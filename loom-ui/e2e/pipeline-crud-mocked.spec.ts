import { test, expect, Page } from "@playwright/test";

/**
 * Smoke tests for the pipeline create / clone / delete flow (Task 8).
 *
 * These do NOT require a running Loom backend — every REST call is intercepted
 * via Playwright's route API. A mutable `state.pipelines` array models the
 * server so successive create/delete calls are reflected in the list endpoint.
 *
 * See `pipeline-versions-mocked.spec.ts` for the routing conventions this reuses.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";

/** The seeded pipeline's definition — 3 nodes, so clone/selection is visible. */
const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
    { id: "thumb", type: "thumbnail", label: "Thumbnail", position: { x: 260, y: 160 }, data: {} },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "sha512", targetPort: "media", branch: "ANY" },
    { id: "e2", source: "src", sourcePort: "media", target: "thumb", targetPort: "media", branch: "ANY" },
  ],
};

/**
 * Descriptors for the node kinds above — the canvas needs them to draw ports, and
 * `isValidConnection` needs them to decide whether a wire may be dropped.
 *
 * They no longer gate the save: the graph rules moved to `POST /pipelines/validate`, mocked below.
 */
const DESCRIPTORS = [
  { kind: "filesystem-source", out: { id: "media", contentType: "media/*" } },
  {
    kind: "sha512",
    out: { id: "hash", contentType: "hash/sha512" },
    // The one declared parameter in this fixture. The unsaved-changes test needs an edit a user
    // could plausibly spend time on, rather than a node drag. It hangs off `sha512` because that
    // node is laid out clear of the minimap, which swallows clicks on the lower-right `thumbnail`.
    parameters: [{ key: "chunkSize", type: "INTEGER", defaultValue: 8192, label: "Chunk Size", description: "Bytes read per hash update" }],
  },
  { kind: "thumbnail", out: { id: "thumbnail", contentType: "artifact/image" } },
].map(({ kind, out, parameters }: { kind: string; out: Record<string, string>; parameters?: unknown[] }) => ({
  kind,
  name: kind,
  description: "",
  icon: "",
  category: "TRANSFORM",
  inputPorts: kind === "filesystem-source"
    ? []
    : [{ id: "media", label: "Media", contentType: "media/*", cardinality: "ONE", required: true }],
  outputPorts: [{ ...out, cardinality: "ONE", required: true }],
  inputGroups: [],
  outputGroups: [],
  dynamicPorts: false,
  parameters: parameters ?? [],
  defaultConcurrency: 1,
  defaultMode: "SEQUENTIAL",
  defaultBlocking: false,
  events: [],
}));

function pipelineResponse(uuid: string, name: string, definition: unknown, extra: Record<string, unknown> = {}) {
  return {
    uuid,
    versionUuid: `${uuid}-v1`,
    versionNumber: 1,
    name,
    description: "Mocked pipeline",
    definition,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
    ...extra,
  };
}

interface MockState {
  pipelines: ReturnType<typeof pipelineResponse>[];
  created: any[];
  deleted: string[];
  seq: number;
  /**
   * The verdict `POST /pipelines/validate` answers with. Null means "valid" — the server is the
   * authority on the graph rules now, so a test that wants a save blocked sets this rather than
   * drawing a graph the editor would refuse on its own.
   */
  validationErrors: { code: string; message: string; nodeId?: string }[] | null;
}

/**
 * Install all routes. `state` is mutated by the create/delete handlers.
 *
 * Playwright resolves the **most recently registered** matching handler first,
 * so these are ordered least-specific → most-specific. The list glob and the
 * single-pipeline / runs / versions regexes are mutually exclusive by URL.
 */
async function mockBackend(page: Page): Promise<MockState> {
  const state: MockState = {
    pipelines: [pipelineResponse(PIPELINE_UUID, "Quick Hash", DEFINITION)],
    created: [],
    deleted: [],
    seq: 0,
    validationErrors: null,
  };

  // Catch-all for anything else the shell loads (spaces, users, …).
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: DESCRIPTORS, contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) })
  );

  // GET /pipelines (list) + POST /pipelines (create/clone).
  await page.route("**/api/v1/pipelines", route => {
    const req = route.request();
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      state.created.push(body);
      state.seq += 1;
      const uuid = `22222222-2222-2222-2222-00000000000${state.seq}`;
      const created = pipelineResponse(uuid, body.name, body.definition ?? { nodes: [], edges: [] }, {
        description: body.description ?? "",
        enabled: body.enabled ?? true,
        priority: body.priority ?? 0,
        dryRun: body.dryRun ?? false,
      });
      state.pipelines.push(created);
      route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
      return;
    }
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: state.pipelines, _metainfo: { totalCount: state.pipelines.length } }),
    });
  });

  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route(/\/api\/v1\/pipelines\/([^/]+)\/versions$/, route => {
    const uuid = route.request().url().match(/pipelines\/([^/]+)\/versions/)![1];
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: p ? [p] : [], _metainfo: { totalCount: p ? 1 : 0 } }),
    });
  });

  // GET /pipelines/:uuid (single) + DELETE /pipelines/:uuid.
  await page.route(/\/api\/v1\/pipelines\/[^/]+$/, route => {
    const req = route.request();
    const uuid = req.url().match(/pipelines\/([^/?]+)/)![1];
    if (req.method() === "DELETE") {
      state.deleted.push(uuid);
      state.pipelines = state.pipelines.filter(p => p.uuid !== uuid);
      route.fulfill({ status: 204, body: "" });
      return;
    }
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({ status: p ? 200 : 404, contentType: "application/json", body: JSON.stringify(p ?? {}) });
  });

  // POST /pipelines/validate — the graph rules, which live on the server now. Registered LAST
  // because the most recent matching handler wins: the /pipelines/:uuid regex above also matches
  // this URL and would otherwise read "validate" as a pipeline uuid. The server route table has
  // the same hazard, resolved the other way round (literal before wildcard).
  await page.route("**/api/v1/pipelines/validate", route => {
    const errors = state.validationErrors ?? [];
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ valid: errors.length === 0, errors, warnings: [] }),
    });
  });

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

