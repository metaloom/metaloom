import { test, expect } from "@playwright/test";

/**
 * Basic e2e smoke test for the login page.
 *
 * This test only validates the UI form behaviour — it does NOT require a
 * running Loom backend.  The actual POST /api/v1/login call is intercepted
 * via Playwright's route API so we can assert form mechanics in isolation.
 */
test.describe("Login page", () => {
  test("renders the login form", async ({ page }) => {
    await page.goto("/");

    // The login form should be visible when unauthenticated
    await expect(page.getByText("Loom Studio")).toBeVisible();
    await expect(page.getByPlaceholder("Username")).toBeVisible();
    await expect(page.getByPlaceholder("Password")).toBeVisible();
    await expect(page.getByRole("button", { name: /sign in/i })).toBeVisible();
  });

  test("shows error on invalid credentials", async ({ page }) => {
    // Intercept the login API call and return 401
    await page.route("**/api/v1/login", (route) =>
      route.fulfill({ status: 401, body: "Unauthorized" })
    );

    await page.goto("/");
    await page.getByPlaceholder("Username").fill("bad-user");
    await page.getByPlaceholder("Password").fill("bad-pass");
    await page.getByRole("button", { name: /sign in/i }).click();

    await expect(page.getByText("Invalid credentials")).toBeVisible();
  });

  test("successful login navigates away from login page", async ({ page }) => {
    // Intercept the login API call and return a valid token
    await page.route("**/api/v1/login", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: "fake-jwt-token" }),
      })
    );

    await page.goto("/");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();

    // After login the "Sign in" heading should disappear
    await expect(page.getByText("Loom Studio")).toBeHidden({ timeout: 5000 }).catch(() => {
      // On success, the page navigates to the main app — "Loom Studio" title
      // may remain in the header, so alternatively check that the login form is gone
    });
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 5000 });
  });
});
