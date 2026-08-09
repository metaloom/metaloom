import { test, expect, Page } from "@playwright/test";

/**
 * The palette must reflect the fleet, and the canvas must not.
 *
 * A custom node's contract is durable while the worker offering it is not, so the editor has to say
 * two different things at once: this node exists and can be authored, and it cannot run right now.
 * The three behaviours that get this wrong in practice:
 *
 *  - **Ordering vs. the highlight.** The add-node search bar renders its list in one place and reads
 *    it again in its keyboard handler. Those used to be separate expressions sharing one index, so
 *    the first ordering change would make Enter add a different node than the highlighted one.
 *  - **The toggle reaching the canvas.** Hiding an offline node from the *picker* is helpful. Hiding
 *    it from the *canvas* silently redraws a saved graph as disconnected boxes, because a node with
 *    no descriptor gets no ports and every attached edge is dropped.
 *  - **A missing availability block.** The checked-in descriptor snapshot has none; treating that as
 *    "unavailable" empties the palette with no explanation.
 *
 * No running Loom backend — every REST call is intercepted.
 */

const PIPELINE_UUID = "22222222-2222-2222-2222-222222222222";

function port(id: string, contentType: string, extra: Record<string, unknown> = {}) {
  return { id, label: id, contentType, cardinality: "ONE", required: true, ...extra };
}

/**
 * Three nodes: an online source, an online analysis node, and a custom node whose worker is down.
 * `acme-nsfw` sorts before `whisper` alphabetically and is listed second by the server, so if
 * availability ordering is not applied it stays where the server put it — which is what makes the
 * ordering assertions meaningful rather than accidental.
 */
const DESCRIPTORS = [
  {
    nodeId: "filesystem-source", kind: "filesystem-source", name: "File Source", category: "SOURCE",
    inputPorts: [], outputPorts: [port("media", "media/*")],
  },
  {
    nodeId: "acme-nsfw", kind: "acme-nsfw", name: "NSFW Classifier", category: "ANALYSIS",
    version: "1.0.0-SNAPSHOT",
    inputPorts: [port("media", "media/image")],
    outputPorts: [port("result", "struct/nsfw")],
  },
  {
    nodeId: "whisper", kind: "whisper", name: "Whisper", category: "ANALYSIS",
    inputPorts: [port("media", "media/*")],
    outputPorts: [port("transcript", "text/transcript")],
  },
].map(d => ({
  description: "A node", icon: "", dynamicPorts: false, parameters: [],
  inputGroups: [], outputGroups: [],
  defaultConcurrency: 1, defaultMode: "PARALLEL", defaultBlocking: false, events: [],
  ...d,
}));

const CONTENT_TYPES = [
  { id: "media/*", label: "Any Media", family: "media", wildcard: true },
  { id: "media/image", label: "Image", family: "media", wildcard: false },
  { id: "struct/nsfw", label: "Nsfw", family: "struct", wildcard: false },
  { id: "text/transcript", label: "Transcript", family: "text", wildcard: false },
];

/** `acme-nsfw` has a contract but no online worker. */
const AVAILABILITY = {
  "filesystem-source": { source: "BUILTIN", available: true, providedBy: ["cortex-1"] },
  whisper: { source: "BUILTIN", available: true, providedBy: ["cortex-1"] },
  "acme-nsfw": {
    source: "ANNOUNCED", available: false, providedBy: ["cortex-gpu-01"],
    lastSeen: "2026-07-29T22:41:10Z",
  },
};

/** A saved graph that already uses the offline node, wired on both sides. */
const GRAPH_USING_OFFLINE_NODE = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 } },
    { id: "nsfw", type: "acme-nsfw", label: "NSFW", position: { x: 300, y: 0 } },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "nsfw", targetPort: "media", branch: "ANY" },
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

/**
 * @param availability fleet state, or `null` for "the server sent no availability block at all".
 *   Deliberately `null` rather than `undefined`: passing `undefined` explicitly would trigger the
 *   default parameter and quietly hand back the full map, which is exactly the bug this argument is
 *   here to test for.
 */
async function mockBackend(page: Page, definition: unknown, availability: unknown = AVAILABILITY) {
  const pipelines = [pipelineResponse(PIPELINE_UUID, "Availability", definition)];

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({
        nodeDescriptors: DESCRIPTORS,
        contentTypes: CONTENT_TYPES,
        // Null here is the offline-snapshot case, and must read as "everything available".
        ...(availability === null ? {} : { availability }),
      }),
    })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(CONTENT_TYPES) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ data: pipelines, _metainfo: { totalCount: pipelines.length } }),
    })
  );
  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route(/\/api\/v1\/pipelines\/([^/]+)\/versions$/, route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ data: pipelines, _metainfo: { totalCount: 1 } }),
    })
  );
  await page.route(/\/api\/v1\/pipelines\/[^/]+$/, route => {
    const p = pipelines[0];
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(p) });
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
 * The node rows of the add-node dropdown, in the order they are rendered.
 *
 * Scoped to the list so it cannot also pick up the offline toggle - which is why the toggle carries
 * a different testid prefix rather than a longer `add-node-` one.
 */
