import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end test for region (time + area) tagging of assets.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * The test draws a rubber-band box on an image asset, tags the region, then
 * navigates away and back (client-side, so the asset is re-fetched from the API)
 * and asserts the region tag + its overlay box survive the round-trip.
 */

const IMAGE_ASSET = "sunset-beach.jpg";

async function loginAndGoToAssets(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Assets" }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

async function openImageAsset(page: Page) {
  const link = page.getByText(IMAGE_ASSET).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  await expect(page.getByTestId("asset-tags")).toBeVisible({ timeout: 10_000 });
}

test.describe("Region tagging – full backend e2e", () => {
  test("draw a region on an image, tag it, and verify it persists", async ({ page }) => {
    const tagName = `pw-region-${Date.now()}`;

    await loginAndGoToAssets(page);
    await openImageAsset(page);

    // Enter region-tagging mode.
    await page.getByTestId("region-mode-toggle").click();

    // Draw a rubber-band box across the middle of the image.
    const image = page.getByTestId("zoomable-image");
    await expect(image).toBeVisible();
    const box = await image.boundingBox();
    if (!box) throw new Error("zoomable-image has no bounding box");
    await page.mouse.move(box.x + box.width * 0.3, box.y + box.height * 0.3);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.65, box.y + box.height * 0.65, { steps: 10 });
    await page.mouse.up();

    // The preview overlay for the captured region should now be shown.
    await expect(page.getByTestId("image-region").first()).toBeVisible({ timeout: 5_000 });

    // Name the region tag and save it.
    const input = page.getByTestId("tag-input");
    await input.fill(tagName);
    await input.press("Enter");

    // The region tag chip should appear.
    await expect(page.getByTestId("region-tag-chip").filter({ hasText: tagName }))
      .toBeVisible({ timeout: 10_000 });

    // Navigate away and back (client-side) to force a re-fetch from the API.
    await page.locator('[data-testid="ArrowBackIcon"]').click();
    await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
    await openImageAsset(page);

    // The region tag and its overlay box must have survived the round-trip.
    const persistedChip = page.getByTestId("region-tag-chip").filter({ hasText: tagName });
    await expect(persistedChip).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("image-region").first()).toBeVisible({ timeout: 5_000 });

    // Cleanup — remove the tag so the test stays idempotent against demo data.
    await persistedChip.getByTestId("CancelIcon").click();
    await expect(page.getByTestId("region-tag-chip").filter({ hasText: tagName }))
      .toBeHidden({ timeout: 10_000 });
  });
});
