import { test, expect } from "@playwright/test";

/**
 * Full end-to-end login test.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. http://localhost:8092/api/v1)
 *
 * This test assumes:
 *  1. A Loom server is running (e.g. via LoomServerRunner or the integration-test harness)
 *  2. The default admin credentials are  admin / finger
 *  3. The loom-ui Vite dev server is running with VITE_API_BASE_URL configured
 *
 * Run:
 *   VITE_API_BASE_URL=http://localhost:8092/api/v1 npm run test:e2e -- e2e/login-backend.spec.ts
 */
test.describe("Login – full backend e2e", () => {
  test("login with valid admin credentials succeeds", async ({ page }) => {
    await page.goto("/");

    // Fill in the login form
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();

    // After successful login, the login form should disappear
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  });

  test("login with wrong credentials shows error", async ({ page }) => {
    await page.goto("/");

    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("wrong-password");
    await page.getByRole("button", { name: /sign in/i }).click();

    await expect(page.getByText("Invalid credentials")).toBeVisible({ timeout: 5_000 });
    // Login form should still be present
    await expect(page.getByPlaceholder("Username")).toBeVisible();
  });
});
