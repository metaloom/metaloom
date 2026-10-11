import { test, expect, Page } from "@playwright/test";

/**
 * Mocked tests for "where does a pipeline's time go".
 *
 * The editor loads `GET /pipelines/:uuid/node-stats` once per pipeline and scope and shows it twice:
 * as the accumulated time on each node card, and as the Performance tab of the bottom panel - a
 * ranking of the nodes with a drill-down into one. Reused executions are marked on the task rows.
 *
 * No Loom backend is required; every REST call is intercepted.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const RUN_UUID = "run-0000-0000-0000-000000000001";
const OLD_RUN_UUID = "run-0000-0000-0000-000000000000";
const ITEM_UUID = "item-0000-0000-0000-000000000001";

const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
    { id: "whisper", type: "whisper", label: "Transcribe", position: { x: 520, y: 0 }, data: {} },
  ],
  edges: [
    { id: "e1", source: "src", target: "sha512" },
    { id: "e2", source: "src", target: "whisper" },
  ],
};

function pipeline() {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "aaaaaaaa-0000-0000-0000-000000000001",
    versionNumber: 1,
    name: "Transcribe Library",
    description: "Mocked pipeline",
    definition: DEFINITION,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

const RUN = {
  uuid: RUN_UUID, pipelineUuid: PIPELINE_UUID, started: "2026-10-11T10:00:00Z", finished: "2026-10-11T10:01:00Z",
  status: "success", mediaCount: 1, successCount: 1, failureCount: 0, dryRun: false,
};

const ITEM = { uuid: ITEM_UUID, runUuid: RUN_UUID, itemSeq: 0, mediaPath: "/media/library/interview.mp4", state: "SUCCESS" };

/** The second run reused the transcript and recomputed the hash. */
const TASKS = [
  { uuid: "t-1", itemUuid: ITEM_UUID, runUuid: RUN_UUID, nodeId: "whisper", nodeKind: "whisper", elementSeq: 0, generation: 0,
    state: "COMPLETED", attempt: 1, maxAttempts: 1, durationMs: 14, origin: "REMOTE", outputs: {} },
  { uuid: "t-2", itemUuid: ITEM_UUID, runUuid: RUN_UUID, nodeId: "sha512", nodeKind: "sha512", elementSeq: 0, generation: 0,
    state: "COMPLETED", attempt: 1, maxAttempts: 1, durationMs: 900, origin: "COMPUTED", outputs: {} },
];

function stats(runCount: number) {
  return {
    pipelineUuid: PIPELINE_UUID,
    runCount,
    tasks: 4,
    reused: 1,
    totalDurationMs: 182_814,
    estimatedSavedMs: 180_000,
    runs: [
      { uuid: RUN_UUID, status: "COMPLETED", started: Date.parse("2026-10-11T10:00:00Z"), durationMs: 60_000, pipelineVersion: 1 },
      { uuid: OLD_RUN_UUID, status: "COMPLETED", started: Date.parse("2026-10-10T10:00:00Z"), durationMs: 200_000, pipelineVersion: 1 },
    ].slice(0, runCount),
    nodes: [
      {
        nodeId: "whisper", nodeKind: "whisper", tasks: 2, computed: 1, reused: 1, failed: 0, skipped: 0,
        totalDurationMs: 180_014, computedDurationMs: 180_000, avgDurationMs: 180_000, p50DurationMs: 180_000,
        p95DurationMs: 180_000, maxDurationMs: 180_000, estimatedSavedMs: 180_000, share: 0.985,
        histogram: [{ fromMs: 131_072, toMs: 262_144, count: 1 }],
        runs: [
          { runUuid: RUN_UUID, tasks: 1, reused: 1, totalDurationMs: 14 },
          { runUuid: OLD_RUN_UUID, tasks: 1, reused: 0, totalDurationMs: 180_000 },
        ],
      },
      {
        nodeId: "sha512", nodeKind: "sha512", tasks: 2, computed: 2, reused: 0, failed: 0, skipped: 0,
        totalDurationMs: 2_800, computedDurationMs: 2_800, avgDurationMs: 1_400, p50DurationMs: 1_400,
        p95DurationMs: 1_850, maxDurationMs: 1_900, estimatedSavedMs: 0, share: 0.015,
        histogram: [{ fromMs: 512, toMs: 1_024, count: 1 }, { fromMs: 1_024, toMs: 2_048, count: 1 }],
        runs: [
          { runUuid: RUN_UUID, tasks: 1, reused: 0, totalDurationMs: 900 },
          { runUuid: OLD_RUN_UUID, tasks: 1, reused: 0, totalDurationMs: 1_900 },
        ],
      },
    ],
  };
}

const EMPTY_STATS = { pipelineUuid: PIPELINE_UUID, runCount: 0, tasks: 0, reused: 0, totalDurationMs: 0, estimatedSavedMs: 0, runs: [], nodes: [] };