test.describe("Pipeline create / clone / delete – mocked", () => {

  test("creating a pipeline adds it to the list and selects an empty canvas", async ({ page }) => {
    await mockBackend(page);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    await page.getByTestId("pipeline-create-button").click();
    await expect(page.getByTestId("pipeline-create-dialog")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-create-name").locator("input").fill("Fresh Pipeline");
    await page.getByTestId("pipeline-create-confirm").click();

    // Appears in the sidebar (as a selectable list button) …
    await expect(page.getByRole("button", { name: "Fresh Pipeline" })).toBeVisible({ timeout: 10_000 });
    // … is selected and starts from an empty canvas.
    await expect(canvas.locator(".react-flow__node")).toHaveCount(0, { timeout: 10_000 });
  });

  test("cloning copies the selected pipeline's definition", async ({ page }) => {
    const state = await mockBackend(page);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    await page.getByTestId("pipeline-clone-button").click();
    await expect(page.getByTestId("pipeline-create-dialog")).toBeVisible({ timeout: 5_000 });
    // Name is pre-seeded with "(copy)".
    await expect(page.getByTestId("pipeline-create-name").locator("input")).toHaveValue("Quick Hash (copy)");
    await page.getByTestId("pipeline-create-confirm").click();

    // The clone appears and is selected with the same 3-node graph.
    await expect(page.getByRole("button", { name: "Quick Hash (copy)" })).toBeVisible({ timeout: 10_000 });
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    // The POST body carried the source definition verbatim.
    await expect.poll(() => state.created.length).toBe(1);
    const posted = state.created[0];
    expect(posted.name).toBe("Quick Hash (copy)");
    expect(posted.definition.nodes.map((n: any) => n.id)).toEqual(["src", "sha512", "thumb"]);
    expect(posted.definition.edges.map((e: any) => e.id)).toEqual(["e1", "e2"]);
  });

  /**
   * The server owns the graph rules, so a refusal has to reach the author and stop the write.
   *
   * The definition here is perfectly well formed as far as the editor can tell — the point is that
   * the editor no longer has an opinion. It asks, and the answer blocks the save.
   */
  test("a server validation error blocks the save", async ({ page }) => {
    const state = await mockBackend(page);
    state.validationErrors = [
      { code: "CYCLE", message: "Cycle detected in pipeline graph — nodes form a circular dependency" },
      { code: "UNREACHABLE", message: "Unreachable node(s): [thumb] — every node must be reachable from the pipeline source", nodeId: "thumb" },
    ];
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    await page.getByTestId("pipeline-clone-button").click();
    await expect(page.getByTestId("pipeline-create-dialog")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-create-confirm").click();

    // The first error is surfaced …
    await expect(page.getByText("Cycle detected in pipeline graph", { exact: false })).toBeVisible({ timeout: 10_000 });
    // … and nothing was written.
    await expect(page.getByRole("button", { name: "Quick Hash (copy)" })).toBeHidden();
    expect(state.created).toHaveLength(0);

    // Clearing the verdict lets the same definition through, which is the proof that the server
    // was the thing blocking it rather than anything the editor decided on its own.
    state.validationErrors = null;
    await page.getByTestId("pipeline-create-confirm").click();
    await expect(page.getByRole("button", { name: "Quick Hash (copy)" })).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => state.created.length).toBe(1);
  });

  /**
   * Every problem is shown at once — the reason the route collects rather than throwing on the
   * first. The toast can only carry one; the JSON tab's panel carries the list.
   */
  test("the JSON tab lists every error the server reported", async ({ page }) => {
    const state = await mockBackend(page);
    state.validationErrors = [
      { code: "NODE_TYPE_UNKNOWN", message: 'Unknown node type: "ghost" — not found in descriptor registry', nodeId: "sha512" },
      { code: "CYCLE", message: "Cycle detected in pipeline graph — nodes form a circular dependency" },
    ];
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    await page.getByTestId("pipeline-clone-button").click();
    await expect(page.getByTestId("pipeline-create-dialog")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-create-confirm").click();
    await expect(page.getByText("Unknown node type", { exact: false }).first()).toBeVisible({ timeout: 10_000 });

    // The dialog stays open on a refusal — dismiss it to reach the editor behind it.
    await page.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByTestId("pipeline-create-dialog")).toBeHidden({ timeout: 5_000 });

    await page.getByTestId("pipeline-tab-json").click();
    const panel = page.getByTestId("pipeline-validation-errors");
    await expect(panel).toBeVisible({ timeout: 10_000 });
    await expect(panel.getByText("Unknown node type", { exact: false })).toBeVisible();
    await expect(panel.getByText("Cycle detected", { exact: false })).toBeVisible();
  });

  test("deleting a pipeline removes it and reselects another", async ({ page }) => {
    const state = await mockBackend(page);
    await login(page);

    // Create a second pipeline so a fallback selection exists after delete.
    await page.getByTestId("pipeline-create-button").click();
    await expect(page.getByTestId("pipeline-create-dialog")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-create-name").locator("input").fill("Second");
    await page.getByTestId("pipeline-create-confirm").click();
    await expect(page.getByRole("button", { name: "Second" })).toBeVisible({ timeout: 10_000 });

    // The newly created "Second" is selected — delete it.
    await page.getByTestId("pipeline-delete-button").click();
    await expect(page.getByRole("heading", { name: "Delete Pipeline" })).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-delete-confirm").click();

    // It disappears from the list and the DELETE reached the server …
    await expect(page.getByRole("button", { name: "Second" })).toBeHidden({ timeout: 10_000 });
    await expect.poll(() => state.deleted.length).toBe(1);
    // … while the original remains and is reselected.
    await expect(page.getByRole("button", { name: "Quick Hash" })).toBeVisible();
    await expect(page.getByTestId("pipeline-canvas").locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });
  });

  test("switching pipelines with unsaved changes prompts to discard", async ({ page }) => {
    const state = await mockBackend(page);
    // Seed a second pipeline so there are two list entries to switch between.
    state.pipelines.push(pipelineResponse("33333333-3333-3333-3333-333333333333", "Beta", { nodes: [], edges: [] }));
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    // Make the canvas dirty by dragging a node (mirrors the versions spec helper).
    const node = canvas.locator(".react-flow__node").first();
    const box = await node.boundingBox();
    expect(box).toBeTruthy();
    await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await page.mouse.down();
    await page.mouse.move(box!.x + box!.width / 2 + 60, box!.y + box!.height / 2 + 40, { steps: 8 });
    await page.mouse.up();
    // The Save chip flips to the dirty "Save" state.
    await expect(page.getByText("Save", { exact: true })).toBeVisible({ timeout: 5_000 });

    // Switching now pops the discard-confirm guard instead of switching silently.
    await page.getByRole("button", { name: "Beta" }).click();
    await expect(page.getByTestId("pipeline-switch-confirm")).toBeVisible({ timeout: 5_000 });

    // Cancelling keeps us on Quick Hash; confirming discards and switches.
    await page.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByTestId("pipeline-switch-confirm")).toBeHidden();
    await page.getByRole("button", { name: "Beta" }).click();
    await page.getByTestId("pipeline-switch-confirm").click();
    await expect(canvas.locator(".react-flow__node")).toHaveCount(0, { timeout: 10_000 });
  });

  test("leaving the editor with unsaved changes prompts to discard", async ({ page }) => {
    await mockBackend(page);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    // Edit a node parameter. The switch guard above only covers the editor's own pipeline list —
    // every other way out of the screen used to drop the canvas without a word.
    await page.getByTestId("pipeline-node-sha512").click();
    const chunkSize = page.getByTestId("pipeline-node-param-chunkSize");
    await expect(chunkSize).toBeVisible({ timeout: 5_000 });
    await chunkSize.fill("4096");
    await expect(page.getByText("Save", { exact: true })).toBeVisible({ timeout: 5_000 });

    // The app sidebar is the main way out, so it is where the guard has to hold.
    await page.getByTestId("sidebar-item-/assets").click();
    await expect(page.getByTestId("pipeline-switch-confirm")).toBeVisible({ timeout: 5_000 });

    // Cancelling keeps the route *and* the edit — a guard that dropped the value would be no
    // better than the silent discard it replaces.
    await page.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByTestId("pipeline-switch-confirm")).toBeHidden();
    await expect(canvas).toBeVisible();
    await expect(chunkSize).toHaveValue("4096");

    // Discarding resumes the navigation the dialog was holding.
    await page.getByTestId("sidebar-item-/assets").click();
    await page.getByTestId("pipeline-switch-confirm").click();
    await expect(canvas).toBeHidden({ timeout: 10_000 });
  });
});
