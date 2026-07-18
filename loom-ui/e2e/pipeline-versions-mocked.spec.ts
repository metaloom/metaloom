import { test, expect, Page } from "@playwright/test";

/**
 * Smoke tests for the pipeline version badge / history / restore flow.
 *
 * These do NOT require a running Loom backend — every REST call is intercepted
 * via Playwright's route API. They assert the editor's version mechanics in
 * isolation: badge rendering, the history dropdown, and that a restore swaps
 * the graph shown on the node editor.
 *
 * See `pipeline-versions.spec.ts` for the equivalent tests against a real
 * backend.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";

/** v1 has 2 nodes, the current v3 has 3 — so a restore is visible on the canvas. */
const V1_DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
  ],
  edges: [{ id: "e1", source: "src", target: "sha512" }],
};

const V3_DEFINITION = {
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

function pipelineAt(versionNumber: number, definition: unknown) {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: `aaaaaaaa-0000-0000-0000-00000000000${versionNumber}`,
    versionNumber,
    name: "Quick Hash",
    description: "Mocked pipeline",
    definition,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

/**
 * Install all routes. `state` is mutated by the restore handler.
 *
 * Playwright resolves the **most recently registered** matching handler first,
 * so these are ordered least-specific → most-specific.
 */
async function mockBackend(page: Page) {
  const state = { current: pipelineAt(3, V3_DEFINITION), restoreCalls: [] as number[] };

  // Catch-all for anything else the shell loads (spaces, users, …).
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: [], contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) })
  );

  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: [state.current], _metainfo: { totalCount: 1 } }),
    })
  );

  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [state.current, pipelineAt(2, V3_DEFINITION), pipelineAt(1, V1_DEFINITION)]
          .filter(p => p.versionNumber <= state.current.versionNumber),
        _metainfo: { totalCount: 3 },
      }),
    })
  );

  await page.route(/\/api\/v1\/pipelines\/[^/]+\/versions\/(\d+)\/restore$/, route => {
    const requested = Number(route.request().url().match(/versions\/(\d+)\/restore/)![1]);
    state.restoreCalls.push(requested);
    // Copy-forward semantics: mint a NEW version holding the old contents.
    const source = requested === 1 ? V1_DEFINITION : V3_DEFINITION;
    state.current = pipelineAt(state.current.versionNumber + 1, source);
    route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(state.current) });
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

test.describe("Pipeline versioning – mocked", () => {

  test("badge shows the current version and the inspector agrees", async ({ page }) => {
    await mockBackend(page);
    await login(page);

    await expect(page.getByTestId("pipeline-version-badge")).toHaveText("v3", { timeout: 10_000 });
    await expect(page.getByTestId("pipeline-inspector-version")).toHaveText("v3");
  });

  test("clicking the badge opens the version history", async ({ page }) => {
    await mockBackend(page);
    await login(page);

    await page.getByTestId("pipeline-version-badge").click();

    const list = page.getByTestId("pipeline-version-list");
    await expect(list).toBeVisible({ timeout: 5_000 });
    await expect(list).toContainText("Version History");

    // Newest first, all three rendered.
    await expect(page.getByTestId("pipeline-version-item-3")).toBeVisible();
    await expect(page.getByTestId("pipeline-version-item-2")).toBeVisible();
    await expect(page.getByTestId("pipeline-version-item-1")).toBeVisible();

    // The current version is flagged and cannot be restored onto itself.
    await expect(page.getByTestId("pipeline-version-item-3")).toContainText("current");
    await expect(page.getByTestId("pipeline-version-restore-3")).toHaveCount(0);
    await expect(page.getByTestId("pipeline-version-restore-1")).toBeVisible();
  });

  test("restoring v1 reloads the node editor with the restored graph", async ({ page }) => {
    const state = await mockBackend(page);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    await page.getByTestId("pipeline-version-badge").click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-version-restore-1").click();

    await expect(page.getByRole("heading", { name: "Restore Version" })).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-version-restore-confirm").click();

    // The server was asked for v1 …
    await expect.poll(() => state.restoreCalls).toEqual([1]);

    // … and the editor now shows v4 (copy-forward) with v1's 2-node graph.
    await expect(page.getByTestId("pipeline-version-badge")).toHaveText("v4", { timeout: 10_000 });
    await expect(canvas.locator(".react-flow__node")).toHaveCount(2, { timeout: 10_000 });
    await expect(page.getByTestId("pipeline-inspector-version")).toHaveText("v4");
  });

  test("cancelling the restore dialog changes nothing", async ({ page }) => {
    const state = await mockBackend(page);
    await login(page);

    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3, { timeout: 10_000 });

    await page.getByTestId("pipeline-version-badge").click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("pipeline-version-restore-1").click();

    await expect(page.getByRole("heading", { name: "Restore Version" })).toBeVisible({ timeout: 5_000 });
    await page.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("heading", { name: "Restore Version" })).toBeHidden();

    expect(state.restoreCalls).toEqual([]);
    await expect(page.getByTestId("pipeline-version-badge")).toHaveText("v3");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(3);
  });
});
