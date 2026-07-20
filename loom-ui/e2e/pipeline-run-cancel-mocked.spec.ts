import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Mocked tests for pipeline-run cancellation.
 *
 * A running run shows a Cancel control that issues
 * `POST /api/v1/pipelines/:uuid/runs/:runUuid/cancel` and flips the run banner to
 * `cancelled`; a terminal run shows no Cancel control. No Loom backend is required —
 * REST is intercepted via `page.route` and the events socket via `page.routeWebSocket`.
 * Modelled on `pipeline-events-mocked.spec.ts`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Quick Hash";
const RUN_UUID = "run-0000-0000-0000-000000000001";

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
    uuid: RUN_UUID,
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

async function mockBackend(page: Page) {
  const runsState = { runs: [] as unknown[] };
  const cancelState = { calls: 0, lastPath: "" };

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
  // The cancel action: record the call and flip the run list to cancelled, so the
  // editor's post-cancel loadRuns() refetch observes the new state.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/cancel`, route => {
    cancelState.calls += 1;
    cancelState.lastPath = new URL(route.request().url()).pathname;
    runsState.runs = [run("cancelled", { finished: "2026-07-20T10:00:30Z" })];
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "Pipeline run cancelled" }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: runsState.runs }) })
  );

  return { runsState, cancelState };
}

function mockEventsSocket(page: Page): { registered: Promise<void>; waitForSocket: (index: number) => Promise<WebSocketRoute> } {
  const sockets: WebSocketRoute[] = [];
  const registered = page.routeWebSocket(/\/pipelines\/events\/ws/, ws => { sockets.push(ws); });
  async function waitForSocket(index: number): Promise<WebSocketRoute> {
    await expect.poll(() => sockets.length, { timeout: 15_000 }).toBeGreaterThan(index);
    return sockets[index];
  }
  return { registered: Promise.resolve(registered), waitForSocket };
}

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

test.describe("Pipeline run cancellation – mocked", () => {

  test("a running run shows Cancel, which issues the POST and flips the banner to cancelled", async ({ page }) => {
    const { runsState, cancelState } = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);

    // A running run → banner shows it, with a Cancel button.
    runsState.runs = [run("running")];
    pushPipelineEvent(ws, "PIPELINE_STARTED");
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "running", { timeout: 5_000 });

    const cancelBtn = page.getByTestId("pipeline-run-banner-cancel");
    await expect(cancelBtn).toBeVisible({ timeout: 5_000 });

    // Clicking Cancel issues the POST; the route flips the run list to cancelled and the
    // handler's loadRuns() refetch drives the banner over to cancelled.
    await cancelBtn.click();
    await expect.poll(() => cancelState.calls, { timeout: 5_000 }).toBe(1);
    expect(cancelState.lastPath).toContain(`/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/cancel`);
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "cancelled", { timeout: 5_000 });

    // The cancelled (terminal) run no longer offers a Cancel control.
    await expect(page.getByTestId("pipeline-run-banner-cancel")).toHaveCount(0);
  });

  test("a terminal run shows no Cancel control", async ({ page }) => {
    const { runsState } = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);

    runsState.runs = [run("success", { finished: "2026-07-20T10:01:00Z" })];
    pushPipelineEvent(ws, "PIPELINE_COMPLETED", { durationMs: 60000 });
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "success", { timeout: 5_000 });

    await expect(page.getByTestId("pipeline-run-banner-cancel")).toHaveCount(0);
  });
});
