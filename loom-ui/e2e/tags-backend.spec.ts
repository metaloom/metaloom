import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for the Tags view — CRUD and filtering.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *     → 8 tags across 2 collections: "category" and "type"
 *  2. Default admin credentials: admin / finger
 */

/** Login and navigate to the Tags view via sidebar (preserves in-memory auth). */
async function loginAndGoToTags(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate via sidebar
  await page.getByRole("button", { name: "Tags" }).first().click();
  await expect(page.getByRole("heading", { name: "Tags" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Tags – full backend e2e", () => {
  test("tag list loads and displays demo tags", async ({ page }) => {
    await loginAndGoToTags(page);

    // Wait for tags to load — header shows "8 tags across 2 collections"
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Collection headers should be visible
    await expect(page.getByText("category")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("type")).toBeVisible({ timeout: 5_000 });

    // Some demo tag names should be present (collections auto-expand)
    const demoTags = ["nature", "city", "portrait", "landscape", "video", "image", "audio", "document"];
    let foundCount = 0;
    for (const name of demoTags) {
      const loc = page.getByText(name, { exact: true });
      if (await loc.isVisible().catch(() => false)) {
        foundCount++;
      }
    }
    expect(foundCount).toBeGreaterThanOrEqual(4);
  });

  test("create a new tag and verify it appears", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Count initial tags
    const headerText = await page.getByText(/\d+ tags across/).textContent();
    const initialCount = parseInt(headerText!.match(/(\d+) tags/)![1], 10);

    // Fill in new tag name and collection
    await page.getByPlaceholder("Tag name…").fill("pw-test-tag");
    await page.getByPlaceholder("Collection…").fill("e2e");
    await page.getByPlaceholder("Tag name…").press("Enter");

    // Wait for the new count to appear
    const expectedCount = initialCount + 1;
    await expect(page.getByText(new RegExp(`${expectedCount} tags across`))).toBeVisible({ timeout: 10_000 });

    // The new tag should be visible under the "e2e" collection
    await expect(page.getByText("pw-test-tag")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("e2e")).toBeVisible({ timeout: 5_000 });
  });

  test("select a tag and view details sidebar", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Click on a known demo tag
    await page.getByText("nature", { exact: true }).click();

    // The detail sidebar should appear with "Tag Details" heading
    await expect(page.getByText("Tag Details")).toBeVisible({ timeout: 5_000 });

    // Name field should contain "nature"
    const nameInput = page.getByLabel("Name");
    await expect(nameInput).toHaveValue("nature");

    // Collection field should contain "category"
    const collectionInput = page.getByLabel("Collection");
    await expect(collectionInput).toHaveValue("category");

    // UUID should be displayed
    await expect(page.getByText(/UUID:/)).toBeVisible();
  });

  test("filter tags using search", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Filter for "audio"
    await page.getByPlaceholder("Filter tags…").fill("audio");

    // "audio" should be visible
    await expect(page.getByText("audio", { exact: true })).toBeVisible({ timeout: 5_000 });

    // Tags that don't match (like "nature") should not be visible
    await expect(page.getByText("nature", { exact: true })).toBeHidden({ timeout: 3_000 });
  });

  test("delete a tag via context menu", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // First create a tag to delete (avoid deleting demo data)
    await page.getByPlaceholder("Tag name…").fill("delete-me-tag");
    await page.getByPlaceholder("Collection…").fill("temp");
    await page.getByPlaceholder("Tag name…").press("Enter");
    await expect(page.getByText("delete-me-tag")).toBeVisible({ timeout: 10_000 });

    // Get the count after creation
    const headerText = await page.getByText(/\d+ tags across/).textContent();
    const countAfterCreate = parseInt(headerText!.match(/(\d+) tags/)![1], 10);

    // Hover over the tag row to reveal the action menu
    const tagRow = page.getByText("delete-me-tag");
    await tagRow.hover();

    // Click the more (three-dot) menu button — it sits next to the tag label
    const moreButton = tagRow.locator("..").locator("button").last();
    await moreButton.click();

    // Click "Delete" in the context menu
    await page.getByRole("menuitem", { name: /delete/i }).click();

    // The tag should disappear and the count should decrease
    const expectedCount = countAfterCreate - 1;
    await expect(page.getByText(new RegExp(`${expectedCount} tags across`))).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("delete-me-tag")).toBeHidden({ timeout: 5_000 });
  });
});
