import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for comment authoring on the Asset Detail view.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Exercises the asset-scoped comment routes:
 *   GET/POST /api/v1/assets/:uuid/comments   and   POST/DELETE /api/v1/comments/:uuid
 */

/** Login and open the detail view of the demo asset "street-crossing.jpg". */
async function loginAndOpenAssetDetail(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Assets" }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });

  const assetLink = page.getByText("street-crossing.jpg").first();
  await expect(assetLink).toBeVisible({ timeout: 10_000 });
  await assetLink.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });

  // Switch to the Comments tab
  await page.getByRole("tab", { name: /comments/i }).click();
}

test.describe("Comments – full backend e2e", () => {
  test("post → edit → delete a comment on an asset", async ({ page }) => {
    await loginAndOpenAssetDetail(page);

    const text = `pw-comment-${Date.now()}`;
    const editedText = `${text}-edited`;

    // ── Post ──────────────────────────────────────────────────────────
    const composer = page.getByPlaceholder("Write a comment…");
    await expect(composer).toBeVisible({ timeout: 5_000 });
    await composer.fill(text);
    await page.getByRole("button", { name: "Post comment" }).click();
    await expect(page.getByText(text, { exact: true })).toBeVisible({ timeout: 10_000 });

    // ── Edit ──────────────────────────────────────────────────────────
    const commentBox = page.locator("div").filter({ hasText: text }).last();
    await commentBox.hover();
    await page.getByTestId("comment-edit").click();
    await page.getByTestId("comment-edit-field").fill(editedText);
    await page.getByTestId("comment-save").click();
    await expect(page.getByText(editedText, { exact: true })).toBeVisible({ timeout: 10_000 });

    // ── Delete ────────────────────────────────────────────────────────
    const editedBox = page.locator("div").filter({ hasText: editedText }).last();
    await editedBox.hover();
    await page.getByTestId("comment-delete").click();
    await expect(page.getByText(editedText, { exact: true })).toBeHidden({ timeout: 10_000 });
  });
});
