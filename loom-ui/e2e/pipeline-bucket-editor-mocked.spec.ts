import { test, expect, Page } from "@playwright/test";

/**
 * The PORT_LIST bucket editor (BucketListEditor) on a `filter` node: editing a row's label and
 * aliases reaches the saved definition, and a row that cannot become a port says why.
 *
 * Mocks mirror `pipeline-ports-mocked.spec.ts`; every REST call is intercepted.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";

function port(id: string, contentType: string, extra: Record<string, unknown> = {}) {
  return { id, label: id, contentType, cardinality: "ONE", required: true, ...extra };
}

const DESCRIPTORS = [
  {
    kind: "filesystem-source", name: "File Source", category: "SOURCE",
    inputPorts: [],
    outputPorts: [port("media", "media/*")],
    inputGroups: [], outputGroups: [],
  },
  {
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

const CONTENT_TYPES = [
  { id: "media/*", label: "Any Media", family: "media", wildcard: true },
  { id: "text/*", label: "Any Text", family: "text", wildcard: false },
];

const FILTER_GRAPH = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 } },
    {
      id: "flt", type: "filter", label: "Filter", position: { x: 300, y: 0 },
      options: { buckets: [{ id: "de", label: "German" }, { id: "en", label: "English", match: "english" }] },
    },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "flt", targetPort: "media", branch: "ANY" },
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
  // A session now survives a reload (LOOM_UI.md §7.1), so a test that reloads mid-way reaches
  // here already signed in and there is no form to fill. Idempotent rather than removed from
  // those call sites: "make sure we are signed in" is what every caller meant all along.
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}


async function openFilter(page: Page) {
  await page.getByTestId("pipeline-node-flt").click();
  await expect(page.getByTestId("bucket-list-editor")).toBeVisible({ timeout: 10_000 });
}

/** The helper line under a row's id field. */
function idHelper(page: Page, index: number) {
  return page.getByTestId(`bucket-row-${index}`).locator(".MuiFormHelperText-root").first();
}

test.describe("Pipeline bucket editor – mocked", () => {

  test("editing a bucket's label and aliases is what the save sends", async ({ page }) => {
    const state = await mockBackend(page, FILTER_GRAPH);
    await login(page);
    await openFilter(page);

    await expect(page.getByTestId("bucket-label-0")).toHaveValue("German");
    await expect(page.getByTestId("bucket-match-1")).toHaveValue("english");

    await page.getByTestId("bucket-label-0").fill("Deutsch");
    await page.getByTestId("bucket-match-0").fill("german, deutsch");
    await page.getByTestId("bucket-match-1").fill("");

    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBe(1);

    const filter = state.saved[0].definition.nodes.find((n: any) => n.id === "flt");
    expect(filter.options.buckets).toEqual([
      { id: "de", label: "Deutsch", match: "german, deutsch" },
      { id: "en", label: "English", match: "" },
    ]);
    // The label is not the port id: the handle is still keyed by the unchanged id.
    await expect(page.getByTestId("port-out-flt-de")).toBeVisible();
  });

  test("an empty id says it is required and produces no port", async ({ page }) => {
    await mockBackend(page, FILTER_GRAPH);
    await login(page);
    await openFilter(page);

    await page.getByTestId("bucket-add").click();
    await expect(idHelper(page, 2)).toHaveText("An id is required");
    await expect(idHelper(page, 2)).toHaveClass(/Mui-error/);
    // The valid rows carry no complaint.
    await expect(idHelper(page, 0)).not.toHaveClass(/Mui-error/);
    await expect(idHelper(page, 0)).not.toContainText("required");
  });

  test("a reserved id is flagged and does not shadow the node's own port", async ({ page }) => {
    await mockBackend(page, FILTER_GRAPH);
    await login(page);
    await openFilter(page);

    await page.getByTestId("bucket-add").click();
    await page.getByTestId("bucket-id-2").fill("other");
    await expect(idHelper(page, 2)).toHaveText("That id is one of the node's reserved ports");
    // Exactly one 'other' handle: the built-in catch-all, not a second one from the row.
    await expect(page.getByTestId("port-out-flt-other")).toHaveCount(1);
  });

  test("a duplicate id is flagged on the second row and clears once renamed", async ({ page }) => {
    await mockBackend(page, FILTER_GRAPH);
    await login(page);
    await openFilter(page);

    await page.getByTestId("bucket-add").click();
    await page.getByTestId("bucket-id-2").fill("de");
    // Both rows sharing the id are flagged: each sees the other.
    await expect(idHelper(page, 2)).toHaveText("Another bucket already uses this id");
    await expect(idHelper(page, 0)).toHaveText("Another bucket already uses this id");

    await page.getByTestId("bucket-id-2").fill("fr");
    await expect(idHelper(page, 2)).not.toHaveClass(/Mui-error/);
    await expect(idHelper(page, 0)).not.toHaveClass(/Mui-error/);
    await expect(page.getByTestId("port-out-flt-fr")).toBeVisible();
  });

  test("the Delete key inside a sidebar field edits the field, it does not offer to delete the node", async ({ page }) => {
    await mockBackend(page, FILTER_GRAPH);
    await login(page);
    await openFilter(page);

    const label = page.getByTestId("bucket-label-0");
    await expect(label).toHaveValue("German");
    await label.focus();
    await label.press("Home");
    await label.press("Delete");

    await expect(label).toHaveValue("erman");
    await expect(page.getByRole("dialog", { name: "Delete Node" })).toHaveCount(0);
  });

  test("the Delete key with the node selected on the canvas still offers to delete it", async ({ page }) => {
    await mockBackend(page, FILTER_GRAPH);
    await login(page);
    await openFilter(page);

    // Focus is on the canvas node, not in a field.
    await page.getByTestId("pipeline-node-flt").click();
    await page.keyboard.press("Delete");

    const dialog = page.getByRole("dialog", { name: "Delete Node" });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("Filter");
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByTestId("pipeline-node-flt")).toBeVisible();
  });
});
