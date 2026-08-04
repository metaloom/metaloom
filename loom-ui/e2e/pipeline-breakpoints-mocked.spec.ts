import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Mocked tests for breakpoints and stepping.
 *
 * A breakpoint holds a node's output back from everything downstream. The node still runs — that
 * is the whole point, since a result you cannot see is not worth stopping for — so the two things
 * worth pinning on the canvas are that the *gutter dot* arms the halt and that a *held* node looks
 * unmistakably stopped rather than busy.
 *
 * Both directions are exercised: the operator clicking (which must reach the REST routes) and the
 * server pushing a `NODE_BREAKPOINT_HELD` frame (which must ring the node without a click, because
 * a run can be debugged from a second tab or stop while nobody is looking).
 *
 * No Loom backend is required; REST is intercepted with `page.route` and the events socket with
 * `page.routeWebSocket`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Quick Hash";
const RUN_UUID = "run-0000-0000-0000-000000000001";
const ITEM_UUID = "item-0000-0000-0000-000000000001";

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

/** A live run, so the debug controls have something to act on. */
const RUN = {
  uuid: RUN_UUID,
  pipelineUuid: PIPELINE_UUID,
  started: "2026-07-20T10:00:00Z",
  status: "RUNNING",
  mediaCount: 3,
  successCount: 1,
  failureCount: 0,
  dryRun: false,
};

const TASKS = [
  {
    uuid: "task-1", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "sha512", nodeKind: "sha512", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 12,
    outputs: {
      sha512: {
        contentType: "hash/sha512", cardinality: "ONE",
        elements: [{ origin: { itemId: ITEM_UUID, seq: 0, total: 1 }, value: "0f8ef1c9ab34de56789012" }],
      },
    },
  },
];

interface Calls {
  put: Array<string[]>;
  steps: number;
  continued: string[];
  tasks: number;
  runBody: Record<string, unknown> | null;
}

async function mockBackend(page: Page, armed: string[] = [], held: unknown[] = []) {
  const calls: Calls = { put: [], steps: 0, continued: [], tasks: 0, runBody: null };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: [], contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [RUN] }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/run`, route => {
    calls.runBody = JSON.parse(route.request().postData() || "{}");
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ runUuid: RUN_UUID, dispatched: true }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`, route => {
    calls.tasks += 1;
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: TASKS }) });
  });

  // GET reports what is armed and held; PUT replaces the armed set and echoes it back.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/breakpoints`, route => {
    if (route.request().method() === "PUT") {
      const body = JSON.parse(route.request().postData() || "{}");
      calls.put.push(body.nodeIds ?? []);
      route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify({ nodeIds: body.nodeIds ?? [], held: [] }),
      });
      return;
    }
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeIds: armed, held }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/breakpoints/*/continue`, route => {
    calls.continued.push(decodeURIComponent(new URL(route.request().url()).pathname.split("/").at(-2)!));
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "Released 1 held execution of node sha512" }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/steps`, route => {
    calls.steps += 1;
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeIds: ["sha512"], held: [] }) });
  });

  return { calls };
}

/**
 * Intercept the UI events socket so the test can play the server.
 *
 * `routeWebSocket` returns a promise, and it has to be awaited *before* the page navigates —
 * a route registered after the app has already opened its socket never intercepts anything, and
 * `waitForSocket` then polls an array that will stay empty forever. Under a loaded full-suite
 * run that gap is wide enough to hit.
 */
