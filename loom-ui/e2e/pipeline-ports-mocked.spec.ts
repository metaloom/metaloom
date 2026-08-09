import { test, expect, Page } from "@playwright/test";

/**
 * Typed, named ports in the pipeline editor (port refactor, phase 6).
 *
 * Covers the four things that were previously either unenforced or silently lost:
 *  - drawing a valid port-to-port connection;
 *  - refusing a type-incompatible one, with a toast that says why;
 *  - a wired XOR member disabling its siblings;
 *  - a save → reload → save round trip preserving the exact ports and the branch. That last one is
 *    the regression test for the two live defects this phase fixes: the editor wrote the filter
 *    branch as `edgeType` (nothing reads it — every PASS/REJECT reached the engine as ANY), and
 *    `toRFEdges` dropped the handles on reload, so ports were lost on the next save.
 *
 * No running Loom backend — every REST call is intercepted. Routing conventions mirror
 * `pipeline-crud-mocked.spec.ts`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";

/** A port in the shape the node-descriptor endpoint serves. */
function port(id: string, contentType: string, extra: Record<string, unknown> = {}) {
  return { id, label: id, contentType, cardinality: "ONE", required: true, ...extra };
}

/**
 * Four kinds, enough to exercise every rule:
 *  - `filesystem-source` emits the family wildcard `media/*`, as a real source does;
 *  - `whisper` is the XOR case — audio *or* video, never both;
 *  - `sentiment` takes `text/*`, which a transcript satisfies;
 *  - `thumbnail` takes media, which a transcript does not — the incompatible target.
 */
const DESCRIPTORS = [
  {
    kind: "filesystem-source", name: "File Source", category: "SOURCE",
    inputPorts: [],
    outputPorts: [port("media", "media/*")],
    inputGroups: [], outputGroups: [],
  },
  {
    kind: "whisper", name: "Whisper", category: "ANALYSIS",
    inputPorts: [
      port("audio", "media/audio", { group: "media_alt" }),
      port("video", "media/video", { group: "media_alt" }),
    ],
    outputPorts: [port("transcript", "text/transcript")],
    inputGroups: [{ id: "media_alt", mode: "XOR", required: true, label: "Media" }],
    outputGroups: [],
  },
  {
    kind: "sentiment", name: "Sentiment", category: "ANALYSIS",
    inputPorts: [port("text", "text/*")],
    outputPorts: [port("label", "scalar/string")],
    inputGroups: [], outputGroups: [],
  },
  {
    kind: "thumbnail", name: "Thumbnail", category: "TRANSFORM",
    inputPorts: [port("media", "media/*")],
    outputPorts: [port("thumbnail", "artifact/image")],
    inputGroups: [], outputGroups: [],
  },
  {
    // Dynamic ports: the descriptor declares none, the `outputs` option decides them.
    kind: "script", name: "Script", category: "TRANSFORM",
    inputPorts: [port("media", "media/*", { required: false })],
    outputPorts: [],
    inputGroups: [], outputGroups: [], dynamicPorts: true,
  },
  {
    // Dynamic ports again, but the ports *are* the branches: one per configured bucket, edited
    // through the PORT_LIST widget rather than a JSON blob.
    kind: "filter", name: "Filter", category: "FILTER",
    inputPorts: [port("media", "media/*"), port("text", "text/*", { required: false })],
    outputPorts: [],
    inputGroups: [], outputGroups: [], dynamicPorts: true,
    parameters: [
      { key: "buckets", type: "PORT_LIST", label: "Buckets", description: "One output port per bucket", defaultValue: [] },
    ],
  },
].map(d => ({
  description: "", icon: "", dynamicPorts: false, parameters: [],
  defaultConcurrency: 1, defaultMode: "PARALLEL", defaultBlocking: false, events: [],
  ...d,
}));

/** The served vocabulary — the editor's only source of port labels and descriptions. */
const CONTENT_TYPES = [
  { id: "media/*", label: "Any Media", family: "media", wildcard: true },
  { id: "media/audio", label: "Audio", family: "media", wildcard: false },
  { id: "media/video", label: "Video", family: "media", wildcard: false },
  { id: "text/*", label: "Any Text", family: "text", wildcard: false },
  { id: "text/transcript", label: "Transcript", family: "text", wildcard: false, description: "Recognised speech with per-segment times" },
  { id: "scalar/string", label: "String", family: "scalar", wildcard: false },
  { id: "artifact/image", label: "Image Artifact", family: "artifact", wildcard: false },
];

