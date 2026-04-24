import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for loading pipelines from the backend REST API.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *  3. DemoDatabaseInitializer creates three pipelines:
 *     "Quick Hash" (simple), "Ingest & Proxy" (medium), "Full Processing" (complex)
 */

async function loginAndGoToPipelines(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Pipelines via sidebar
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  // Wait for the pipeline editor to render
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}

test.describe("Pipeline loading – backend e2e", () => {

  test("pipelines are loaded from the backend REST API", async ({ page }) => {
    // Intercept the pipelines list API call
    const pipelinesPromise = page.waitForResponse(
      resp => resp.url().includes("/api/v1/pipelines") && resp.request().method() === "GET" && resp.status() === 200
    );

    await loginAndGoToPipelines(page);

    const pipelinesResponse = await pipelinesPromise;
    const body = await pipelinesResponse.json();

    // The response should contain pipeline data
    expect(body).toHaveProperty("data");
    expect(body.data.length).toBeGreaterThanOrEqual(3);

    // Verify the three demo pipelines are present
    const names = body.data.map((p: { name: string }) => p.name);
    expect(names).toContain("Quick Hash");
    expect(names).toContain("Ingest & Proxy");
    expect(names).toContain("Full Processing");
  });

  test("pipelines have definition with nodes and edges", async ({ page }) => {
    const pipelinesPromise = page.waitForResponse(
      resp => resp.url().includes("/api/v1/pipelines") && resp.request().method() === "GET" && resp.status() === 200
    );

    await loginAndGoToPipelines(page);

    const pipelinesResponse = await pipelinesPromise;
    const body = await pipelinesResponse.json();

    for (const pipeline of body.data) {
      expect(pipeline).toHaveProperty("definition");
      expect(pipeline.definition).toHaveProperty("nodes");
      expect(pipeline.definition).toHaveProperty("edges");
      expect(pipeline.definition.nodes.length).toBeGreaterThan(0);
      expect(pipeline.definition.edges.length).toBeGreaterThan(0);
    }

    // Verify the simple pipeline has 3 nodes, 2 edges
    const simple = body.data.find((p: { name: string }) => p.name === "Quick Hash");
    expect(simple).toBeTruthy();
    expect(simple.definition.nodes).toHaveLength(3);
    expect(simple.definition.edges).toHaveLength(2);

    // Verify the complex pipeline has 8 nodes, 8 edges
    const complex = body.data.find((p: { name: string }) => p.name === "Full Processing");
    expect(complex).toBeTruthy();
    expect(complex.definition.nodes).toHaveLength(8);
    expect(complex.definition.edges).toHaveLength(8);
  });

  test("first pipeline is selected and rendered on the canvas", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // Wait for nodes to appear on the canvas (from the first loaded pipeline)
    await expect(canvas.locator(".react-flow__node").first()).toBeVisible({ timeout: 10_000 });

    // There should be at least 2 nodes on the canvas (even the simplest pipeline has 3)
    const nodeCount = await canvas.locator(".react-flow__node").count();
    expect(nodeCount).toBeGreaterThanOrEqual(2);
  });

  test("pipeline list shows loaded pipelines in the sidebar", async ({ page }) => {
    await loginAndGoToPipelines(page);

    // The pipeline list in the sidebar should show at least the 3 demo pipelines
    // Use getByRole('button') scoped to text match to avoid matching the selected
    // pipeline's name that also appears in the toolbar, header, and log areas.
    await expect(page.getByRole("button", { name: "Quick Hash" })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("button", { name: "Ingest & Proxy" })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByRole("button", { name: "Full Processing" })).toBeVisible({ timeout: 5_000 });
  });

  test("switching pipelines updates the canvas nodes", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");

    // Wait for initial render
    await expect(canvas.locator(".react-flow__node").first()).toBeVisible({ timeout: 10_000 });
    const initialNodeCount = await canvas.locator(".react-flow__node").count();

    // Click on a different pipeline in the list (e.g. "Full Processing" which has more nodes)
    await page.getByText("Full Processing").click();

    // Wait a moment for the canvas to update
    await page.waitForTimeout(500);

    // The complex pipeline should have 8 nodes
    const newNodeCount = await canvas.locator(".react-flow__node").count();
    // If we started on a different pipeline, the count should change
    // At minimum, the complex pipeline has 8 nodes
    if (initialNodeCount !== 8) {
      expect(newNodeCount).not.toBe(initialNodeCount);
    }
    expect(newNodeCount).toBeGreaterThanOrEqual(3);
  });

  test("JSON view shows loaded pipeline definition", async ({ page }) => {
    await loginAndGoToPipelines(page);

    // Switch to JSON tab
    const jsonTab = page.getByRole("tab", { name: "JSON" });
    await jsonTab.click();

    // Should show both "Loaded Definition" and "Current Canvas State" headers
    await expect(page.getByText("Loaded Definition")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("Current Canvas State")).toBeVisible();

    // The loaded definition JSON should contain node data
    const jsonView = page.locator("pre").first();
    await expect(jsonView).toContainText("nodes");
    await expect(jsonView).toContainText("edges");
  });
});
