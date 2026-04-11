import { test, expect, Page } from "@playwright/test";

async function loginAndGoToLibrary(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: /library|libraries|bibliothek/i }).first().click();
  await expect(page.getByRole("heading", { name: /libraries|bibliotheken/i })).toBeVisible({ timeout: 10_000 });
}

test.describe("Library - backend e2e", () => {
  test("create, update and delete a library", async ({ page }) => {
    await loginAndGoToLibrary(page);

    const name = `pw-lib-${Date.now()}`;
    const updatedName = `${name}-updated`;

    // Create
    await page.locator("button").filter({ has: page.locator("svg[data-testid='AddOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(name);
    await page.getByRole("button", { name: /^create$/i }).click();
    const createdRow = page.locator(".MuiListItemButton-root").filter({ has: page.getByText(name, { exact: true }) }).first();
    await expect(createdRow).toBeVisible({ timeout: 10_000 });

    // Update
    await createdRow.click();
    await page.locator("button").filter({ has: page.locator("svg[data-testid='EditOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(updatedName);
    await page.getByRole("button", { name: /save/i }).click();
    const updatedRow = page.locator(".MuiListItemButton-root").filter({ has: page.getByText(updatedName, { exact: true }) }).first();
    await expect(updatedRow).toBeVisible({ timeout: 10_000 });

    // Delete
    await updatedRow.hover();
    await updatedRow.locator("button").last().click();
    await page.getByRole("button", { name: /delete/i }).click();

    await expect(updatedRow).toBeHidden({ timeout: 10_000 });
  });
});