async function mockBackend(page: Page, opts: { empty?: boolean } = {}) {
  const calls = { stats: [] as string[] };
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/pipeline/node-descriptors", route => route.fulfill(json({ nodeDescriptors: [], contentTypes: [] })));
  await page.route("**/api/v1/pipelines", route => route.fulfill(json({ data: [pipeline()], _metainfo: { totalCount: 1 } })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route => route.fulfill(json({ data: [pipeline()], _metainfo: { totalCount: 1 } })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route => route.fulfill(json({ data: opts.empty ? [] : [RUN] })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items`, route => route.fulfill(json({ data: [ITEM] })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`, route => route.fulfill(json({ data: TASKS })));
  // A regex, not a glob: the scope travels as a query string, and the glob would not match it.
  await page.route(new RegExp(`/api/v1/pipelines/${PIPELINE_UUID}/node-stats(\\?.*)?$`), route => {
    const url = new URL(route.request().url());
    calls.stats.push(url.search);
    const limit = Number(url.searchParams.get("limit") ?? "20");
    route.fulfill(json(opts.empty ? EMPTY_STATS : stats(Math.min(limit, 2))));
  });
  return { calls };
}

async function loginAndOpenEditor(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("pipeline-node-whisper")).toBeVisible({ timeout: 10_000 });
}

async function openPerformanceTab(page: Page) {
  await page.getByTestId("pipeline-bottom-tab-performance").click();
  await expect(page.getByTestId("pipeline-performance-panel")).toBeVisible({ timeout: 5_000 });
}

test.describe("Pipeline performance – mocked", () => {

  test("node cards show their accumulated execution time and reused share, outside debug mode", async ({ page }) => {
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);

    await expect.poll(() => calls.stats.length, { timeout: 5_000 }).toBeGreaterThan(0);
    expect(calls.stats[0]).toContain("limit=20");

    const whisperTime = page.getByTestId("pipeline-node-time-whisper");
    await expect(whisperTime).toBeVisible({ timeout: 5_000 });
    await expect(whisperTime).toHaveAttribute("data-total-ms", "180014");
    await expect(whisperTime).toContainText("3 min 00 s");
    await expect(page.getByTestId("pipeline-node-reused-whisper")).toContainText("50 % reused");

    await expect(page.getByTestId("pipeline-node-time-sha512")).toContainText("2.8 s");
    await expect(page.getByTestId("pipeline-node-reused-sha512")).toHaveCount(0);
    // The source never ran as a task here, so its card stays untouched.
    await expect(page.getByTestId("pipeline-node-time-src")).toHaveCount(0);
  });

  test("the Performance tab ranks the nodes and drills into the most expensive one", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await openPerformanceTab(page);

    await expect(page.getByTestId("pipeline-performance-total")).toContainText("3 min 03 s");
    await expect(page.getByTestId("pipeline-performance-reused")).toContainText("1 / 4 (25 %)");
    await expect(page.getByTestId("pipeline-performance-saved")).toContainText("3 min 00 s");

    const ranking = page.getByTestId("pipeline-performance-ranking");
    await expect(ranking).toContainText("Transcribe");
    await expect(ranking).toContainText("SHA-512");

    // The drill-down opens on the node that costs the most.
    const detail = page.getByTestId("pipeline-performance-detail");
    await expect(detail).toHaveAttribute("data-node-id", "whisper");
    await expect(page.getByTestId("pipeline-performance-node-reused")).toContainText("1 (50 %)");
    await expect(page.getByTestId("pipeline-performance-figures")).toContainText("3 min 00 s");
    await expect(page.getByTestId("pipeline-performance-histogram")).toBeVisible();
    await expect(page.getByTestId("pipeline-performance-trend")).toBeVisible();
  });

  test("picking another node moves the drill-down to it", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await openPerformanceTab(page);

    // MUI keeps the test id on a hidden native input; the visible control is the combobox.
    await page.getByRole("combobox", { name: "Node", exact: true }).click();
    await page.getByRole("option", { name: /SHA-512/ }).click();

    const detail = page.getByTestId("pipeline-performance-detail");
    await expect(detail).toHaveAttribute("data-node-id", "sha512");
    await expect(page.getByTestId("pipeline-performance-figures")).toContainText("1.4 s");
    await expect(page.getByTestId("pipeline-performance-figures")).toContainText("1.9 s");
  });

  test("changing the scope reloads the figures for that many runs", async ({ page }) => {
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await openPerformanceTab(page);

    await page.getByRole("combobox", { name: "Runs covered", exact: true }).click();
    await page.getByRole("option", { name: "Last run", exact: true }).click();

    await expect.poll(() => calls.stats.some(q => q.includes("limit=1")), { timeout: 5_000 }).toBe(true);
    await expect(page.getByTestId("pipeline-performance-panel")).toContainText("Runs");
  });

  test("a pipeline that never ran says so instead of drawing an empty chart", async ({ page }) => {
    await mockBackend(page, { empty: true });
    await loginAndOpenEditor(page);
    await openPerformanceTab(page);

    await expect(page.getByTestId("pipeline-performance-empty")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-performance-ranking")).toHaveCount(0);
    await expect(page.getByTestId("pipeline-node-time-whisper")).toHaveCount(0);
  });

  test("a reused execution is marked in the run's task list", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId(`pipeline-run-row-${RUN_UUID}`).click();
    await expect(page.getByTestId("pipeline-run-detail-drawer")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-run-item").first().click();

    await expect(page.getByTestId("run-task-reused-whisper")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("run-task-reused-whisper")).toContainText("Reused");
    await expect(page.getByTestId("run-task-reused-sha512")).toHaveCount(0);
  });

  test("the log tab is still the default and still works", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await expect(page.getByTestId("pipeline-log-status")).toBeVisible();
    await openPerformanceTab(page);
    await expect(page.getByTestId("pipeline-log-status")).toHaveCount(0);
    await page.getByTestId("pipeline-bottom-tab-log").click();
    await expect(page.getByTestId("pipeline-log-status")).toBeVisible();
  });
});