async function pickerOrder(page: Page): Promise<string[]> {
  return page.locator("[data-testid^='add-node-']").evaluateAll(nodes =>
    nodes.map(n => n.getAttribute("data-testid")!.replace("add-node-", "")));
}

/** Open the always-visible add-node search bar's dropdown. */
async function openAddNode(page: Page) {
  await page.getByPlaceholder(/add node/i).click();
  await expect(page.getByTestId("add-node-whisper")).toBeVisible({ timeout: 5_000 });
}

test.describe("node availability in the pipeline editor", () => {

  test("an offline node is listed last, dimmed, and says why", async ({ page }) => {
    await mockBackend(page, GRAPH_USING_OFFLINE_NODE);
    await login(page);
    await openAddNode(page);

    const ids = await pickerOrder(page);

    // The server listed acme-nsfw second; availability ordering moves it to the end.
    expect(ids[ids.length - 1]).toBe("acme-nsfw");
    expect(ids).toContain("whisper");

    const offline = page.getByTestId("add-node-acme-nsfw");
    await expect(offline).toHaveAttribute("data-available", "false");
    // The caption has to explain itself, not just look faded.
    await expect(offline).toContainText(/offline|no worker/i);
  });

  test("the toggle hides offline nodes and reports how many", async ({ page }) => {
    await mockBackend(page, GRAPH_USING_OFFLINE_NODE);
    await login(page);
    await openAddNode(page);

    await expect(page.getByTestId("add-node-acme-nsfw")).toBeVisible();

    await page.getByTestId("offline-toggle-searchbar").click();

    await expect(page.getByTestId("add-node-acme-nsfw")).toHaveCount(0);
    await expect(page.getByTestId("add-node-whisper")).toBeVisible();
    // Hiding entries without saying so turns "my node is missing" into a support question.
    await expect(page.locator("text=/1 offline node hidden/i")).toBeVisible();
  });

  test("Enter adds the node the highlight is on, with offline entries reordered", async ({ page }) => {
    await mockBackend(page, { nodes: [], edges: [] });
    await login(page);
    await openAddNode(page);

    // Down once from the top of the reordered list: whichever node that is, Enter must add *it*.
    const ids = await pickerOrder(page);
    const expected = ids[1];

    await page.getByPlaceholder(/add node/i).press("ArrowDown");
    await page.getByPlaceholder(/add node/i).press("Enter");

    // The node that landed on the canvas must be the highlighted one. Before the picker shared a
    // single ordered list, this is exactly where it would silently add a different node.
    await expect(page.getByTestId("pipeline-canvas")).toContainText(
      new RegExp(DESCRIPTORS.find(d => d.nodeId === expected)!.name, "i"),
      { timeout: 5_000 },
    );
  });

  test("a canvas node stays fully wired while its provider is offline", async ({ page }) => {
    await mockBackend(page, GRAPH_USING_OFFLINE_NODE);
    await login(page);

    // The offline node is on the canvas and keeps its ports - so its edge survives. Filtering the
    // canvas by availability would drop the handles and silently disconnect a saved graph.
    await expect(page.getByTestId("pipeline-canvas")).toContainText(/NSFW/i);
    await expect(page.locator(".react-flow__edge")).toHaveCount(1);
  });

  test("hiding offline nodes in the picker never touches the canvas", async ({ page }) => {
    await mockBackend(page, GRAPH_USING_OFFLINE_NODE);
    await login(page);
    await openAddNode(page);

    await page.getByTestId("offline-toggle-searchbar").click();
    await expect(page.getByTestId("add-node-acme-nsfw")).toHaveCount(0);

    // Still on the canvas, still wired.
    await expect(page.getByTestId("pipeline-canvas")).toContainText(/NSFW/i);
    await expect(page.locator(".react-flow__edge")).toHaveCount(1);
  });

  test("a response with no availability block leaves every node usable", async ({ page }) => {
    // What the checked-in node-descriptors.json snapshot looks like: no fleet, everything authorable.
    await mockBackend(page, { nodes: [], edges: [] }, null);
    await login(page);
    await openAddNode(page);

    for (const id of ["filesystem-source", "acme-nsfw", "whisper"]) {
      await expect(page.getByTestId(`add-node-${id}`)).toHaveAttribute("data-available", "true");
    }
  });
});
