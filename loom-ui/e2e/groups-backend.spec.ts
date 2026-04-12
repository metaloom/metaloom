import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Groups admin CRUD.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

async function loginAndGoToGroups(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Groups via sidebar
  await page.getByRole("button", { name: "Groups" }).first().click();
  await expect(page.getByRole("heading", { name: "Groups" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Groups – full backend e2e", () => {
  test("group list loads and displays demo groups", async ({ page }) => {
    await loginAndGoToGroups(page);

    // Wait for the table to load — demo groups should be present
    await expect(page.getByText("Editors")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Viewers")).toBeVisible({ timeout: 10_000 });
  });

  test("create a new group and verify it appears", async ({ page }) => {
    await loginAndGoToGroups(page);
    await expect(page.getByText("Editors")).toBeVisible({ timeout: 10_000 });

    // Click "New Group" button
    await page.getByRole("button", { name: /new group/i }).click();

    // Fill in the create dialog
    await page.getByLabel("Group name").fill("pw-test-group");

    // Submit
    await page.getByRole("button", { name: /create/i }).click();

    // The new group should appear in the table
    await expect(page.getByText("pw-test-group")).toBeVisible({ timeout: 10_000 });
  });

  test("edit a group", async ({ page }) => {
    await loginAndGoToGroups(page);
    await expect(page.getByText("pw-test-group")).toBeVisible({ timeout: 10_000 });

    // Click edit button on the pw-test-group row
    const row = page.getByRole("row").filter({ hasText: "pw-test-group" });
    await row.getByRole("button").first().click();

    // Update the name in the edit dialog
    const nameField = page.getByLabel("Group name");
    await nameField.clear();
    await nameField.fill("pw-test-group-updated");

    // Save
    await page.getByRole("button", { name: /save/i }).click();

    // Verify the updated name appears
    await expect(page.getByText("pw-test-group-updated")).toBeVisible({ timeout: 10_000 });
  });

  test("delete a group", async ({ page }) => {
    await loginAndGoToGroups(page);
    await expect(page.getByText("pw-test-group-updated")).toBeVisible({ timeout: 10_000 });

    // Click delete button on the row
    const row = page.getByRole("row").filter({ hasText: "pw-test-group-updated" });
    const buttons = row.getByRole("button");
    await buttons.last().click();

    // Confirm deletion
    await page.getByRole("button", { name: /delete/i }).click();

    // Wait for the confirmation dialog to close
    await expect(page.getByLabel('Delete Group')).toBeHidden({ timeout: 10_000 });

    // The group should disappear from the list
    await expect(page.getByText("pw-test-group-updated")).toBeHidden({ timeout: 10_000 });
  });
});
