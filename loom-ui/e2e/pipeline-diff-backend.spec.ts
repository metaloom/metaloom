import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for the pipeline version diff view.
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
 *     GET  /api/v1/pipelines/:uuid/versions/:version
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
 * Edit the selected pipeline and save so the backend mints a new version.
 * The Save chip is only clickable while the editor is dirty, so we first make a
 * graph change by dragging a node — that moves the node's `position`, which the
 * version diff reports as a changed line.
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

test.describe("Pipeline version diff – backend e2e", () => {

  test("editing + saving mints a version whose diff against the previous reports the change", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });

    // Edit + save to mint a fresh version on top of an existing one.
    const before = Number((await badge.textContent())!.trim().replace(/^v/, ""));
    const saved = await makeDirtyAndSave(page);
    expect(saved.versionNumber).toBeGreaterThan(before);
    await expect(badge).toHaveText(`v${saved.versionNumber}`, { timeout: 10_000 });

    const previousVersion = saved.versionNumber - 1;
    expect(previousVersion).toBeGreaterThanOrEqual(1);

    // Open the version history and compare the previous version with current.
    await badge.click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });

    // Both versions' definitions are fetched by the diff view.
    const baseFetch = page.waitForResponse(
      resp => new RegExp(`/api/v1/pipelines/[^/]+/versions/${previousVersion}$`).test(resp.url())
        && resp.request().method() === "GET"
    );
    await page.getByTestId(`pipeline-version-compare-${previousVersion}`).click();
    await baseFetch;

    const dialog = page.getByTestId("pipeline-version-diff");
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await expect(dialog).toContainText(`Compare v${previousVersion}`);

    // The moved node produces at least one changed/added/removed line.
    await expect(dialog.getByTestId("pipeline-version-diff-changed").first()).toBeVisible({ timeout: 5_000 });

    // Closing the dialog dismisses it.
    await page.getByTestId("pipeline-version-diff-close").click();
    await expect(dialog).toBeHidden();
  });

  test("no compare action is offered for the current version", async ({ page }) => {
    await loginAndGoToPipelines(page);

    const badge = page.getByTestId("pipeline-version-badge");
    await expect(badge).toBeVisible({ timeout: 10_000 });
    const current = (await badge.textContent())!.trim().replace(/^v/, "");

    await badge.click();
    await expect(page.getByTestId("pipeline-version-list")).toBeVisible({ timeout: 5_000 });

    // You cannot compare the current version with itself.
    await expect(page.getByTestId(`pipeline-version-compare-${current}`)).toHaveCount(0);
  });
});
