import { test, expect, Page } from "@playwright/test";

/**
 * Mocked tests for the monitoring dashboard.
 *
 * The pipeline-run KPI and the success/failed/skipped chart are fed by the real
 * cross-pipeline aggregation endpoint (`GET /api/v1/pipelines/runs/stats`) — not by
 * synthetic constants. All remaining panels still render demo series and must carry
 * an explicit "Sample data" badge. No Loom backend is required — REST is intercepted
 * via `page.route` and the events socket via `page.routeWebSocket`. Modelled on
 * `pipeline-run-mocked.spec.ts`.
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

async function mockBackend(page: Page, opts: { statsHttpStatus?: number } = {}) {
  const statsFetch = { calls: 0 };

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

  // Absorb the pipeline-events socket so it never reaches a real backend.
  page.routeWebSocket(/\/pipelines\/events\/ws/, () => { /* accept and hold */ });

  return { statsFetch };
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

  test("every synthetic panel carries a sample-data badge, the real ones none", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenMonitoring(page);

    // 6 synthetic KPI cards + 5 synthetic charts.
    await expect(page.getByTestId("sample-data-badge")).toHaveCount(11);
    await expect(page.getByTestId("kpi-pipeline-runs").getByTestId("sample-data-badge")).toHaveCount(0);
    await expect(page.getByTestId("monitoring-runs-chart").getByTestId("sample-data-badge")).toHaveCount(0);
  });

  test("a failing stats endpoint degrades to a warning without breaking the page", async ({ page }) => {
    await mockBackend(page, { statsHttpStatus: 500 });
    await loginAndOpenMonitoring(page);

    await expect(page.getByText("Failed to load pipeline run data")).toBeVisible({ timeout: 10_000 });
    // The KPI falls back to zero real runs instead of synthetic numbers.
    await expect(page.getByTestId("kpi-pipeline-runs")).toContainText("0");
    await expect(page.getByTestId("kpi-pipeline-runs")).not.toContainText("+8%");
    // The sample panels keep rendering.
    await expect(page.getByTestId("sample-data-badge")).toHaveCount(11);
  });
});
