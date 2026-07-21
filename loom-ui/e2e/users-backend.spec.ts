import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Users admin CRUD.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

async function loginAndGoToUsers(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Users via sidebar
  await page.getByRole("button", { name: "Users" }).first().click();
  await expect(page.getByRole("heading", { name: "Users" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Users – full backend e2e", () => {
  test("user list loads and displays users", async ({ page }) => {
    await loginAndGoToUsers(page);

    // Wait for the table to load — at least admin user should be present
    await expect(page.getByText("admin", { exact: true })).toBeVisible({ timeout: 10_000 });
  });

  test("create a new user and verify it appears", async ({ page }) => {
    await loginAndGoToUsers(page);
    await expect(page.getByText("admin", { exact: true })).toBeVisible({ timeout: 10_000 });

    // Click "Create User" button
    await page.getByRole("button", { name: /create user/i }).click();

    // Fill in the create dialog
    await page.getByLabel("Username").fill("pw-test-user");
    await page.getByLabel("Email").fill("pw@example.com");
    await page.getByLabel("Firstname").fill("Play");
    await page.getByLabel("Lastname").fill("Wright");

    // Submit
    await page.getByRole("button", { name: /create/i }).click();

    // The new user should appear in the table
    await expect(page.getByText("pw-test-user")).toBeVisible({ timeout: 10_000 });
  });

  test("edit a user and persist a changed field", async ({ page }) => {
    await loginAndGoToUsers(page);
    await expect(page.getByText("pw-test-user")).toBeVisible({ timeout: 10_000 });

    // Click on the pw-test-user row to open the edit dialog
    const row = page.getByRole("row").filter({ hasText: "pw-test-user" });
    await row.getByRole("button").first().click();

    // Verify the edit dialog opens with the username field
    await expect(page.getByLabel("Username")).toHaveValue("pw-test-user", { timeout: 5_000 });

    // Change the firstname and save
    await page.getByLabel("Firstname").fill("Edited");
    await page.getByRole("button", { name: /save/i }).click();

    // The user should still be visible in the table
    await expect(page.getByText("pw-test-user")).toBeVisible({ timeout: 10_000 });

    // Reopen the row and assert the changed firstname was persisted
    await page.getByRole("row").filter({ hasText: "pw-test-user" }).getByRole("button").first().click();
    await expect(page.getByLabel("Firstname")).toHaveValue("Edited", { timeout: 5_000 });
  });

  test("delete a user", async ({ page }) => {
    await loginAndGoToUsers(page);
    await expect(page.getByText("pw-test-user")).toBeVisible({ timeout: 10_000 });

    // Click delete button on the pw-test-user row
    const row = page.getByRole("row").filter({ hasText: "pw-test-user" });
    const buttons = row.getByRole("button");
    await buttons.last().click();

    // Confirm deletion
    await page.getByRole("button", { name: /delete/i }).click();

    // Wait for the confirmation dialog to close
    await expect(page.getByLabel('Delete User')).toBeHidden({ timeout: 10_000 });

    // The user should disappear
    await expect(page.getByText("pw-test-user")).toBeHidden({ timeout: 10_000 });
  });
});
