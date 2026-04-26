import { test, expect, Page } from "@playwright/test";

async function loginAndGoToPersons(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  // Navigate to detection page and switch to persons tab
  await page.getByRole("button", { name: /detection/i }).first().click();
  await page.getByText(/persons/i).first().click();
  await expect(page.getByText(/persons/i).first()).toBeVisible({ timeout: 10_000 });
}

test.describe("Persons - backend e2e", () => {
  test("create, update and delete a person", async ({ page }) => {
    await loginAndGoToPersons(page);

    const alias = `pw-person-${Date.now()}`;
    const updatedAlias = `${alias}-updated`;

    // Create
    await page.getByRole("button", { name: /add person/i }).click();
    await page.getByLabel(/alias/i).fill(alias);
    await page.getByLabel(/first name/i).fill("Test");
    await page.getByLabel(/last name/i).fill("User");
    await page.getByRole("button", { name: /^create$/i }).click();

    // Verify created person is visible
    const personCard = page.locator("div").filter({ hasText: alias }).first();
    await expect(personCard).toBeVisible({ timeout: 10_000 });

    // Update - click edit button on the person card
    await personCard.locator("button").filter({ has: page.locator("svg[data-testid='EditOutlinedIcon']") }).first().click();
    await page.getByLabel(/alias/i).fill(updatedAlias);
    await page.getByRole("button", { name: /save/i }).click();

    const updatedCard = page.locator("div").filter({ hasText: updatedAlias }).first();
    await expect(updatedCard).toBeVisible({ timeout: 10_000 });

    // Delete
    await updatedCard.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
    await expect(updatedCard).toBeHidden({ timeout: 10_000 });
  });
});
