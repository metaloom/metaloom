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

test.describe("Pipeline Editor – backend e2e", () => {

  test("node descriptors are loaded from the backend API", async ({ page }) => {
    // Intercept the node-descriptors API call
    const descriptorsPromise = page.waitForResponse(
      resp => resp.url().includes("/api/v1/pipeline/node-descriptors") && resp.status() === 200
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

  test("add-node menu shows descriptors grouped by category", async ({ page }) => {
    await loginAndGoToPipelines(page);

    // Click the "Add Node" button
    await page.getByTestId("pipeline-add-node-button").click();

    // The add-node menu should be visible
    const menu = page.getByTestId("pipeline-add-node-menu");
    await expect(menu).toBeVisible({ timeout: 5_000 });

    // Verify representative nodes from different categories appear in the menu
    await expect(page.getByTestId("pipeline-node-item-filesystem-source")).toBeVisible();
    await expect(page.getByTestId("pipeline-node-item-sha256")).toBeVisible();
    await expect(page.getByTestId("pipeline-node-item-fingerprint")).toBeVisible();
    await expect(page.getByTestId("pipeline-node-item-filter")).toBeVisible();
    await expect(page.getByTestId("pipeline-node-item-loom")).toBeVisible();

    // Close the menu
    await page.keyboard.press("Escape");
  });

  test("category chips filter the add-node menu", async ({ page }) => {
    await loginAndGoToPipelines(page);

    // Click the "Source" category chip
    await page.getByTestId("pipeline-category-chip-source").click();

    // Only SOURCE nodes should be visible
    await expect(page.getByTestId("pipeline-node-item-filesystem-source")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-node-item-loom-fetch")).toBeVisible();

    // ANALYSIS nodes should NOT be visible
    await expect(page.getByTestId("pipeline-node-item-sha256")).not.toBeVisible();
    await expect(page.getByTestId("pipeline-node-item-fingerprint")).not.toBeVisible();

    // Close the menu
    await page.keyboard.press("Escape");

    // Click "Filter" category chip
    await page.getByTestId("pipeline-category-chip-filter").click();
    // One entry, not eight: the filter-* kinds collapsed into a single `filter` node whose
    // buckets are configured per instance.
    await expect(page.getByTestId("pipeline-node-item-filter")).toBeVisible({ timeout: 5_000 });
    // SOURCE nodes should NOT be visible in filter category
    await expect(page.getByTestId("pipeline-node-item-filesystem-source")).not.toBeVisible();

    await page.keyboard.press("Escape");
  });

  test("can add a source node to the pipeline canvas", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // Click "Add Node" and select "Filesystem Source"
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-filesystem-source").click();

    // The node should appear on the canvas with the correct label
    await expect(canvas.locator(".react-flow__node").filter({ hasText: "Filesystem Source" })).toBeVisible({ timeout: 5_000 });
  });

  test("can add multiple nodes of different categories", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // Add a source node: Filesystem Source
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-filesystem-source").click();
    await expect(canvas.locator(".react-flow__node").filter({ hasText: "Filesystem Source" })).toBeVisible({ timeout: 5_000 });

    // Add an analysis node: SHA-256 Hash
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-sha256").click();
    await expect(canvas.locator(".react-flow__node").filter({ hasText: "SHA-256 Hash" })).toBeVisible({ timeout: 5_000 });

    // Add a filter node: Filter Mimetype
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-filter").click();
    await expect(canvas.locator(".react-flow__node").filter({ hasText: "MIME Type Filter" })).toBeVisible({ timeout: 5_000 });

    // Add an output node: Loom Output
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-loom").click();
    await expect(canvas.locator(".react-flow__node").filter({ hasText: "Loom Sync" })).toBeVisible({ timeout: 5_000 });

    // Verify total node count — the mock pipeline has some existing nodes too,
    // so we just check our 4 newly added nodes are all on the canvas
    const allNodes = canvas.locator(".react-flow__node");
    const nodeCount = await allNodes.count();
    expect(nodeCount).toBeGreaterThanOrEqual(4);
  });

  test("added nodes have correct connector handles", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // Add a source node (source nodes have no input handles, only output)
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-filesystem-source").click();
    const sourceNode = canvas.locator(".react-flow__node").filter({ hasText: "Filesystem Source" });
    await expect(sourceNode).toBeVisible({ timeout: 5_000 });
    // Source nodes should have output handles but no input handles
    await expect(sourceNode.locator(".react-flow__handle-right")).toBeVisible();

    // Add an analysis node (has both input and output handles)
    await page.getByTestId("pipeline-add-node-button").click();
    await page.getByTestId("pipeline-node-item-sha256").click();
    const analysisNode = canvas.locator(".react-flow__node").filter({ hasText: "SHA-256 Hash" });
    await expect(analysisNode).toBeVisible({ timeout: 5_000 });
    // Analysis nodes should have both input and output handles
    await expect(analysisNode.locator(".react-flow__handle-left")).toBeVisible();
    await expect(analysisNode.locator(".react-flow__handle-right")).toBeVisible();
  });
});
