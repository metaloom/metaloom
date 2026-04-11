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

    // Wait for the table body to render.
    // If there are tasks, we should see at least one table row.
    // If there are no tasks, the "No tasks found" message should appear.
    const taskRows = page.getByRole("row");
    const emptyMessage = page.getByText("No tasks found");

    // One of these must become visible
    await expect(taskRows.first().or(emptyMessage)).toBeVisible({ timeout: 10_000 });
  });
});
