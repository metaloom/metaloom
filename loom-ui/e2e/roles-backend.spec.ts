import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Roles / Permissions admin CRUD.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

async function loginAndGoToPermissions(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Permissions via sidebar
  await page.getByRole("button", { name: "Permissions" }).first().click();
  await expect(page.getByRole("heading", { name: "Access Control" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Roles – full backend e2e", () => {
  test("role list loads and displays demo roles", async ({ page }) => {
    await loginAndGoToPermissions(page);

    // Demo roles should be present in the sidebar
    await expect(page.getByText("Editor")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Viewer")).toBeVisible({ timeout: 10_000 });
  });

  test("create a new role and verify it appears", async ({ page }) => {
    await loginAndGoToPermissions(page);
    await expect(page.getByText("Editor")).toBeVisible({ timeout: 10_000 });

    // Click "New Role" button
    await page.getByRole("button", { name: /new role/i }).click();

    // Fill in the create dialog
    await page.getByLabel("Role name").fill("pw-test-role");

    // Submit
    await page.getByRole("button", { name: /create/i }).click();

    // The new role should appear in the role list
    await expect(page.getByText("pw-test-role")).toBeVisible({ timeout: 10_000 });
  });

  test("select a role and view permissions", async ({ page }) => {
    await loginAndGoToPermissions(page);
    await expect(page.getByText("Editor")).toBeVisible({ timeout: 10_000 });

    // Click on the Editor role
    await page.getByText("Editor").click();

    // The permission tree should show resource groups
    await expect(page.getByText("permissions granted")).toBeVisible({ timeout: 5_000 });
  });

  test("delete a role", async ({ page }) => {
    await loginAndGoToPermissions(page);
    await expect(page.getByText("pw-test-role")).toBeVisible({ timeout: 10_000 });

    // Find the delete button next to pw-test-role
    const roleItem = page.getByText("pw-test-role").first();
    await roleItem.hover();

    // Click the delete icon button (small trash icon next to the role)
    const container = roleItem.locator("..").locator("..");
    const deleteBtn = container.getByRole("button").last();
    await deleteBtn.click();

    // Confirm deletion in dialog
    await page.getByRole("button", { name: /delete/i }).last().click();

    // Role should disappear
    await expect(page.getByText("pw-test-role")).toBeHidden({ timeout: 10_000 });
  });
});
