import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Mocked tests for editing a held node's settings and running it again.
 *
 * The feature only makes sense because of the breakpoint underneath it: a held execution has
 * produced a result nothing downstream has seen, so it can be discarded and redone. What the UI
 * has to get right is the *scope* of the edit, and that is what these tests pin down. While a node
 * is held its form edits the run — the request goes to the re-execution route and no pipeline
 * version is written — and only the explicit "Save to pipeline" button crosses that line.
 *
 * The other half is that two attempts must both remain visible. A re-execution that overwrote the
 * result it was meant to be compared with would look like it worked and destroy the reason for
 * doing it.
 *
 * No Loom backend is required; REST is intercepted with `page.route` and the events socket with
 * `page.routeWebSocket`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Contact Sheets";
const RUN_UUID = "run-0000-0000-0000-000000000001";
const ITEM_UUID = "item-0000-0000-0000-000000000001";

/** A thumbnail node with a bounded integer parameter — something worth changing at a breakpoint. */
const DESCRIPTORS = [
  {
    kind: "filesystem-source", name: "filesystem-source", description: "", icon: "", category: "SOURCE",
    inputPorts: [], outputPorts: [{ id: "media", contentType: "media/*", cardinality: "ONE", required: true }],
    inputGroups: [], outputGroups: [], dynamicPorts: false, parameters: [],
    defaultConcurrency: 1, defaultMode: "SEQUENTIAL", defaultBlocking: false, events: [],
  },
  {
    kind: "thumbnail", name: "thumbnail", description: "", icon: "", category: "TRANSFORM",
    inputPorts: [{ id: "media", label: "Media", contentType: "media/*", cardinality: "ONE", required: true }],
    outputPorts: [{ id: "thumbnail", contentType: "artifact/image", cardinality: "ONE", required: true }],
    inputGroups: [], outputGroups: [], dynamicPorts: false,
    parameters: [
      { key: "cols", type: "INTEGER", label: "Columns", description: "Tiles across", defaultValue: 6, min: 1, max: 20 },
      { key: "rows", type: "INTEGER", label: "Rows", description: "Tiles down", defaultValue: 1, min: 1, max: 20 },
    ],
    defaultConcurrency: 1, defaultMode: "SEQUENTIAL", defaultBlocking: false, events: [],
  },
];

const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "thumb", type: "thumbnail", label: "Thumbnail", position: { x: 260, y: 0 }, options: { cols: 6, rows: 1 }, data: {} },
  ],
  edges: [{ id: "e1", source: "src", target: "thumb", sourcePort: "media", targetPort: "media" }],
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

const RUN = {
  uuid: RUN_UUID,
  pipelineUuid: PIPELINE_UUID,
  started: "2026-07-20T10:00:00Z",
  status: "RUNNING",
  mediaCount: 1,
  successCount: 0,
  failureCount: 0,
  dryRun: false,
};

const ITEM = {
  uuid: ITEM_UUID,
  runUuid: RUN_UUID,
  itemSeq: 0,
  mediaPath: "/media/library/holiday-clip.mp4",
  state: "RUNNING",
};

/** One attempt at the thumbnail node, with the sheet path it produced. */
function task(generation: number, path: string) {
  return {
    uuid: `task-${generation}`, itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "thumb", nodeKind: "thumbnail", elementSeq: 0, generation,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 40,
    outputs: {
      thumbnail: {
        contentType: "artifact/image", cardinality: "ONE",
        elements: [{ origin: { itemId: ITEM_UUID, seq: 0, total: 1 }, value: path }],
      },
    },
  };
}

const HELD = [{ nodeId: "thumb", itemUuid: ITEM_UUID, elementSeq: 0 }];

interface Calls {
  /** Bodies posted to the re-execution route. */
  reExecutions: Array<Record<string, unknown>>;
  /** Definitions posted to the pipeline update route — i.e. new versions. */
  saved: Array<Record<string, any>>;
}

