import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for the Pipeline Editor view.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *  3. The NodeDescriptorEndpoint is registered and returns descriptors
 */

async function loginAndGoToPipelines(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Pipelines via sidebar
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  // Wait for the pipeline editor to render (canvas area appears)
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}

/**
 * Open the add-node dropdown and optionally narrow it.
 *
 * The picker is an always-visible search bar, not a button with a menu, and its rows are
 * `add-node-<nodeId>`. The query is matched against the descriptor's name, node id and category
 * (see src/features/pipeline/nodePicker.ts), which is what replaced the old per-category chips.
 */
async function openAddNode(page: Page, query = "") {
  const search = page.getByPlaceholder(/add node/i);
  await search.click();
  await search.fill(query);
  return search;
}

test.describe("Pipeline Editor – backend e2e", () => {

  test("node descriptors are loaded from the backend API", async ({ page }) => {
    // Intercept the node-descriptors API call. Match the path exactly: the editor also fetches
    // .../node-descriptors/availability, which an `includes` check matches just as happily, and
    // whichever response lands first wins the race - so the assertions below would intermittently
    // run against the availability payload, which carries no nodeDescriptors at all.
    const descriptorsPromise = page.waitForResponse(
      resp => new URL(resp.url()).pathname.endsWith("/pipeline/node-descriptors") && resp.status() === 200
    );

    await loginAndGoToPipelines(page);

    const descriptorsResponse = await descriptorsPromise;
    const body = await descriptorsResponse.json();

    // The response should contain nodeDescriptors and contentTypes arrays
    expect(body).toHaveProperty("nodeDescriptors");
    expect(body).toHaveProperty("contentTypes");
    expect(body.nodeDescriptors.length).toBeGreaterThanOrEqual(20);
    expect(body.contentTypes.length).toBeGreaterThanOrEqual(10);

    // Verify a few well-known descriptors are present
    const kinds = body.nodeDescriptors.map((d: { kind: string }) => d.kind);
    expect(kinds).toContain("filesystem-source");
    expect(kinds).toContain("sha256");
    expect(kinds).toContain("fingerprint");
    expect(kinds).toContain("filter");
    expect(kinds).toContain("thumbnail");
  });

  test("the add-node picker lists descriptors from every category", async ({ page }) => {
    await loginAndGoToPipelines(page);

    await openAddNode(page);

    // Representative nodes from four categories, addressed by node id rather than label so a
    // renamed descriptor does not silently turn this into a no-op.
    await expect(page.getByTestId("add-node-filesystem-source")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("add-node-sha256")).toBeVisible();
    await expect(page.getByTestId("add-node-fingerprint")).toBeVisible();
    await expect(page.getByTestId("add-node-filter")).toBeVisible();
    await expect(page.getByTestId("add-node-s3-sink")).toBeVisible();

    await page.keyboard.press("Escape");
  });

  test("the picker query filters by category", async ({ page }) => {
    await loginAndGoToPipelines(page);

    // "source" matches the SOURCE category, so the source nodes stay and the analysis ones go.
    await openAddNode(page, "source");
    await expect(page.getByTestId("add-node-filesystem-source")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("add-node-loom-fetch")).toBeVisible();
    await expect(page.getByTestId("add-node-sha256")).toHaveCount(0);
    await expect(page.getByTestId("add-node-fingerprint")).toHaveCount(0);

    // One entry, not eight: the filter-* kinds collapsed into a single `filter` node whose
    // buckets are configured per instance.
    await openAddNode(page, "filter");
    await expect(page.getByTestId("add-node-filter")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("add-node-filesystem-source")).toHaveCount(0);

    await page.keyboard.press("Escape");
  });

  test("can add a source node to the pipeline canvas", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    await openAddNode(page, "filesystem");
    await page.getByTestId("add-node-filesystem-source").click();

    // No count arithmetic: the editor opens on an existing pipeline whose own nodes stream in, so a
    // "before" snapshot taken here races the load and the delta is meaningless.
    await expect(canvas.locator(".react-flow__node").filter({ hasText: "Filesystem Source" }).first())
      .toBeVisible({ timeout: 5_000 });
  });

  test("can add multiple nodes of different categories", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // One node from each of SOURCE, ANALYSIS and OUTPUT. The labels are the descriptor names the
    // backend actually serves, so they are asserted rather than guessed at.
    const added: Array<[string, string]> = [
      ["filesystem-source", "Filesystem Source"],
      ["sha256", "SHA-256 Hash"],
      ["s3-sink", "S3 Sink"],
    ];

    for (const [nodeId, label] of added) {
      await openAddNode(page, nodeId);
      await page.getByTestId(`add-node-${nodeId}`).click();
      await expect(canvas.locator(".react-flow__node").filter({ hasText: label }).first()).toBeVisible({ timeout: 5_000 });
    }

    expect(await canvas.locator(".react-flow__node").count()).toBeGreaterThanOrEqual(added.length);
  });

  test("added nodes have correct connector handles", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // A source node emits but does not consume, so it carries an output handle only.
    await openAddNode(page, "filesystem");
    await page.getByTestId("add-node-filesystem-source").click();
    const sourceNode = canvas.locator(".react-flow__node").filter({ hasText: "Filesystem Source" }).first();
    await expect(sourceNode).toBeVisible({ timeout: 5_000 });
    await expect(sourceNode.locator(".react-flow__handle-right").first()).toBeVisible();
    await expect(sourceNode.locator(".react-flow__handle-left")).toHaveCount(0);

    // An analysis node sits mid-graph and carries both.
    await openAddNode(page, "sha256");
    await page.getByTestId("add-node-sha256").click();
    const analysisNode = canvas.locator(".react-flow__node").filter({ hasText: "SHA-256 Hash" }).first();
    await expect(analysisNode).toBeVisible({ timeout: 5_000 });
    await expect(analysisNode.locator(".react-flow__handle-left").first()).toBeVisible();
    await expect(analysisNode.locator(".react-flow__handle-right").first()).toBeVisible();
  });
});
