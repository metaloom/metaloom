import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for asset reactions (real backend).
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Exercises the asset-scoped reaction routes:
 *   GET/POST /api/v1/assets/:uuid/reactions   and   DELETE /api/v1/assets/:uuid/reactions/:reactionUuid
 */

/** Login and open the detail view of the demo asset "sunset-beach.jpg" on the Reactions tab. */
async function loginAndOpenReactionsTab(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Assets" }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });

  const assetLink = page.getByText("sunset-beach.jpg").first();
  await expect(assetLink).toBeVisible({ timeout: 10_000 });
  await assetLink.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });

  // Switch to the Reactions tab
  await page.getByRole("tab", { name: /reactions/i }).click();
}

test.describe("Asset reactions – backend e2e", () => {
  test("can add a reaction to an asset and delete it", async ({ page }) => {
    await loginAndOpenReactionsTab(page);

    // The demo asset may already carry reactions, so assert relative to the
    // starting count rather than an absolute value.
    const allChips = page.getByTestId("assets-reaction-chip");
    const initialCount = await allChips.count();

    // Add a THUMBSUP reaction.
    await page.getByTestId("assets-react-button").click();
    await page.getByTestId("assets-react-option-THUMBSUP").click();

    // The reaction chip appears and round-trips through the backend.
    await expect(allChips).toHaveCount(initialCount + 1, { timeout: 10_000 });
    const thumbsUp = allChips.filter({ hasText: "Thumbs up" });
    await expect(thumbsUp.first()).toBeVisible({ timeout: 10_000 });

    // Delete the reaction (the delete affordance is only rendered for reactions
    // authored by the current user, so its presence also proves identity resolution).
    await thumbsUp.first().getByTestId("assets-reaction-delete").click();
    await expect(allChips).toHaveCount(initialCount, { timeout: 10_000 });
  });
});
