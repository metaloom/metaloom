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

/**
 * Create a tag via the header inputs (mirrors the create/delete tests) and wait
 * for it to render.
 */
async function createTag(page: Page, name: string, collection: string) {
  await page.getByPlaceholder("Tag name…").fill(name);
  await page.getByPlaceholder("Collection…").fill(collection);
  await page.getByPlaceholder("Tag name…").press("Enter");
  await expect(page.getByText(name, { exact: true })).toBeVisible({ timeout: 10_000 });
}

/** Delete a tag via its row context menu (mirrors the delete test) — used for cleanup. */
async function deleteTagViaMenu(page: Page, name: string) {
  const tagRow = page.getByText(name, { exact: true });
  await tagRow.hover();
  await tagRow.locator("..").locator("button").last().click();
  await page.getByRole("menuitem", { name: /delete/i }).click();
  await expect(page.getByText(name, { exact: true })).toBeHidden({ timeout: 5_000 });
}

/**
 * Reparent a tag by dragging its row onto a collection header.
 *
 * The tags tree uses native HTML5 drag-and-drop and stores the drag source in
 * React state (`dragId`) rather than in `dataTransfer` — so Playwright's mouse
 * based `dragTo` will not trigger the React handlers. We dispatch the real
 * dragstart/dragover/drop events (sharing one DataTransfer) on the label
 * elements; the events bubble to the draggable row / drop-target header Box so
 * `onDragStart` (→ setDragId) and `onDrop` (→ updateTag) fire.
 */
async function dragTagToCollection(page: Page, tagName: string, collectionName: string) {
  const source = page.getByText(tagName, { exact: true });
  const target = page.getByText(collectionName, { exact: true });
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer());
  await source.dispatchEvent("dragstart", { dataTransfer });
  await target.dispatchEvent("dragover", { dataTransfer });
  await target.dispatchEvent("drop", { dataTransfer });
  await source.dispatchEvent("dragend", { dataTransfer });
}

/** Read the child-count Chip badge rendered next to a collection header. */
function collectionCountChip(page: Page, collection: string) {
  return page.getByText(collection, { exact: true }).locator("..").locator(".MuiChip-label");
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
    const collectionInput = page.getByRole('textbox', { name: 'Collection', exact: true });
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

  test("edit a tag's name and collection then verify after reload", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Create a throwaway tag to edit (avoid mutating demo data)
    await createTag(page, "edit-src-tag", "editcol-a");

    // Open its detail sidebar
    await page.getByText("edit-src-tag", { exact: true }).click();
    await expect(page.getByText("Tag Details")).toBeVisible({ timeout: 5_000 });

    // Change both the name and the collection, then save (fill clears first)
    await page.getByLabel("Name").fill("edit-dst-tag");
    await page.getByRole("textbox", { name: "Collection", exact: true }).fill("editcol-b");
    await page.getByRole("button", { name: "Save" }).click();

    // Reload from the backend (in-memory auth is dropped, so re-login + navigate)
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // The renamed tag lives under the new collection; the emptied source collection is gone
    await expect(page.getByText("edit-dst-tag", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("editcol-b", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(collectionCountChip(page, "editcol-b")).toHaveText("1");
    await expect(page.getByText("editcol-a", { exact: true })).toBeHidden({ timeout: 5_000 });
    await expect(page.getByText("edit-src-tag", { exact: true })).toBeHidden({ timeout: 5_000 });

    // Cleanup — keep the suite idempotent
    await deleteTagViaMenu(page, "edit-dst-tag");
  });

  test("drag a tag onto another collection header to reparent it", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Tag to move, plus an anchor tag so the target collection header exists as a drop target
    await createTag(page, "drag-src-tag", "dragcol-a");
    await createTag(page, "drag-anchor-tag", "dragcol-b");
    await expect(page.getByText("dragcol-a", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("dragcol-b", { exact: true })).toBeVisible({ timeout: 5_000 });

    // Drag the tag from collection A onto collection B's header
    await dragTagToCollection(page, "drag-src-tag", "dragcol-b");

    // In-session: the tag moved under B (count 2) and the emptied A header is gone
    await expect(collectionCountChip(page, "dragcol-b")).toHaveText("2", { timeout: 5_000 });
    await expect(page.getByText("dragcol-a", { exact: true })).toBeHidden({ timeout: 5_000 });

    // Reload from the backend and re-assert the reparent persisted server-side
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("drag-src-tag", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("dragcol-b", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(collectionCountChip(page, "dragcol-b")).toHaveText("2");
    await expect(page.getByText("dragcol-a", { exact: true })).toBeHidden({ timeout: 5_000 });

    // Cleanup — keep the suite idempotent
    await deleteTagViaMenu(page, "drag-src-tag");
    await deleteTagViaMenu(page, "drag-anchor-tag");
  });
});