/** Four unconnected nodes — the connection tests draw the edges themselves. */
const UNWIRED = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 } },
    { id: "whisper", type: "whisper", label: "Whisper", position: { x: 300, y: 0 } },
    { id: "sentiment", type: "sentiment", label: "Sentiment", position: { x: 600, y: 0 } },
    { id: "thumb", type: "thumbnail", label: "Thumbnail", position: { x: 300, y: 220 } },
  ],
  edges: [],
};

/** A fully wired graph whose edges name their ports and carry a non-default branch. */
const WIRED = {
  nodes: UNWIRED.nodes.filter(n => n.id !== "thumb"),
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "whisper", targetPort: "video", branch: "ANY" },
    { id: "e2", source: "whisper", sourcePort: "transcript", target: "sentiment", targetPort: "text", branch: "PASS" },
  ],
};

/** A `script` node whose handles come from its `outputs` option, not from its descriptor. */
const SCRIPT_GRAPH = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 } },
    {
      id: "script", type: "script", label: "Script", position: { x: 300, y: 0 },
      options: { outputs: [{ key: "paragraphs", type: "TEXT_LIST" }, { key: "summary", type: "TEXT" }] },
    },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "script", targetPort: "media", branch: "ANY" },
  ],
};

/**
 * A `filter` whose two buckets are already wired downstream, so removing one has an edge to take
 * with it.
 */
const FILTER_GRAPH = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 } },
    {
      id: "flt", type: "filter", label: "Filter", position: { x: 300, y: 0 },
      options: { buckets: [{ id: "de", label: "German" }, { id: "en", label: "English" }] },
    },
    // The English branch feeds a `script`, whose media input is optional — so removing that bucket
    // prunes the edge without also stranding a required input, which would refuse the save for an
    // unrelated reason and hide what this fixture is here to show.
    { id: "sink", type: "script", label: "Script", position: { x: 600, y: 0 } },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "flt", targetPort: "media", branch: "ANY" },
    { id: "e2", source: "flt", sourcePort: "en", target: "sink", targetPort: "media", branch: "ANY" },
  ],
};

function pipelineResponse(uuid: string, name: string, definition: unknown, version = 1) {
  return {
    uuid,
    versionUuid: `${uuid}-v${version}`,
    versionNumber: version,
    name,
    description: "Mocked pipeline",
    definition,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

interface MockState {
  pipelines: ReturnType<typeof pipelineResponse>[];
  saved: any[];
}

async function mockBackend(page: Page, definition: unknown): Promise<MockState> {
  const state: MockState = {
    pipelines: [pipelineResponse(PIPELINE_UUID, "Ports", definition)],
    saved: [],
  };

  // Least-specific → most-specific (Playwright resolves newest-registered first).
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ nodeDescriptors: DESCRIPTORS, contentTypes: CONTENT_TYPES }),
    })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(CONTENT_TYPES) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ data: state.pipelines, _metainfo: { totalCount: state.pipelines.length } }),
    })
  );
  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route(/\/api\/v1\/pipelines\/([^/]+)\/versions$/, route => {
    const uuid = route.request().url().match(/pipelines\/([^/]+)\/versions/)![1];
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ data: p ? [p] : [], _metainfo: { totalCount: p ? 1 : 0 } }),
    });
  });

  // GET single + POST update (save).
  await page.route(/\/api\/v1\/pipelines\/[^/]+$/, route => {
    const req = route.request();
    const uuid = req.url().match(/pipelines\/([^/?]+)/)![1];
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      state.saved.push(body);
      const idx = state.pipelines.findIndex(p => p.uuid === uuid);
      const nextVersion = (state.pipelines[idx]?.versionNumber ?? 1) + 1;
      const updated = pipelineResponse(uuid, body.name, body.definition, nextVersion);
      if (idx >= 0) state.pipelines[idx] = updated;
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(updated) });
      return;
    }
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({ status: p ? 200 : 404, contentType: "application/json", body: JSON.stringify(p ?? {}) });
  });

  // POST /pipelines/validate — the server owns the graph rules now. Registered LAST because the
  // most recent matching handler wins, and the /pipelines/:uuid route above also matches this URL:
  // without this the validation request is counted as a save.
  await page.route("**/api/v1/pipelines/validate", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ valid: true, errors: [], warnings: [] }),
    })
  );

  return state;
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}

