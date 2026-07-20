import { test, expect, Page } from "@playwright/test";

async function loginAndGoToClusters(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  // Navigate to detection page. Clusters is the default section.
  await page.getByRole("button", { name: /detection/i }).first().click();
  await page.getByText(/clusters/i).first().click();
  await expect(page.getByText(/clusters/i).first()).toBeVisible({ timeout: 10_000 });
}

test.describe("Clusters - backend e2e", () => {
  test("create, update and delete a cluster", async ({ page }) => {
    await loginAndGoToClusters(page);

    const name = `pw-cluster-${Date.now()}`;
    const updatedName = `${name}-updated`;

    // Create
    await page.getByRole("button", { name: /add cluster/i }).click();
    await page.getByLabel(/name/i).fill(name);
    await page.getByRole("button", { name: /^create$/i }).click();

    // Verify created cluster is visible
    const clusterCard = page.locator("div").filter({ hasText: name }).first();
    await expect(clusterCard).toBeVisible({ timeout: 10_000 });

    // Update - click edit button on the cluster card
    await clusterCard.locator("button").filter({ has: page.locator("svg[data-testid='EditOutlinedIcon']") }).first().click();
    await page.getByLabel(/name/i).fill(updatedName);
    await page.getByRole("button", { name: /save/i }).click();

    const updatedCard = page.locator("div").filter({ hasText: updatedName }).first();
    await expect(updatedCard).toBeVisible({ timeout: 10_000 });

    // Delete
    await updatedCard.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
    await expect(updatedCard).toBeHidden({ timeout: 10_000 });
  });
});
