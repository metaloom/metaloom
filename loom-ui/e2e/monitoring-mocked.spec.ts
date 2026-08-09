import { test, expect, Page } from "@playwright/test";

/**
 * Mocked tests for the monitoring dashboard.
 *
 * Every panel derives from a real endpoint. Two of them, independently:
 *
 * - `GET /api/v1/pipelines/runs/stats` — the run history, the only series with a day axis.
 * - `GET /api/v1/metrics` — the `loom_*` meter catalog, polled. Meters carry no history, so its
 *   panels are either instantaneous or a rate differenced across two polls.
 *
 * Nothing renders a synthetic series any more, so there is no sample-data badge left to assert —
 * this suite asserts its *absence*, which is the check that stops one creeping back. No Loom
 * backend is required: REST is intercepted via `page.route` and the events socket via
 * `page.routeWebSocket`.
 */

/** ISO date (YYYY-MM-DD) of `daysAgo` days before today, local time. */
function isoDate(daysAgo: number): string {
  const d = new Date();
  d.setHours(12, 0, 0, 0);
  d.setDate(d.getDate() - daysAgo);
  return d.toISOString().slice(0, 10);
}

/**
 * 14 zero-filled buckets ending today. Previous window (days 13..7 ago) holds 4 runs,
 * current window (days 6..0 ago) 5 runs → KPI 5, delta +25%.
 */
function statsBody() {
  const daily = Array.from({ length: 14 }, (_, i) => ({
    date: isoDate(13 - i),
    runCount: 0,
    successCount: 0,
    failureCount: 0,
    skippedCount: 0,
  }));
  // Previous 7-day window: 4 runs.
  daily[2] = { ...daily[2], runCount: 3, successCount: 30, failureCount: 1, skippedCount: 0 };
  daily[5] = { ...daily[5], runCount: 1, successCount: 5, failureCount: 0, skippedCount: 1 };
  // Current 7-day window: 5 runs.
  daily[10] = { ...daily[10], runCount: 2, successCount: 20, failureCount: 2, skippedCount: 1 };
  daily[12] = { ...daily[12], runCount: 3, successCount: 12, failureCount: 3, skippedCount: 2 };
  return { daily };
}

type Metric = {
  name: string;
  type: "COUNTER" | "GAUGE" | "TIMER";
  tags?: Record<string, string>;
  value?: number;
  count?: number;
  sumSeconds?: number;
  maxSeconds?: number;
  meanSeconds?: number;
};

function gauge(name: string, value: number, tags: Record<string, string> = {}): Metric {
  return { name, type: "GAUGE", tags, value };
}

function counter(name: string, value: number, tags: Record<string, string> = {}): Metric {
  return { name, type: "COUNTER", tags, value };
}

function timer(name: string, count: number, sumSeconds: number, maxSeconds: number, tags: Record<string, string>): Metric {
  return { name, type: "TIMER", tags, count, sumSeconds, maxSeconds, meanSeconds: count ? sumSeconds / count : 0 };
}

/**
 * A catalog snapshot. `results` and `inFlight` vary per poll so the live charts have something to
 * difference; everything else is fixed so the KPI assertions are stable.
 *
 * The names carry the scraped Prometheus suffixes, exactly as the endpoint serves them — a fixture
 * spelling `loom_node_tasks_dispatch_failed` without `_total` would pass while the real response
 * fails, which is the drift this whole route was built to avoid.
 */
function metricsBody(opts: { timestamp: string; results: number; inFlight: number }) {
  const metrics: Metric[] = [
    gauge("loom_pipeline_runs_active", 2),
    gauge("loom_node_tasks_inflight", opts.inFlight),
    gauge("loom_node_tasks_inflight_ceiling", 16),
    gauge("loom_processors_connected", 4),
    gauge("loom_processors_by_state", 3, { state: "online" }),
    gauge("loom_processors_by_state", 1, { state: "terminating" }),
    gauge("loom_processors_by_state", 0, { state: "offline" }),
    gauge("loom_processors_by_state", 0, { state: "starting" }),
    gauge("loom_processors_by_state", 0, { state: "paused" }),
    gauge("loom_node_circuit_breaker_state", 0, { kind: "sha512" }),
    gauge("loom_node_circuit_breaker_state", 2, { kind: "whisper" }),
    counter("loom_node_tasks_dispatch_failed_total", 5, { reason: "no_processor" }),
    counter("loom_node_tasks_dispatch_failed_total", 2, { reason: "socket_gone" }),
    counter("loom_node_tasks_deadlettered_total", 3, { kind: "ocr" }),
    counter("loom_node_results_received_total", opts.results, { kind: "sha512", state: "completed" }),
    counter("loom_node_results_received_total", 4, { kind: "sha512", state: "failed" }),
    counter("loom_node_results_received_total", 1, { kind: "whisper", state: "skipped" }),
    // 4 completed sha512 tasks totalling 2s → a 500 ms mean.
    timer("loom_node_task_latency_seconds", 4, 2, 1.5, { kind: "sha512", state: "completed" }),
  ];
  return { timestamp: opts.timestamp, metrics };
}