/**
 * @param tasks the executions the item reports; pass more than one generation to exercise the
 *              attempt selector
 */
async function mockBackend(page: Page, tasks: unknown[] = [task(0, "/tmp/sheet-6x1.jpg")]) {
  const calls: Calls = { reExecutions: [], saved: [] };
  let served = tasks;

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: DESCRIPTORS, contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}`, route => {
    if (route.request().method() === "POST") {
      calls.saved.push(JSON.parse(route.request().postData() || "{}"));
      route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify({ ...pipeline(), versionNumber: 2, versionUuid: "aaaaaaaa-0000-0000-0000-000000000002" }),
      });
      return;
    }
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pipeline()) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [RUN] }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [ITEM] }) })
  );
  // Registered after /items so the more specific pattern is not shadowed by it.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: served }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/breakpoints`, route => {
    if (route.request().method() === "PUT") {
      const body = JSON.parse(route.request().postData() || "{}");
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeIds: body.nodeIds ?? [], held: HELD }) });
      return;
    }
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeIds: ["thumb"], held: HELD }) });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/nodes/*/reexecutions`, route => {
    const body = JSON.parse(route.request().postData() || "{}");
    calls.reExecutions.push(body);
    // The second attempt now exists, exactly as it would once the worker reported back.
    served = [...served, task(calls.reExecutions.length, "/tmp/sheet-4x2.jpg")];
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ generation: calls.reExecutions.length, nodeId: "thumb", options: body.options ?? {} }),
    });
  });

  return { calls };
}

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
  await expect(page.getByTestId("pipeline-node-thumb")).toBeVisible({ timeout: 10_000 });
}

/**
 * Get to the state the whole feature hangs off: debug on, an item inspected, and the thumbnail
 * node held at a breakpoint with its detail sidebar open.
 *
 * The hold is delivered as a `NODE_BREAKPOINT_HELD` frame rather than clicked into place, because
 * that is how it really arrives — the run stops when the worker reports, not when anyone presses
 * anything.
 */
async function holdThumbAndOpenSidebar(page: Page, ws: WebSocketRoute) {
  pushPipelineEvent(ws, "NODE_BREAKPOINT_HELD", {
    nodeId: "thumb",
    pipelineRunUuid: RUN_UUID,
    itemUuid: ITEM_UUID,
    elementSeq: 0,
    mediaPath: ITEM.mediaPath,
  });
  await expect(page.getByTestId("pipeline-node-thumb")).toHaveAttribute("data-held", "true", { timeout: 10_000 });
  await page.getByTestId("pipeline-node-thumb").click();
  await expect(page.getByTestId("pipeline-node-held-panel")).toBeVisible({ timeout: 10_000 });
}

/** The sidebar input for one of the node's declared parameters. */
function parameterField(page: Page, key: string) {
  return page.getByTestId(`pipeline-node-param-${key}`);
}

