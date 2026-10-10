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

  for (const [label, answer] of [
    ["a server failure (500)", (route: import("@playwright/test").Route) => route.fulfill({ status: 500, body: "boom" })],
    ["a network failure", (route: import("@playwright/test").Route) => route.abort("connectionrefused")],
  ] as const) {
    test(`${label} says the server could not be reached, not that the password is wrong`, async ({ page }) => {
      await page.route("**/api/v1/login", answer);

      await page.goto("/");
      await page.getByPlaceholder("Username").fill("admin");
      await page.getByPlaceholder("Password").fill("finger");
      const button = page.getByRole("button", { name: /sign in/i });
      await button.click();

      await expect(page.getByTestId("login-error-unavailable")).toHaveText(
        "The server could not be reached. Please try again.",
      );
      await expect(page.getByTestId("login-error-invalidCredentials")).toHaveCount(0);
      // Still on the login form, ready for a retry.
      await expect(button).toBeEnabled();
      await expect(page.getByPlaceholder("Username")).toHaveValue("admin");
    });
  }

  test("a refusal after an outage replaces the outage message with invalid credentials", async ({ page }) => {
    let calls = 0;
    await page.route("**/api/v1/login", (route) => {
      calls += 1;
      return calls === 1
        ? route.abort("connectionrefused")
        : route.fulfill({ status: 401, body: "Unauthorized" });
    });

    await page.goto("/");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("wrong");
    const button = page.getByRole("button", { name: /sign in/i });
    await button.click();
    await expect(page.getByTestId("login-error-unavailable")).toBeVisible();

    await button.click();
    await expect(page.getByTestId("login-error-invalidCredentials")).toHaveText(/^Invalid credentials\.?$/);
    await expect(page.getByTestId("login-error-unavailable")).toHaveCount(0);
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

    // The login form is gone. (A "Loom Studio" check used to sit here wrapped in a .catch that
    // swallowed its failure, so it asserted nothing; the app header may carry that title too.)
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 5000 });
    await expect(page.getByPlaceholder("Password")).toBeHidden();
  });

  test("a second press while signing in does not send a second login", async ({ page }) => {
    let posts = 0;
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    await page.route("**/api/v1/login", async (route) => {
      posts += 1;
      await gate;
      return route.fulfill({ status: 401, body: "Unauthorized" });
    });

    await page.goto("/");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    const button = page.getByRole("button", { name: /sign in/i });
    await button.click();
    await expect(button).toBeDisabled();
    await page.getByPlaceholder("Password").press("Enter");

    release();
    await expect(page.getByText("Invalid credentials")).toBeVisible();
    expect(posts).toBe(1);
    // And the form is usable again for the retry.
    await expect(button).toBeEnabled();
  });
});