async function mockEventsSocket(page: Page): Promise<(index: number) => Promise<WebSocketRoute>> {
  const sockets: WebSocketRoute[] = [];
  await page.routeWebSocket(/\/pipelines\/events\/ws/, ws => { sockets.push(ws); });
  return async function waitForSocket(index: number): Promise<WebSocketRoute> {
    await expect.poll(() => sockets.length, { timeout: 15_000 }).toBeGreaterThan(index);
    return sockets[index];
  };
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

/**
 * Click the breakpoint gutter of a node.
 *
 * Dispatched on the element rather than clicked at its coordinates: React Flow's minimap floats
 * over the bottom-right of the canvas and the second node of this two-node graph lands under it
 * after `fitView`, where even a forced positional click is received by the minimap. The event
 * still bubbles into React's handler exactly as a real click would.
 */
async function clickGutter(page: Page, nodeId: string) {
  await page.getByTestId(`pipeline-node-breakpoint-${nodeId}`).dispatchEvent("click");
}

test.describe("Pipeline breakpoints – mocked", () => {

  test("the breakpoint gutter appears only in debug mode", async ({ page }) => {
    // Debug off means the editor is exactly what it always was. A designer laying out a graph
    // should never see a debugger's margin.
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await expect(page.getByTestId("pipeline-node-breakpoint-sha512")).toHaveCount(0);

    await page.getByTestId("pipeline-debug-toggle").click();

    await expect(page.getByTestId("pipeline-node-breakpoint-sha512")).toBeVisible({ timeout: 5_000 });
  });

  test("clicking the gutter arms a breakpoint and sends the whole set", async ({ page }) => {
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();

    await clickGutter(page, "sha512");

    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-breakpoint", "true");
    // The request carries the armed set, not a delta — that is what keeps the editor and the
    // run from disagreeing about what is armed.
    await expect.poll(() => calls.put.length, { timeout: 5_000 }).toBe(1);
    expect(calls.put[0]).toEqual(["sha512"]);
  });

  test("clicking the gutter again disarms it", async ({ page }) => {
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();

    await clickGutter(page, "sha512");
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-breakpoint", "true");

    await clickGutter(page, "sha512");

    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-breakpoint", "false");
    await expect.poll(() => calls.put.length, { timeout: 5_000 }).toBe(2);
    expect(calls.put[1]).toEqual([]);
  });

  test("arming a breakpoint does not select the node", async ({ page }) => {
    // The canvas selects a node on click. Arming a breakpoint is not selecting, and having the
    // inspector swap over every time you set one would be maddening.
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();

    await clickGutter(page, "sha512");

    await expect(page.getByTestId("pipeline-node-sha512")).not.toHaveClass(/selected/);
  });

  test("a NODE_BREAKPOINT_HELD frame rings the node without a click", async ({ page }) => {
    // The run can stop while nobody is looking at this tab, so the ring has to come from the
    // frame rather than from the click that armed it.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
      nodeId: "sha512",
      pipelineRunUuid: RUN_UUID,
      itemUuid: ITEM_UUID,
      elementSeq: 0,
      mediaPath: "/media/library/holiday-clip.mp4",
    });

    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-held", "true", { timeout: 5_000 });
    await expect(page.getByTestId("pipeline-node-held-sha512")).toBeVisible();
  });

  test("a held node stops looking busy", async ({ page }) => {
    // The pulse means "working"; a held node is the opposite of working. Leaving it pulsing
    // would say precisely the wrong thing.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    // Re-sent until it lands. A frame pushed in the gap between the socket being routed and the
    // app attaching its message handler is simply dropped, which under a loaded full-suite run is
    // a real race — and this is the one test here that asserts an *intermediate* state, so it is
    // the one that notices.
    await expect.poll(async () => {
      pushPipelineEvent(ws, "NODE_STARTED", { nodeId: "sha512", pipelineRunUuid: RUN_UUID });
      return page.getByTestId("pipeline-node-sha512").getAttribute("data-active");
    }, { timeout: 15_000 }).toBe("true");

    pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
      nodeId: "sha512", pipelineRunUuid: RUN_UUID, itemUuid: ITEM_UUID, elementSeq: 0,
    });

    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-active", "false", { timeout: 5_000 });
  });

  test("a hold loads the stopped item's results", async ({ page }) => {
    // Stopping somewhere and not being shown what is there would defeat the point of stopping.
    const waitForSocket = await mockEventsSocket(page);
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
      nodeId: "sha512",
      pipelineRunUuid: RUN_UUID,
      itemUuid: ITEM_UUID,
      elementSeq: 0,
      mediaPath: "/media/library/holiday-clip.mp4",
    });

    await expect.poll(() => calls.tasks, { timeout: 5_000 }).toBe(1);
    await expect(page.getByTestId("pipeline-inspected-item")).toContainText("holiday-clip.mp4");
    await expect(page.getByTestId("pipeline-node-sha512").getByTestId("node-result-port-sha512"))
      .toBeVisible({ timeout: 5_000 });
  });

  test("the transport appears only once something is actually held", async ({ page }) => {
    // Continue and Step mean nothing with nothing held, and controls that are almost always
    // disabled are just clutter.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    await expect(page.getByTestId("pipeline-debug-step")).toHaveCount(0);

    // Re-pushed rather than sent once: `routeWebSocket` hands over the socket as soon as the page
    // opens it, which can be before the app has attached its message handler, and a frame that
    // lands in that gap is dropped with nothing to retry it. The gap is invisible when this spec
    // runs alone and wide enough to hit under a full-suite run.
    await expect
      .poll(async () => {
        pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
          nodeId: "sha512", pipelineRunUuid: RUN_UUID, itemUuid: ITEM_UUID, elementSeq: 0,
        });
        return page.getByTestId("pipeline-debug-step").count();
      }, { timeout: 15_000 })
      .toBeGreaterThan(0);

    await expect(page.getByTestId("pipeline-debug-step")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-debug-continue")).toBeVisible();
    await expect(page.getByTestId("pipeline-debug-held-count")).toHaveAttribute("data-held-count", "1");
  });

  test("Step calls the steps route and Continue releases the node", async ({ page }) => {
    const waitForSocket = await mockEventsSocket(page);
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
      nodeId: "sha512", pipelineRunUuid: RUN_UUID, itemUuid: ITEM_UUID, elementSeq: 0,
    });
    await expect(page.getByTestId("pipeline-debug-step")).toBeVisible({ timeout: 5_000 });

    await page.getByTestId("pipeline-debug-step").click();
    await expect.poll(() => calls.steps, { timeout: 5_000 }).toBe(1);
    // The step's response said nothing is held any more, so the transport goes away again.
    await expect(page.getByTestId("pipeline-debug-step")).toHaveCount(0, { timeout: 5_000 });

    pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
      nodeId: "sha512", pipelineRunUuid: RUN_UUID, itemUuid: ITEM_UUID, elementSeq: 1,
    });
    await expect(page.getByTestId("pipeline-debug-continue")).toBeVisible({ timeout: 5_000 });

    await page.getByTestId("pipeline-debug-continue").click();
    await expect.poll(() => calls.continued, { timeout: 5_000 }).toEqual(["sha512"]);
  });

  test("a release frame clears the ring", async ({ page }) => {
    // Otherwise a node released from another tab would stay ringed forever, and the operator
    // would think the run was still stopped.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
      nodeId: "sha512", pipelineRunUuid: RUN_UUID, itemUuid: ITEM_UUID, elementSeq: 0,
    });
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-held", "true", { timeout: 5_000 });

    pushPipelineEvent(ws, "NODE_BREAKPOINT_RELEASED", {
      nodeId: "sha512", pipelineRunUuid: RUN_UUID, itemUuid: ITEM_UUID, elementSeq: 0,
    });

    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-held", "false", { timeout: 5_000 });
    await expect(page.getByTestId("pipeline-debug-step")).toHaveCount(0);
  });

  test("a run started with a breakpoint armed carries it in the request", async ({ page }) => {
    // Arming after pressing Run would always be a race: the first item can reach the node
    // before the PUT lands.
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    await clickGutter(page, "sha512");
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-breakpoint", "true");

    await page.getByText("Run", { exact: true }).click();

    await expect.poll(() => calls.runBody, { timeout: 5_000 }).toEqual({
      dryRun: false,
      debug: true,
      breakpoints: ["sha512"],
    });
  });

  test("a run started with debug off carries no breakpoints", async ({ page }) => {
    // Breakpoints are a debugging affordance. A production run must not inherit one that
    // happens to be left armed in the editor.
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByText("Run", { exact: true }).click();

    await expect.poll(() => calls.runBody, { timeout: 5_000 }).toEqual({ dryRun: false, debug: false });
  });

  test("breakpoints reported by the run are adopted on open", async ({ page }) => {
    // A reload, or a second tab, must show the run as it actually is — including a node that
    // was already stopped before this editor was opened.
    await mockEventsSocket(page);
    await mockBackend(page, ["sha512"], [{ nodeId: "sha512", itemUuid: ITEM_UUID, elementSeq: 0 }]);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();

    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-breakpoint", "true", { timeout: 5_000 });
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-held", "true");
    await expect(page.getByTestId("pipeline-debug-held-count")).toHaveAttribute("data-held-count", "1");
  });
});
