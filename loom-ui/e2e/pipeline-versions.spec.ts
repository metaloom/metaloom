import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for pipeline versioning in the editor.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *  3. The pipeline version endpoints are deployed:
 *     GET  /api/v1/pipelines/:uuid/versions
 *     POST /api/v1/pipelines/:uuid/versions/:version/restore
 */

async function loginAndGoToPipelines(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}

/**
 * Save the currently selected pipeline so the backend mints a new version.
 * The Save chip is only clickable while the editor is dirty, so we first make a
 * trivial graph change by dragging a node a few pixels.
 */
async function makeDirtyAndSave(page: Page) {
  const canvas = page.getByTestId("pipeline-canvas");
  const node = canvas.locator(".react-flow__node").first();
  await expect(node).toBeVisible({ timeout: 10_000 });

  const box = await node.boundingBox();
  expect(box).toBeTruthy();
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.mouse.move(box!.x + box!.width / 2 + 40, box!.y + box!.height / 2 + 25, { steps: 8 });
  await page.mouse.up();

  const savePromise = page.waitForResponse(
    resp => /\/api\/v1\/pipelines\/[^/]+$/.test(resp.url()) && resp.request().method() === "POST"
  );
  await page.getByText("Save", { exact: true }).click();
  const saveResponse = await savePromise;
  expect(saveResponse.status()).toBeLessThan(300);
  return saveResponse.json();
}

test.describe("Pipeline versioning – backend e2e", () => {

  test("version badge shows the current version number on the canvas", async ({ page }) => {
    const pipelinesPromise = page.waitForResponse(
      resp => resp.url().includes("/api/v1/pipelines") && resp.request().method() === "GET" && resp.status() === 200
    );

    await loginAndGoToPipelines(page);

    const body = await (await pipelinesPromise).json();
    const first = body.data[0];
    expect(first).toHaveProperty("versionNumber");

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });
    await expect(badge).toHaveText(`v${first.versionNumber}`);
  });

  test("the inspector panel also shows the current version", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });
    const badgeText = await badge.textContent();

    const inspectorChip = page.getByTestId("pipeline-inspector-version");
    await expect(inspectorChip).toBeVisible();
    await expect(inspectorChip).toHaveText(badgeText!.trim());
  });

  test("clicking the badge opens the version history and calls the versions endpoint", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const versionsPromise = page.waitForResponse(
      resp => /\/api\/v1\/pipelines\/[^/]+\/versions$/.test(resp.url())
        && resp.request().method() === "GET"
        && resp.status() === 200
    );

    await page.getByTestId("pipeline-version-badge").click();

    const body = await (await versionsPromise).json();
    expect(body).toHaveProperty("data");

    const list = page.getByTestId("pipeline-version-list");
    await expect(list).toBeVisible({ timeout: 5_000 });
    await expect(list).toContainText("Version History");

    // Every version returned by the server should be rendered as a row.
    for (const v of body.data as Array<{ versionNumber: number }>) {
      await expect(page.getByTestId(`pipeline-version-item-${v.versionNumber}`)).toBeVisible();
    }
  });

  test("the current version is marked and has no restore button", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });
    const current = (await badge.textContent())!.trim().replace(/^v/, "");

    await badge.click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });

    const currentRow = page.getByTestId(`pipeline-version-item-${current}`);
    await expect(currentRow).toBeVisible();
    await expect(currentRow).toContainText("current");
    // Restoring the version you are already on is not offered.
    await expect(page.getByTestId(`pipeline-version-restore-${current}`)).toHaveCount(0);
  });

  test("saving creates a new version and the badge increments", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });
    const before = Number((await badge.textContent())!.trim().replace(/^v/, ""));

    const saved = await makeDirtyAndSave(page);
    expect(saved.versionNumber).toBeGreaterThan(before);

    await expect(badge).toHaveText(`v${saved.versionNumber}`, { timeout: 10_000 });
  });

  test("restoring a previous version reloads the node editor", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const canvas = page.getByTestId("pipeline-canvas");
    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });
    await expect(canvas.locator(".react-flow__node").first()).toBeVisible({ timeout: 10_000 });

    // Ensure there is at least one older version to restore to.
    const startVersion = Number((await badge.textContent())!.trim().replace(/^v/, ""));
    if (startVersion < 2) {
      await makeDirtyAndSave(page);
    }

    const currentVersion = Number((await badge.textContent())!.trim().replace(/^v/, ""));
    const targetVersion = currentVersion - 1;
    expect(targetVersion).toBeGreaterThanOrEqual(1);

    const nodeCountBefore = await canvas.locator(".react-flow__node").count();

    // Open history and trigger the restore.
    await badge.click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId(`pipeline-version-restore-${targetVersion}`).click();

    // Confirm the dialog.
    const restorePromise = page.waitForResponse(
      resp => /\/api\/v1\/pipelines\/[^/]+\/versions\/\d+\/restore$/.test(resp.url())
        && resp.request().method() === "POST"
    );
    await page.getByTestId("pipeline-version-restore-confirm").click();

    const restoreResponse = await restorePromise;
    expect(restoreResponse.status()).toBe(201);
    const restored = await restoreResponse.json();

    // Restore is copy-forward: the server mints a NEW version rather than
    // rewinding to the requested one.
    expect(restored.versionNumber).toBeGreaterThan(currentVersion);

    // The badge reflects the new version and the canvas has been rebuilt from
    // the restored definition.
    await expect(badge).toHaveText(`v${restored.versionNumber}`, { timeout: 10_000 });
    await expect(canvas.locator(".react-flow__node").first()).toBeVisible({ timeout: 10_000 });
    const nodeCountAfter = await canvas.locator(".react-flow__node").count();
    expect(nodeCountAfter).toBe((restored.definition?.nodes ?? []).length || nodeCountBefore);

    // A restore leaves the editor in a clean (non-dirty) state.
    await expect(page.getByText("Saved", { exact: true })).toBeVisible({ timeout: 5_000 });
  });

  test("cancelling the restore dialog leaves the version untouched", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });

    let current = Number((await badge.textContent())!.trim().replace(/^v/, ""));
    if (current < 2) {
      const saved = await makeDirtyAndSave(page);
      current = saved.versionNumber;
      await expect(badge).toHaveText(`v${current}`, { timeout: 10_000 });
    }

    await badge.click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId(`pipeline-version-restore-${current - 1}`).click();

    await expect(page.getByRole("heading", { name: "Restore Version" })).toBeVisible({ timeout: 5_000 });
    await page.getByRole("button", { name: "Cancel" }).click();

    await expect(page.getByRole("heading", { name: "Restore Version" })).toBeHidden();
    await expect(badge).toHaveText(`v${current}`);
  });
});