async function mockBackend(page: Page, opts: { statsHttpStatus?: number; metricsHttpStatus?: number } = {}) {
  const statsFetch = { calls: 0 };
  const metricsFetch = { calls: 0 };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipelines/runs/stats*", route => {
    statsFetch.calls += 1;
    const status = opts.statsHttpStatus ?? 200;
    if (status >= 400) {
      route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ message: "boom" }) });
      return;
    }
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(statsBody()) });
  });
  await page.route("**/api/v1/metrics*", route => {
    metricsFetch.calls += 1;
    const status = opts.metricsHttpStatus ?? 200;
    if (status >= 400) {
      route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ message: "boom" }) });
      return;
    }
    // Each poll advances the counter and the depth, five seconds apart, so the differenced series
    // has real movement rather than a flat line the chart could also produce from stale data.
    const nth = metricsFetch.calls;
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(metricsBody({
        timestamp: new Date(Date.UTC(2026, 7, 9, 11, 0, 0) + nth * 5_000).toISOString(),
        results: 100 + nth * 50,
        inFlight: 5 + nth,
      })),
    });
  });

  // Absorb the pipeline-events socket so it never reaches a real backend.
  page.routeWebSocket(/\/pipelines\/events\/ws/, () => { /* accept and hold */ });

  return { statsFetch, metricsFetch };
}

async function loginAndOpenMonitoring(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Monitoring" }).first().click();
  await expect(page.getByTestId("kpi-pipeline-runs")).toBeVisible({ timeout: 10_000 });
}

