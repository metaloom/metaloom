import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Spaces admin CRUD.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

async function loginAndGoToSpaces(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Spaces via sidebar
  await page.getByRole("button", { name: "Spaces" }).first().click();
  await expect(page.getByRole("heading", { name: "Spaces" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Spaces – full backend e2e", () => {
  test("space list loads", async ({ page }) => {
    await loginAndGoToSpaces(page);

    // The table should be visible with header columns
    await expect(page.getByRole("columnheader", { name: "Name" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "UUID" })).toBeVisible();
  });

  test("create a new space and verify it appears", async ({ page }) => {
    await loginAndGoToSpaces(page);

    // Click "New Space" button
    await page.getByRole("button", { name: /new space/i }).click();

    // Fill in the create dialog
    await page.getByLabel("Space name").fill("pw-test-space");

    // Submit
    await page.getByRole("button", { name: /create space/i }).click();

    // The new space should appear in the table
    await expect(page.getByText("pw-test-space")).toBeVisible({ timeout: 10_000 });
  });

  test("edit a space", async ({ page }) => {
    await loginAndGoToSpaces(page);
    await expect(page.getByText("pw-test-space")).toBeVisible({ timeout: 10_000 });

    // Click edit button on the pw-test-space row
    const row = page.getByRole("row").filter({ hasText: "pw-test-space" });
    await row.getByRole("button").first().click();

    // Update the name in the edit dialog
    const nameField = page.getByLabel("Space name");
    await nameField.clear();
    await nameField.fill("pw-test-space-updated");

    // Save
    await page.getByRole("button", { name: /save/i }).click();

    // Verify the updated name appears
    await expect(page.getByText("pw-test-space-updated")).toBeVisible({ timeout: 10_000 });
  });

  test("delete a space", async ({ page }) => {
    await loginAndGoToSpaces(page);
    await expect(page.getByText("pw-test-space-updated")).toBeVisible({ timeout: 10_000 });

    // Click delete button on the pw-test-space-updated row
    const row = page.getByRole("row").filter({ hasText: "pw-test-space-updated" });
    const buttons = row.getByRole("button");
    await buttons.last().click();

    // Confirm deletion
    await page.getByRole("button", { name: /delete/i }).click();

    // The space should disappear
    await expect(page.getByText("pw-test-space-updated")).toBeHidden({ timeout: 10_000 });
  });
});
