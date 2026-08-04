import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Mocked tests for pipeline-run pause / resume.
 *
 * `PAUSED` is non-terminal, so a paused run keeps its Cancel control and swaps Pause for
 * Resume. The controls are also reconciled from the events socket, because a run can be
 * paused from another browser tab or from the CLI and every open editor has to agree.
 *
 * No Loom backend is required — REST is intercepted via `page.route` and the events socket
 * via `page.routeWebSocket`. Modelled on `pipeline-run-cancel-mocked.spec.ts`.
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
    successCount: 0,
    failureCount: 0,
    dryRun: false,
    ...over,
  };
}

async function mockBackend(page: Page) {
  const runsState = { runs: [] as unknown[] };
  const calls = { pause: 0, resume: 0, lastPath: "" };

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
  // Pause / resume flip the run list the way the server would, so the handler's
  // post-action loadRuns() refetch observes the new state.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/pause`, route => {
    calls.pause += 1;
    calls.lastPath = new URL(route.request().url()).pathname;
    runsState.runs = [run("paused")];
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "Pipeline run paused" }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/resume`, route => {
    calls.resume += 1;
    calls.lastPath = new URL(route.request().url()).pathname;
    runsState.runs = [run("running")];
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "Pipeline run resumed" }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: runsState.runs }) })
  );

  return { runsState, calls };
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

test.describe("Pipeline run pause / resume – mocked", () => {

  test("a running run pauses, swaps to Resume, and resumes again", async ({ page }) => {
    const { runsState, calls } = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);

    runsState.runs = [run("running")];
    pushPipelineEvent(ws, "PIPELINE_STARTED");
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "running", { timeout: 5_000 });

    // Running: Pause is offered, Resume is not.
    const pauseBtn = page.getByTestId("pipeline-run-banner-pause");
    await expect(pauseBtn).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-run-banner-resume")).toHaveCount(0);

    await pauseBtn.click();
    await expect.poll(() => calls.pause, { timeout: 5_000 }).toBe(1);
    expect(calls.lastPath).toContain(`/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/pause`);
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "paused", { timeout: 5_000 });

    // Paused: the control has become Resume, and Cancel is still available because
    // PAUSED is non-terminal.
    const resumeBtn = page.getByTestId("pipeline-run-banner-resume");
    await expect(resumeBtn).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-run-banner-pause")).toHaveCount(0);
    await expect(page.getByTestId("pipeline-run-banner-cancel")).toBeVisible();

    await resumeBtn.click();
    await expect.poll(() => calls.resume, { timeout: 5_000 }).toBe(1);
    expect(calls.lastPath).toContain(`/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/resume`);
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "running", { timeout: 5_000 });
    await expect(page.getByTestId("pipeline-run-banner-pause")).toBeVisible({ timeout: 5_000 });
  });

  test("a RUN_PAUSED frame from another client flips the control without a click", async ({ page }) => {
    const { runsState, calls } = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);

    runsState.runs = [run("running")];
    pushPipelineEvent(ws, "PIPELINE_STARTED");
    await expect(page.getByTestId("pipeline-run-banner-pause")).toBeVisible({ timeout: 5_000 });

    // Somebody else paused it — the CLI, or a second tab. The server row is already
    // PAUSED by the time the frame arrives, so the mocked list moves with it.
    runsState.runs = [run("paused")];
    pushPipelineEvent(ws, "RUN_PAUSED", { pipelineRunUuid: RUN_UUID });

    await expect(page.getByTestId("pipeline-run-banner-resume")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-run-banner-pause")).toHaveCount(0);
    // This editor issued nothing: the flip came entirely from the frame.
    expect(calls.pause).toBe(0);

    // …and the counterpart frame flips it back.
    runsState.runs = [run("running")];
    pushPipelineEvent(ws, "RUN_RESUMED", { pipelineRunUuid: RUN_UUID });
    await expect(page.getByTestId("pipeline-run-banner-pause")).toBeVisible({ timeout: 5_000 });
    expect(calls.resume).toBe(0);
  });

  test("a terminal run offers neither Pause nor Resume", async ({ page }) => {
    const { runsState } = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    const ws = await waitForSocket(0);

    runsState.runs = [run("success", { successCount: 2, finished: "2026-07-20T10:01:00Z" })];
    pushPipelineEvent(ws, "PIPELINE_COMPLETED", { durationMs: 60000 });
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "success", { timeout: 5_000 });

    await expect(page.getByTestId("pipeline-run-banner-pause")).toHaveCount(0);
    await expect(page.getByTestId("pipeline-run-banner-resume")).toHaveCount(0);
  });
});
