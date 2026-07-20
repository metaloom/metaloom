import { test, expect, Page } from "@playwright/test";

/**
 * Affinity-group editing for the pipeline editor (Task 5).
 *
 * Nodes sharing an `affinity` string are dispatched together as a single
 * pipeline segment by the engine. These tests assign a group to two connected
 * nodes, verify the canvas colours/badges them, that Save persists a top-level
 * `affinity` field on each node (the exact shape Loom's PipelineGraphParser
 * reads), and that the grouping survives a reload.
 *
 * No running Loom backend — every REST call is intercepted. Mirrors the routing
 * conventions of `pipeline-crud-mocked.spec.ts`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";

/** Seed: source → sha512 → thumbnail (two connected downstream nodes to group). */
const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
    { id: "thumb", type: "thumbnail", label: "Thumbnail", position: { x: 520, y: 0 }, data: {} },
  ],
  edges: [
    { id: "e1", source: "src", target: "sha512" },
    { id: "e2", source: "sha512", target: "thumb" },
  ],
};

/** Descriptors so the client-side validatePipeline accepts these node kinds. */
const DESCRIPTORS = [
  { kind: "filesystem-source", category: "SOURCE" },
  { kind: "sha512", category: "ANALYSIS" },
  { kind: "thumbnail", category: "ANALYSIS" },
].map(({ kind, category }) => ({
  kind,
  name: kind,
  description: "",
  icon: "",
  category,
  inputs: kind === "filesystem-source" ? [] : [{ name: "in", contentType: "media" }],
  outputs: [{ name: "out", contentType: "media" }],
  parameters: [],
  defaultConcurrency: 1,
  defaultMode: "SEQUENTIAL",
  defaultBlocking: false,
  events: [],
}));

function pipelineResponse(uuid: string, name: string, definition: unknown, version = 1, extra: Record<string, unknown> = {}) {
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
    ...extra,
  };
}

interface MockState {
  pipelines: ReturnType<typeof pipelineResponse>[];
  saved: any[];
}

async function mockBackend(page: Page): Promise<MockState> {
  const state: MockState = {
    pipelines: [pipelineResponse(PIPELINE_UUID, "Quick Hash", DEFINITION)],
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
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: DESCRIPTORS, contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
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
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: p ? [p] : [], _metainfo: { totalCount: p ? 1 : 0 } }),
    });
  });

  // GET single + POST update (save) + DELETE.
  await page.route(/\/api\/v1\/pipelines\/[^/]+$/, route => {
    const req = route.request();
    const uuid = req.url().match(/pipelines\/([^/?]+)/)![1];
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      state.saved.push(body);
      const idx = state.pipelines.findIndex(p => p.uuid === uuid);
      const nextVersion = (state.pipelines[idx]?.versionNumber ?? 1) + 1;
      const updated = pipelineResponse(uuid, body.name, body.definition, nextVersion, {
        description: body.description ?? "",
        enabled: body.enabled ?? true,
        priority: body.priority ?? 0,
        dryRun: body.dryRun ?? false,
      });
      if (idx >= 0) state.pipelines[idx] = updated;
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(updated) });
      return;
    }
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({ status: p ? 200 : 404, contentType: "application/json", body: JSON.stringify(p ?? {}) });
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

/** Select a node and type an affinity group into the sidebar's affinity input. */
async function assignAffinity(page: Page, nodeId: string, group: string) {
  await page.getByTestId(`pipeline-node-${nodeId}`).click();
  const input = page.getByTestId("pipeline-node-affinity-input");
  await expect(input).toBeVisible({ timeout: 5_000 });
  await input.fill(group);
  // Commit the free-text value (Autocomplete freeSolo) and close the popup.
  await input.press("Enter");
}

test.describe("Pipeline affinity groups – mocked", () => {

  test("assigning a group colours the nodes and persists across save + reload", async ({ page }) => {
    const state = await mockBackend(page);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    // Two connected downstream nodes → same group.
    await assignAffinity(page, "sha512", "groupA");
    await assignAffinity(page, "thumb", "groupA");

    // Both grouped nodes carry the affinity + badge; the source stays default.
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-affinity", "groupA");
    await expect(page.getByTestId("pipeline-node-thumb")).toHaveAttribute("data-affinity", "groupA");
    await expect(page.getByTestId("pipeline-node-affinity-badge-sha512")).toBeVisible();
    await expect(page.getByTestId("pipeline-node-affinity-badge-thumb")).toBeVisible();
    await expect(page.getByTestId("pipeline-node-src")).toHaveAttribute("data-affinity", "default");
    await expect(page.getByTestId("pipeline-node-affinity-badge-src")).toHaveCount(0);

    // Save and assert the persisted definition carries top-level `affinity`.
    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBeGreaterThan(0);

    const posted = state.saved[state.saved.length - 1];
    const byId = (id: string) => posted.definition.nodes.find((n: any) => n.id === id);
    expect(byId("sha512").affinity).toBe("groupA");
    expect(byId("thumb").affinity).toBe("groupA");
    // Default group is omitted (backward compatible / clean JSON).
    expect(byId("src").affinity).toBeUndefined();
    // The real node kind is preserved on save (not the category).
    expect(byId("sha512").type).toBe("sha512");

    // Reload: session is in-memory, so log in again; the server now returns the
    // saved definition. The grouping must re-render from the reloaded definition.
    await page.reload();
    await login(page);
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });
    await expect(page.getByTestId("pipeline-node-sha512")).toHaveAttribute("data-affinity", "groupA");
    await expect(page.getByTestId("pipeline-node-thumb")).toHaveAttribute("data-affinity", "groupA");
    await expect(page.getByTestId("pipeline-node-affinity-badge-sha512")).toBeVisible();
  });
});
