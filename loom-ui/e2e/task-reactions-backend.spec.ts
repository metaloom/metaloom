import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for task reactions (real backend).
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

test.describe("Task reactions – backend e2e", () => {
  test("can add a reaction to a task and delete it", async ({ page }) => {
    await loginAndGoToTasks(page);

    const unique = Date.now();
    const title = `E2E reaction task ${unique}`;

    // Create a task to react to.
    await page.getByTestId("tasks-create-button").click();
    await page.getByTestId("tasks-title-input").fill(title);
    await page.getByTestId("tasks-create-submit-button").click();
    await expect(page.getByText(title)).toBeVisible({ timeout: 10_000 });

    // Open its detail drawer.
    await page.getByText(title).click();
    await expect(page.getByRole("heading", { name: title })).toBeVisible({ timeout: 10_000 });

    // No reactions yet.
    await expect(page.getByTestId("tasks-reaction-chip")).toHaveCount(0);

    // Add a THUMBSUP reaction.
    await page.getByTestId("tasks-react-button").click();
    await page.getByTestId("tasks-react-option-THUMBSUP").click();

    // The reaction chip appears and round-trips through the backend.
    const chip = page.getByTestId("tasks-reaction-chip");
    await expect(chip).toHaveCount(1, { timeout: 10_000 });
    await expect(chip.first()).toContainText("Thumbs up");

    // Delete the reaction (the delete affordance is only rendered for reactions
    // authored by the current user, so its presence also proves identity resolution).
    await chip.first().getByTestId("tasks-reaction-delete").click();
    await expect(page.getByTestId("tasks-reaction-chip")).toHaveCount(0, { timeout: 10_000 });

    // Cleanup: delete the task.
    await page.getByTestId("tasks-delete-button").click();
    await page.getByTestId("tasks-delete-confirm-button").click();
    await expect(page.locator("tbody tr").filter({ hasText: title })).toHaveCount(0, { timeout: 10_000 });
  });
});