test.describe("monitoring dashboard (mocked)", () => {
  test("pipeline-run KPI derives from the stats endpoint, not constants", async ({ page }) => {
    const { statsFetch } = await mockBackend(page);
    await loginAndOpenMonitoring(page);

    const kpi = page.getByTestId("kpi-pipeline-runs");
    // 5 runs in the current 7-day window of the mocked stats.
    await expect(kpi).toContainText("5", { timeout: 10_000 });
    // The delta is computed from the mocked series (5 vs 4 → +25%), not hardcoded.
    await expect(kpi).toContainText("+25%");
    await expect(kpi).not.toContainText("+8%");
    expect(statsFetch.calls).toBeGreaterThan(0);
  });

  test("run chart renders the mocked success/failed/skipped series", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenMonitoring(page);

    const chart = page.getByTestId("monitoring-runs-chart");
    await expect(chart).toBeVisible();
    await expect(chart.getByText("Success")).toBeVisible({ timeout: 10_000 });
    await expect(chart.getByText("Failed")).toBeVisible();
    await expect(chart.getByText("Skipped")).toBeVisible();
    // The stacked bars are drawn from the mocked buckets.
    expect(await chart.locator(".recharts-bar-rectangle").count()).toBeGreaterThan(0);
  });

  test("no panel carries a sample-data badge — nothing on this screen is synthetic", async ({ page }) => {
    const { metricsFetch } = await mockBackend(page);
    await loginAndOpenMonitoring(page);

    await expect(page.getByTestId("kpi-tasks-inflight")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("sample-data-badge")).toHaveCount(0);
    expect(metricsFetch.calls).toBeGreaterThan(0);
  });

  // ── KPI tiles, each traced back to its series ─────────────────────────

  test("the fleet KPIs read the metrics snapshot", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenMonitoring(page);

    await expect(page.getByTestId("kpi-active-runs")).toContainText("2", { timeout: 10_000 });
    // Depth against its ceiling: the pair is what distinguishes busy from saturated.
    await expect(page.getByTestId("kpi-tasks-inflight")).toContainText("of 16 slots");
    // 4 completed tasks totalling 2s → 500 ms.
    await expect(page.getByTestId("kpi-task-latency")).toContainText("500");
    // Online is a subset of connected, and only online workers are placeable.
    await expect(page.getByTestId("kpi-workers")).toContainText("3");
    await expect(page.getByTestId("kpi-workers")).toContainText("of 4 connected");
    // Both dispatch-failure reasons roll into one number; dead-letters ride along as the subtitle.
    await expect(page.getByTestId("kpi-dispatch-failures")).toContainText("7");
    await expect(page.getByTestId("kpi-dispatch-failures")).toContainText("3 tasks dead-lettered");
  });

  test("a parked node kind is named rather than left to be inferred from flat throughput", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenMonitoring(page);

    const parked = page.getByTestId("kpi-parked-kinds");
    await expect(parked).toContainText("1", { timeout: 10_000 });
    await expect(parked).toContainText("whisper");
    // sha512's breaker reads 0 (closed), so it must not be listed.
    await expect(parked).not.toContainText("sha512");
  });

  // ── Charts ───────────────────────────────────────────────────────────

  test("the per-kind charts derive from the labelled series", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenMonitoring(page);

    const outcomes = page.getByTestId("monitoring-outcomes-chart");
    await expect(outcomes).toBeVisible({ timeout: 10_000 });
    await expect(outcomes.getByText("sha512")).toBeVisible();
    await expect(outcomes.getByText("whisper")).toBeVisible();
    expect(await outcomes.locator(".recharts-bar-rectangle").count()).toBeGreaterThan(0);

    // Only the completed sha512 series is timed, so it is the only bar here.
    const latency = page.getByTestId("monitoring-latency-chart");
    await expect(latency.getByText("sha512")).toBeVisible();
    await expect(latency.getByText("whisper")).toHaveCount(0);

    // Zero states are kept: a fleet stuck in terminating is exactly what this chart is for.
    const workers = page.getByTestId("monitoring-workers-chart");
    await expect(workers.getByText("terminating")).toBeVisible();
    await expect(workers.getByText("offline")).toBeVisible();
  });

  test("the live charts wait for a second poll rather than plotting a total as a rate", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenMonitoring(page);

    const throughput = page.getByTestId("monitoring-throughput-chart");
    // A rate needs an interval, so the first render says so instead of drawing the cumulative total.
    await expect(throughput).toContainText(/Collecting|Reading metrics/, { timeout: 10_000 });

    // The poll interval is 5s; the second sample turns the counter difference into a line.
    // Counted rather than asserted visible: an SVG path with fill="none" has no box, so Playwright
    // reports it hidden however well it is drawn.
    await expect(throughput.locator(".recharts-area-curve")).toHaveCount(1, { timeout: 20_000 });
    await expect(page.getByTestId("monitoring-inflight-chart").locator(".recharts-line-curve"))
      .toHaveCount(2, { timeout: 20_000 });
    // And the placeholder is gone, so the line is the differenced series and not a leftover frame.
    await expect(throughput).not.toContainText("Collecting");
  });

  // ── Degradation ──────────────────────────────────────────────────────

  test("a failing stats endpoint degrades to a warning without breaking the page", async ({ page }) => {
    await mockBackend(page, { statsHttpStatus: 500 });
    await loginAndOpenMonitoring(page);

    await expect(page.getByText("Failed to load pipeline run data")).toBeVisible({ timeout: 10_000 });
    // The KPI falls back to zero real runs instead of synthetic numbers.
    await expect(page.getByTestId("kpi-pipeline-runs")).toContainText("0");
    await expect(page.getByTestId("kpi-pipeline-runs")).not.toContainText("+8%");
    // The metric-fed panels are a separate source and keep working.
    await expect(page.getByTestId("kpi-active-runs")).toContainText("2");
    await expect(page.getByTestId("sample-data-badge")).toHaveCount(0);
  });

  test("a failing metrics endpoint degrades to a warning without breaking the page", async ({ page }) => {
    await mockBackend(page, { metricsHttpStatus: 500 });
    await loginAndOpenMonitoring(page);

    await expect(page.getByTestId("metrics-error")).toBeVisible({ timeout: 10_000 });
    // Dashes, not zeroes: an unreadable gauge is not a fleet that went to zero.
    await expect(page.getByTestId("kpi-active-runs")).toContainText("—");
    await expect(page.getByTestId("kpi-workers")).toContainText("—");
    // The run-stats panels are a separate source and keep working.
    await expect(page.getByTestId("kpi-pipeline-runs")).toContainText("5");
    expect(await page.getByTestId("monitoring-runs-chart").locator(".recharts-bar-rectangle").count()).toBeGreaterThan(0);
    // And still nothing synthetic fills the gap.
    await expect(page.getByTestId("sample-data-badge")).toHaveCount(0);
  });

  test("an instance that has recorded nothing says so instead of drawing zeroes", async ({ page }) => {
    await mockBackend(page);
    // An empty but successful catalog: a freshly started instance before any work.
    await page.route("**/api/v1/metrics*", route =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ timestamp: new Date().toISOString(), metrics: [] }),
      })
    );
    await loginAndOpenMonitoring(page);

    await expect(page.getByTestId("monitoring-outcomes-chart")).toContainText("No node results recorded yet", { timeout: 10_000 });
    await expect(page.getByTestId("monitoring-latency-chart")).toContainText("No task has completed yet");
    await expect(page.getByTestId("monitoring-workers-chart")).toContainText("No workers have ever registered");
    // No error banner: an empty catalog is a valid answer, not a failure.
    await expect(page.getByTestId("metrics-error")).toHaveCount(0);
  });
});