/**
 * Drag from one named handle to another. React Flow tracks the connection on document-level
 * mouse events, so a plain down/move/up over the two handle elements is enough.
 */
async function connectPorts(page: Page, from: string, to: string) {
  const source = page.getByTestId(from);
  const target = page.getByTestId(to);
  const a = await source.boundingBox();
  const b = await target.boundingBox();
  expect(a, `source handle ${from} is not on the canvas`).toBeTruthy();
  expect(b, `target handle ${to} is not on the canvas`).toBeTruthy();
  await page.mouse.move(a!.x + a!.width / 2, a!.y + a!.height / 2);
  await page.mouse.down();
  await page.mouse.move(b!.x + b!.width / 2, b!.y + b!.height / 2, { steps: 12 });
  await page.mouse.up();
}

test.describe("Pipeline typed ports – mocked", () => {

  test("a node renders one handle per declared port, named and typed", async ({ page }) => {
    await mockBackend(page, UNWIRED);
    await login(page);

    // Handle ids are port ids, not positional in_0/out_1.
    const audio = page.getByTestId("port-in-whisper-audio");
    await expect(audio).toBeVisible({ timeout: 10_000 });
    await expect(audio).toHaveAttribute("data-content-type", "media/audio");
    await expect(audio).toHaveAttribute("data-cardinality", "ONE");
    await expect(page.getByTestId("port-in-whisper-video")).toHaveAttribute("data-content-type", "media/video");
    await expect(page.getByTestId("port-out-whisper-transcript")).toHaveAttribute("data-content-type", "text/transcript");
    // The tooltip carries the type id, the served label and the cardinality.
    await expect(page.getByTestId("port-out-whisper-transcript")).toHaveAttribute("title", /text\/transcript.*Transcript[\s\S]*ONE/);
    // A source declares no inputs at all.
    await expect(page.getByTestId("port-out-src-media")).toBeVisible();
    await expect(page.locator('[data-testid^="port-in-src-"]')).toHaveCount(0);
  });

  test("drawing a valid port-to-port connection adds an edge", async ({ page }) => {
    await mockBackend(page, UNWIRED);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(4, { timeout: 10_000 });
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(0);

    // media/* into media/audio — the producer-wildcard arm of the lattice.
    await connectPorts(page, "port-out-src-media", "port-in-whisper-audio");
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(1, { timeout: 5_000 });
    await expect(page.getByTestId("pipeline-connection-error")).toHaveCount(0);
  });

  test("an incompatible connection is refused with a toast that says why", async ({ page }) => {
    await mockBackend(page, UNWIRED);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(4, { timeout: 10_000 });

    // text/transcript into media/* — assignability never crosses families.
    await connectPorts(page, "port-out-whisper-transcript", "port-in-thumb-media");

    const toast = page.getByTestId("pipeline-connection-error");
    await expect(toast).toBeVisible({ timeout: 5_000 });
    await expect(toast).toContainText("text/transcript");
    await expect(toast).toContainText("media/*");
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(0);
  });

  test("wiring one XOR alternative disables its siblings", async ({ page }) => {
    await mockBackend(page, UNWIRED);
    await login(page);

    const video = page.getByTestId("port-in-whisper-video");
    await expect(video).toHaveAttribute("data-port-blocked", "false", { timeout: 10_000 });

    await connectPorts(page, "port-out-src-media", "port-in-whisper-audio");
    await expect(page.getByTestId("pipeline-canvas").locator(".react-flow__edge")).toHaveCount(1, { timeout: 5_000 });

    // whisper takes audio *or* video — the sibling greys out and explains itself.
    await expect(video).toHaveAttribute("data-port-blocked", "true", { timeout: 5_000 });
    await expect(video).toHaveAttribute("title", /accepts exactly one alternative/);
    // The wired alternative itself stays live.
    await expect(page.getByTestId("port-in-whisper-audio")).toHaveAttribute("data-port-blocked", "false");

    // Drawing the second alternative anyway is refused, naming both alternatives.
    await connectPorts(page, "port-out-src-media", "port-in-whisper-video");
    await expect(page.getByTestId("pipeline-canvas").locator(".react-flow__edge")).toHaveCount(1);
  });

  test("a script's handles come from its outputs option, and that option survives a save", async ({ page }) => {
    const state = await mockBackend(page, SCRIPT_GRAPH);
    await login(page);

    // TEXT_LIST fans out — it must not collapse onto the same handle shape as TEXT.
    const paragraphs = page.getByTestId("port-out-script-paragraphs");
    await expect(paragraphs).toBeVisible({ timeout: 10_000 });
    await expect(paragraphs).toHaveAttribute("data-content-type", "text/plain");
    await expect(paragraphs).toHaveAttribute("data-cardinality", "MANY");
    const summary = page.getByTestId("port-out-script-summary");
    await expect(summary).toHaveAttribute("data-content-type", "text/plain");
    await expect(summary).toHaveAttribute("data-cardinality", "ONE");

    // The `outputs` option and the port list are different things living in the same node data —
    // saving must round-trip the option rather than mistake it for editor state.
    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBe(1);
    const saved = state.saved[0].definition.nodes.find((n: any) => n.id === "script");
    expect(saved.options.outputs).toEqual([
      { key: "paragraphs", type: "TEXT_LIST" },
      { key: "summary", type: "TEXT" },
    ]);
  });

  test("a filter's handles are its buckets, and 'other' is always there", async ({ page }) => {
    await mockBackend(page, FILTER_GRAPH);
    await login(page);

    await expect(page.getByTestId("port-out-flt-de")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("port-out-flt-en")).toBeVisible();
    // The catch-all exists whatever the configuration, so the graph is never a dead end.
    await expect(page.getByTestId("port-out-flt-other")).toBeVisible();

    // A bucket port is a branch: it carries the items routed to it and nothing else.
    await expect(page.getByTestId("port-out-flt-de")).toHaveAttribute("data-port-selective", "true");
    await expect(page.getByTestId("port-out-flt-other")).toHaveAttribute("data-port-selective", "true");
    // The decision ports fire for every item, so a node wired to them must never be skipped.
    await expect(page.getByTestId("port-out-flt-passed")).toHaveAttribute("data-port-selective", "false");
    await expect(page.getByTestId("port-out-flt-bucket")).toHaveAttribute("data-port-selective", "false");
  });

  test("adding a bucket grows a handle and removing one takes its edge with it", async ({ page }) => {
    const state = await mockBackend(page, FILTER_GRAPH);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(2, { timeout: 10_000 });

    await page.getByTestId("pipeline-node-flt").click();
    await expect(page.getByTestId("bucket-list-editor")).toBeVisible();

    // Add: a third row, and the handle appears without a save or a reload.
    await page.getByTestId("bucket-add").click();
    await page.getByTestId("bucket-id-2").fill("Brazilian Portuguese");
    // The id is slugified as it is typed, so what the author types is always a legal port id.
    await expect(page.getByTestId("port-out-flt-brazilian_portuguese")).toBeVisible({ timeout: 10_000 });

    // Remove the bucket that has an edge hanging off it. Both the handle and the edge must go —
    // leaving the edge would be invisible until save, where it surfaces as an unknown-port error
    // the author then has to hunt down by hand.
    await page.getByTestId("bucket-remove-1").click();
    await expect(page.getByTestId("port-out-flt-en")).toHaveCount(0);
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(1);

    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBe(1);

    const saved = state.saved[0].definition;
    const filter = saved.nodes.find((n: any) => n.id === "flt");
    expect(filter.options.buckets.map((b: any) => b.id)).toEqual(["de", "brazilian_portuguese"]);
    expect(saved.edges.some((e: any) => e.sourcePort === "en")).toBe(false);
  });

  test("save → reload → save preserves the exact ports and the branch", async ({ page }) => {
    const state = await mockBackend(page, WIRED);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(2, { timeout: 10_000 });

    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBe(1);

    const first = state.saved[0].definition.edges;
    // Ports are persisted as sourcePort/targetPort — not sourceHandle/targetHandle, which the
    // Loom parser never read — and the filter routing as `branch`, not `edgeType`.
    expect(first).toEqual([
      { id: "e1", source: "src", sourcePort: "media", target: "whisper", targetPort: "video", branch: "ANY" },
      { id: "e2", source: "whisper", sourcePort: "transcript", target: "sentiment", targetPort: "text", branch: "PASS" },
    ]);

    // Reload: the session is in-memory, so log in again. The server now serves what we saved.
    await page.reload();
    await login(page);
    await expect(canvas.locator(".react-flow__edge")).toHaveCount(2, { timeout: 10_000 });

    // Saving again must reproduce byte-identical edges. Before the handles round-tripped, this
    // second save came back with the ports gone and every branch reset to ANY.
    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBe(2);
    expect(state.saved[1].definition.edges).toEqual(first);
  });
});
