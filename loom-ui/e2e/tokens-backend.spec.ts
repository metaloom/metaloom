import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for API Keys (tokens) admin CRUD.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Note: these tests are stateful and ordered — each depends on the prior test's mutation.
 */

async function loginAndGoToApiKeys(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to API Keys via sidebar
  // The ACL entries live in a collapsible sidebar sub-group that starts closed.
  await page.getByTestId("sidebar-group-acl").click();
  await page.getByRole("button", { name: "API Keys" }).first().click();
  await expect(page.getByRole("heading", { name: "API Keys" })).toBeVisible({ timeout: 10_000 });
}

test.describe("API Keys – full backend e2e", () => {
  test("create a new API key and verify it appears", async ({ page }) => {
    await loginAndGoToApiKeys(page);

    // Open the create dialog
    await page.getByRole("button", { name: /create key/i }).first().click();
    const dialog = page.getByRole("dialog");

    // Fill in the key name and submit (scoped to the dialog to avoid the header button)
    await dialog.getByLabel("Key name").fill("pw-test-key");
    await dialog.getByRole("button", { name: /^create key$/i }).click();

    // The freshly-created token is revealed; close the dialog
    await dialog.getByRole("button", { name: /close/i }).click();

    // The new key should appear in the table
    await expect(page.getByText("pw-test-key", { exact: true })).toBeVisible({ timeout: 10_000 });
  });

  test("rename an API key", async ({ page }) => {
    await loginAndGoToApiKeys(page);
    await expect(page.getByText("pw-test-key", { exact: true })).toBeVisible({ timeout: 10_000 });

    // Open the kebab menu on the pw-test-key row
    const row = page.getByRole("row").filter({ hasText: "pw-test-key" });
    await row.getByRole("button").first().click();

    // Click "Rename Key" in the menu
    await page.getByRole("menuitem", { name: /rename key/i }).click();

    // Update the name in the edit dialog
    const nameField = page.getByLabel("Key name");
    await nameField.clear();
    await nameField.fill("pw-test-key-renamed");

    // Save
    await page.getByRole("button", { name: /^save$/i }).click();

    // Verify the updated name renders in the table
    await expect(page.getByText("pw-test-key-renamed", { exact: true })).toBeVisible({ timeout: 10_000 });
  });

  test("delete an API key", async ({ page }) => {
    await loginAndGoToApiKeys(page);
    await expect(page.getByText("pw-test-key-renamed", { exact: true })).toBeVisible({ timeout: 10_000 });

    // Open the kebab menu on the renamed row
    const row = page.getByRole("row").filter({ hasText: "pw-test-key-renamed" });
    await row.getByRole("button").first().click();

    // Click "Delete Key" in the menu
    await page.getByRole("menuitem", { name: /delete key/i }).click();

    // The key should disappear from the list
    await expect(page.getByText("pw-test-key-renamed", { exact: true })).toBeHidden({ timeout: 10_000 });
  });
});
