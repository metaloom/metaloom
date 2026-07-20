import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Smoke tests for the pipeline editor's live run status, driven by the
 * multiplexed UI events WebSocket (`/api/v1/pipelines/events/ws`).
 *
 * No running Loom backend is required: REST is intercepted via `page.route`,
 * and the events socket via `page.routeWebSocket` (mock mode — Playwright plays
 * the server, so the test pushes `PipelineEventMessage` frames and asserts the
 * canvas + run banner react without a reload). See `cortex-mocked.spec.ts` for
 * the equivalent processor-channel test.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Quick Hash";

/** A 2-node graph so node ids `src` / `sha512` are addressable on the canvas. */
const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
  ],
  edges: [{ id: "e1", source: "src", target: "sha512" }],
};

function pipeline() {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "aaaaaaaa-0000-0000-0000-000000000001",
    versionNumber: 1,
    name: PIPELINE_NAME,
    description: "Mocked pipeline",
    definition: DEFINITION,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

function run(status: string, over: Partial<Record<string, unknown>> = {}) {
  return {
    uuid: "run-0000-0000-0000-000000000001",
    pipelineUuid: PIPELINE_UUID,
    started: "2026-07-20T10:00:00Z",
    status,
    mediaCount: 2,
    successCount: status === "success" ? 2 : 0,
    failureCount: 0,
    dryRun: false,
    ...over,
  };
}

/**
 * Install REST routes. `runsState.runs` is mutated by the test right before it
 * pushes a PIPELINE_* frame, so the editor's event-triggered `loadRuns()` refetch
 * observes the updated run list.
 *
 * Playwright resolves the most-recently-registered matching handler first, so
 * these go least-specific → most-specific.
 */
async function mockBackend(page: Page) {
  const runsState = { runs: [] as unknown[] };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: [], contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: runsState.runs }) })
  );

  return runsState;
}

/**
 * Intercept the UI events socket. Every connection the app opens is captured in
 * `sockets` (a reconnect appends a new one), so `waitForSocket(n)` can await the
 * first connection and, after a drop, the reconnected one.
 */
function mockEventsSocket(page: Page): { registered: Promise<void>; waitForSocket: (index: number) => Promise<WebSocketRoute> } {
  const sockets: WebSocketRoute[] = [];
  const registered = page.routeWebSocket(/\/pipelines\/events\/ws/, ws => { sockets.push(ws); });
  async function waitForSocket(index: number): Promise<WebSocketRoute> {
    await expect.poll(() => sockets.length, { timeout: 15_000 }).toBeGreaterThan(index);
    return sockets[index];
  }
  return { registered: Promise.resolve(registered), waitForSocket };
}

/** Push a pipeline-channel frame (no `channel` field → routed to pipeline listeners). */
function pushPipelineEvent(ws: WebSocketRoute, type: string, over: Record<string, unknown> = {}) {
  ws.send(JSON.stringify({ type, pipelineName: PIPELINE_NAME, timestamp: 0, ...over }));
}

async function loginAndOpenEditor(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("pipeline-node-sha512")).toBeVisible({ timeout: 10_000 });
}

test.describe("Pipeline live events – mocked", () => {

  test("node events drive canvas activity and last-result styling", async ({ page }) => {
    await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);
    const node = page.getByTestId("pipeline-node-sha512");

    // NODE_STARTED lights the node up (pulsing).
    pushPipelineEvent(ws, "NODE_STARTED", { nodeId: "sha512" });
    await expect(node).toHaveAttribute("data-active", "true", { timeout: 5_000 });

    // NODE_COMPLETED clears active and tints the node "completed".
    pushPipelineEvent(ws, "NODE_COMPLETED", { nodeId: "sha512", durationMs: 12 });
    await expect(node).toHaveAttribute("data-active", "false", { timeout: 5_000 });
    await expect(node).toHaveAttribute("data-result", "completed", { timeout: 5_000 });

    // NODE_FAILED tints it "failed".
    pushPipelineEvent(ws, "NODE_STARTED", { nodeId: "src" });
    pushPipelineEvent(ws, "NODE_FAILED", { nodeId: "src", message: "boom" });
    const srcNode = page.getByTestId("pipeline-node-src");
    await expect(srcNode).toHaveAttribute("data-result", "failed", { timeout: 5_000 });
    await expect(srcNode).toHaveAttribute("data-active", "false", { timeout: 5_000 });
  });

  test("pipeline lifecycle events drive the run banner and refresh history", async ({ page }) => {
    const runsState = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);

    // No runs yet → no banner.
    await expect(page.getByTestId("pipeline-run-banner")).toHaveCount(0);

    // PIPELINE_STARTED → history refetch now returns a running run → banner shows it.
    runsState.runs = [run("running")];
    pushPipelineEvent(ws, "PIPELINE_STARTED");
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "running", { timeout: 5_000 });

    // PIPELINE_COMPLETED → refetch returns a completed run → banner flips to success.
    runsState.runs = [run("success", { finished: "2026-07-20T10:01:00Z" })];
    pushPipelineEvent(ws, "PIPELINE_COMPLETED", { durationMs: 60000 });
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "success", { timeout: 5_000 });
  });

  test("a dropped socket reconnects and keeps applying events", async ({ page }) => {
    await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws0 = await waitForSocket(0);

    // Confirm the first socket works.
    pushPipelineEvent(ws0, "NODE_STARTED", { nodeId: "sha512" });
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-active", "true", { timeout: 5_000 });

    // Drop it — the client should reconnect (exponential backoff, first retry ~1s).
    await ws0.close();

    const ws1 = await waitForSocket(1);
    // A frame over the reconnected socket still updates the canvas.
    pushPipelineEvent(ws1, "NODE_COMPLETED", { nodeId: "sha512", durationMs: 5 });
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-result", "completed", { timeout: 8_000 });
  });
});