test.describe("Pipeline node re-execution – mocked", () => {

  test("the held panel appears only for a node that is actually held", async ({ page }) => {
    // Offering Re-execute on a node the engine would refuse is worse than not offering it: the
    // operator would read the 409 as the feature being broken.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);

    await page.getByTestId("pipeline-node-src").click();
    await expect(page.getByTestId("pipeline-node-held-panel")).toHaveCount(0);

    await holdThumbAndOpenSidebar(page, ws);

    await expect(page.getByTestId("pipeline-node-held-panel")).toHaveAttribute("data-held-seq", "0");
    await expect(page.getByTestId("pipeline-node-reexecute")).toBeVisible();
  });

  test("re-executing sends the changed setting and writes no pipeline version", async ({ page }) => {
    // The property the whole design rests on. Everything typed here is run state until the
    // operator says otherwise, so experimenting can never change what everyone else runs.
    const waitForSocket = await mockEventsSocket(page);
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);
    await holdThumbAndOpenSidebar(page, ws);

    await parameterField(page, "cols").fill("4");
    await expect(page.getByTestId("pipeline-node-settings-draft")).toHaveAttribute("data-draft-keys", "cols");

    await page.getByTestId("pipeline-node-reexecute").click();

    await expect.poll(() => calls.reExecutions.length, { timeout: 10_000 }).toBe(1);
    expect(calls.reExecutions[0]).toMatchObject({ itemUuid: ITEM_UUID, elementSeq: 0, options: { cols: 4 } });
    expect(calls.saved, "a re-execution must not create a pipeline version").toHaveLength(0);
  });

  test("a drafted setting stays out of the definition an ordinary Save writes", async ({ page }) => {
    // The draft must not leak into the pipeline by the back door. Saving for an unrelated reason —
    // moving a node, wiring an edge — has to write the settings the pipeline actually has, not the
    // value someone was trying out at a breakpoint.
    const waitForSocket = await mockEventsSocket(page);
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);
    await holdThumbAndOpenSidebar(page, ws);

    await parameterField(page, "cols").fill("4");
    await expect(page.getByTestId("pipeline-node-settings-draft")).toBeVisible();

    await page.getByText("Save", { exact: true }).click();

    await expect.poll(() => calls.saved.length, { timeout: 10_000 }).toBe(1);
    const nodes = calls.saved[0].definition.nodes as any[];
    expect(nodes.find(n => n.id === "thumb").options).toMatchObject({ cols: 6 });
  });

  test("Save to pipeline writes the drafted setting into a new version", async ({ page }) => {
    // The one button here that crosses from run state into the pipeline, and the only way a value
    // tried out at a breakpoint can outlive the run.
    const waitForSocket = await mockEventsSocket(page);
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);
    await holdThumbAndOpenSidebar(page, ws);

    // Disabled until something has actually been changed — there is nothing to keep otherwise.
    await expect(page.getByTestId("pipeline-node-save-draft")).toBeDisabled();

    await parameterField(page, "cols").fill("4");
    await page.getByTestId("pipeline-node-save-draft").click();

    await expect.poll(() => calls.saved.length, { timeout: 10_000 }).toBe(1);
    const nodes = calls.saved[0].definition.nodes as any[];
    expect(nodes.find(n => n.id === "thumb").options).toMatchObject({ cols: 4, rows: 1 });
    // The draft is spent: it is now part of the pipeline and no longer an unsaved experiment.
    await expect(page.getByTestId("pipeline-node-settings-draft")).toHaveCount(0);
  });

  test("both attempts stay available and the sidebar can switch between them", async ({ page }) => {
    // Comparing before with after is the entire reason to re-execute. A run that overwrote the
    // first attempt would look like it worked while destroying the comparison.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page, [task(0, "/tmp/sheet-6x1.jpg"), task(1, "/tmp/sheet-4x2.jpg")]);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);
    await holdThumbAndOpenSidebar(page, ws);

    const selector = page.getByTestId("pipeline-node-generation");
    await expect(selector).toBeVisible({ timeout: 10_000 });
    // The latest attempt is what is shown by default: after changing a setting you want to see
    // what it did, not what it replaced.
    await expect(selector).toHaveValue("1");

    // Clicking the MUI Select means clicking its combobox; the element carrying the testid is the
    // hidden input behind it.
    await page.getByRole("combobox", { name: "Attempt" }).click();
    await page.getByRole("option", { name: "Original run" }).click();

    await expect(selector).toHaveValue("0");
  });

  test("the attempt selector is hidden until there is more than one attempt", async ({ page }) => {
    // A chooser with one option is a question with one possible answer.
    const waitForSocket = await mockEventsSocket(page);
    await mockBackend(page);
    await loginAndOpenEditor(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    const ws = await waitForSocket(0);
    await holdThumbAndOpenSidebar(page, ws);

    await expect(page.getByTestId("pipeline-node-generation")).toHaveCount(0);
  });
});
