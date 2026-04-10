import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Asset List and Asset Detail views.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

/** Login and navigate to the assets view using sidebar (client-side navigation preserves auth state). */
async function loginAndGoToAssets(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  // Wait for login to complete (login form disappears, sidebar becomes visible)
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate via sidebar link — avoids full page reload which would lose in-memory auth state
  await page.getByRole("button", { name: "Assets" }).first().click();
  // Wait for the heading in the main content area
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Assets – full backend e2e", () => {
  test("asset list loads and displays assets from the API", async ({ page }) => {
    await loginAndGoToAssets(page);

    // Wait for data to load — expect a non-zero asset count
    await expect(page.getByText(/[1-9]\d* assets/)).toBeVisible({ timeout: 10_000 });

    // Verify that at least one asset name from the demo data is rendered
    const assetNames = [
      "sunset-beach.jpg",
      "mountain-lake.jpg",
      "city-skyline.png",
      "drone-coastal.mp4",
      "ambient-rain.mp3",
      "project-brief.pdf",
    ];

    let foundAny = false;
    for (const name of assetNames) {
      const loc = page.getByText(name);
      if (await loc.isVisible().catch(() => false)) {
        foundAny = true;
        break;
      }
    }
    expect(foundAny).toBe(true);
  });

  test("clicking an asset navigates to asset detail", async ({ page }) => {
    await loginAndGoToAssets(page);

    const assetLink = page.getByText("sunset-beach.jpg").first();
    await expect(assetLink).toBeVisible({ timeout: 10_000 });
    await assetLink.click();

    // URL should contain /assets/ followed by a UUID
    await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });

    // The asset detail view should show the filename
    await expect(page.getByText("sunset-beach.jpg")).toBeVisible({ timeout: 5_000 });
  });

  test("asset detail view shows file metadata", async ({ page }) => {
    await loginAndGoToAssets(page);

    const assetLink = page.getByText("sunset-beach.jpg").first();
    await expect(assetLink).toBeVisible({ timeout: 10_000 });
    await assetLink.click();
    await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });

    // Verify metadata is displayed — file size, mime type, or filename
    await expect(page.getByText("sunset-beach.jpg")).toBeVisible({ timeout: 5_000 });

    // The detail view should render (check for Back button or tabs)
    const backButton = page.locator('[data-testid="ArrowBackIcon"]');
    await expect(backButton).toBeVisible({ timeout: 5_000 });
  });

  test("asset list search filters assets", async ({ page }) => {
    await loginAndGoToAssets(page);

    // Wait for assets to load first
    await expect(page.getByText(/[1-9]\d* assets/)).toBeVisible({ timeout: 10_000 });

    const searchInput = page.getByPlaceholder("Search assets, tags…");
    await searchInput.fill("drone");

    // "drone-coastal.mp4" should be visible, others should be filtered
    await expect(page.getByText("drone-coastal.mp4")).toBeVisible({ timeout: 5_000 });
  });
});
