import { test, expect, Page } from "@playwright/test";

async function loginAndGoToCollections(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: /collections/i }).first().click();
  await expect(page.getByRole("heading", { name: /collections/i })).toBeVisible({ timeout: 10_000 });
}

test.describe("Collections - backend e2e", () => {
  test("list collections loaded from backend", async ({ page }) => {
    await loginAndGoToCollections(page);

    // The heading should be visible — collections may or may not have items yet
    await expect(page.getByText(/collections/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test("create, update and delete a collection", async ({ page }) => {
    await loginAndGoToCollections(page);

    const name = `pw-col-${Date.now()}`;
    const updatedName = `${name}-updated`;

    // Create
    await page.locator("button, .MuiChip-root").filter({ has: page.locator("svg[data-testid='AddOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(name);
    await page.getByRole("button", { name: /^create$/i }).click();

    // Verify the collection card appears
    const createdCard = page.locator(".MuiPaper-root").filter({ hasText: name });
    await expect(createdCard).toBeVisible({ timeout: 10_000 });

    // Edit
    await createdCard.hover();
    await createdCard.locator("button").filter({ has: page.locator("svg[data-testid='EditOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(updatedName);
    await page.getByRole("button", { name: /save/i }).click();

    const updatedCard = page.locator(".MuiPaper-root").filter({ hasText: updatedName });
    await expect(updatedCard).toBeVisible({ timeout: 10_000 });

    // Delete
    await updatedCard.hover();
    await updatedCard.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
    await page.getByRole("button", { name: /^delete$/i }).click();

    await expect(updatedCard).toBeHidden({ timeout: 10_000 });
  });

  test("search filters collections", async ({ page }) => {
    await loginAndGoToCollections(page);

    // Create two collections with different names
    const nameA = `pw-search-alpha-${Date.now()}`;
    const nameB = `pw-search-bravo-${Date.now()}`;

    for (const colName of [nameA, nameB]) {
      await page.locator("button, .MuiChip-root").filter({ has: page.locator("svg[data-testid='AddOutlinedIcon']") }).first().click();
      await page.getByLabel("Name").fill(colName);
      await page.getByRole("button", { name: /^create$/i }).click();
      await expect(page.locator(".MuiPaper-root").filter({ hasText: colName })).toBeVisible({ timeout: 10_000 });
    }

    // The in-page filter, named exactly: the sidebar's global search field is mounted on every
    // route and its placeholder also matches /search/i.
    const filter = page.getByPlaceholder("Search collections…");

    // Search for alpha
    await filter.fill("alpha");
    await expect(page.locator(".MuiPaper-root").filter({ hasText: nameA })).toBeVisible();
    await expect(page.locator(".MuiPaper-root").filter({ hasText: nameB })).toBeHidden();

    // Clear search
    await filter.fill("");
    await expect(page.locator(".MuiPaper-root").filter({ hasText: nameB })).toBeVisible();

    // Cleanup
    for (const colName of [nameA, nameB]) {
      const card = page.locator(".MuiPaper-root").filter({ hasText: colName });
      await card.hover();
      await card.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
      await page.getByRole("button", { name: /^delete$/i }).click();
      await expect(card).toBeHidden({ timeout: 10_000 });
    }
  });
});
