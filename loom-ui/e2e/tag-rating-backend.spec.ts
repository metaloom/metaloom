import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end test for the per-user tag rating widget in the Tags view.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * The rating is stored per-user in `tag_user_meta` via POST /tags/:uuid/rating and is
 * persisted immediately (not via the Save button).
 */

/** Login and navigate to the Tags view via sidebar (preserves in-memory auth). */
async function loginAndGoToTags(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Tags" }).first().click();
  await expect(page.getByRole("heading", { name: "Tags" })).toBeVisible({ timeout: 10_000 });
}

async function createTag(page: Page, name: string, collection: string) {
  await page.getByPlaceholder("Tag name…").fill(name);
  await page.getByPlaceholder("Collection…").fill(collection);
  await page.getByPlaceholder("Tag name…").press("Enter");
  await expect(page.getByText(name, { exact: true })).toBeVisible({ timeout: 10_000 });
}

async function deleteTagViaMenu(page: Page, name: string) {
  const tagRow = page.getByText(name, { exact: true });
  await tagRow.hover();
  await tagRow.locator("..").locator("button").last().click();
  await page.getByRole("menuitem", { name: /delete/i }).click();
  await expect(page.getByText(name, { exact: true })).toBeHidden({ timeout: 5_000 });
}

async function selectTag(page: Page, name: string) {
  await page.getByText(name, { exact: true }).click();
  await expect(page.getByText("Tag Details")).toBeVisible({ timeout: 5_000 });
}

test.describe("Tags – per-user rating e2e", () => {
  test("set a rating, reload, and assert it persists", async ({ page }) => {
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });

    // Throwaway tag so we never mutate demo data.
    await createTag(page, "rating-src-tag", "ratecol");
    await selectTag(page, "rating-src-tag");

    // No rating initially — the 7-star input must be unchecked.
    const widget = page.getByTestId("tags-rating");
    await expect(widget).toBeVisible();
    await expect(widget.getByLabel("7 Stars")).not.toBeChecked();

    // Set a rating of 7 (MUI Rating renders one radio per star, aria-labelled "N Star(s)").
    await widget.getByLabel("7 Stars").check({ force: true });
    await expect(widget.getByLabel("7 Stars")).toBeChecked();

    // Reload from the backend (in-memory auth is dropped, so re-login + navigate).
    await loginAndGoToTags(page);
    await expect(page.getByText(/[1-9]\d* tags across/)).toBeVisible({ timeout: 10_000 });
    await selectTag(page, "rating-src-tag");

    // The rating persisted server-side per-user.
    await expect(page.getByTestId("tags-rating").getByLabel("7 Stars")).toBeChecked({ timeout: 5_000 });

    // Cleanup — keep the suite idempotent.
    await deleteTagViaMenu(page, "rating-src-tag");
  });
});
