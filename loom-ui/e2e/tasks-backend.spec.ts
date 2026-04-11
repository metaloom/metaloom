import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Tasks view (real backend).
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

async function loginAndGoToTasks(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate to Tasks via sidebar
  await page.getByRole("button", { name: "Tasks" }).first().click();
  await expect(page.getByRole("heading", { name: "Tasks" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Tasks – backend e2e", () => {
  test("tasks view loads and displays header", async ({ page }) => {
    await loginAndGoToTasks(page);

    // The table should be visible with header columns
    await expect(page.getByRole("columnheader", { name: "Task" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Priority" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Created" })).toBeVisible();
  });

  test("tasks are loaded from backend API", async ({ page }) => {
    await loginAndGoToTasks(page);

    // If there are tasks, at least one tbody row should be visible.
    // If there are no tasks, the empty state should be visible.
    const taskRows = page.locator("tbody tr");
    const emptyMessage = page.getByText("No tasks found");

    await expect.poll(async () => await taskRows.count(), { timeout: 10_000 }).toBeGreaterThanOrEqual(0);
    if (await taskRows.count()) {
      await expect(taskRows.first()).toBeVisible();
    } else {
      await expect(emptyMessage).toBeVisible({ timeout: 10_000 });
    }
  });

  test("can create, update and delete a task", async ({ page }) => {
    await loginAndGoToTasks(page);

    const unique = Date.now();
    const createdTitle = `E2E task ${unique}`;
    const updatedTitle = `E2E task updated ${unique}`;

    // Create
    await page.getByTestId("tasks-create-button").click();
    await page.getByTestId("tasks-title-input").fill(createdTitle);
    await page.getByTestId("tasks-description-input").fill("Created by Playwright e2e");
    await page.getByTestId("tasks-create-submit-button").click();
    await expect(page.getByText(createdTitle)).toBeVisible({ timeout: 10_000 });

    // Edit
    await page.getByText(createdTitle).click();
    await page.getByTestId("tasks-edit-button").click();
    await page.getByTestId("tasks-edit-title-input").fill(updatedTitle);
    await page.getByTestId("tasks-save-button").click();
    await expect(page.getByRole("heading", { name: updatedTitle })).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("tbody tr").filter({ hasText: updatedTitle }).first()).toBeVisible({ timeout: 10_000 });

    // Delete
    await page.getByTestId("tasks-delete-button").click();
    await page.getByTestId("tasks-delete-confirm-button").click();
    await expect(page.locator("tbody tr").filter({ hasText: updatedTitle })).toHaveCount(0, { timeout: 10_000 });
  });
});
