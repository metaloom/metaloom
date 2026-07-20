import { test, expect, Page } from "@playwright/test";

/**
 * Smoke tests for the Maintenance view, which renders live service state from
 * `GET /api/v1/health`.
 *
 * No running Loom backend is required: the REST API is intercepted via
 * `page.route`. A catch-all returns empty lists for everything, then `login`
 * and `health` are overridden with concrete payloads. The `/maintenance` route
 * has no sidebar entry, so we navigate to it directly; the AuthGate shows the
 * login form while the URL stays `/maintenance`, and on a successful (mocked)
 * login the gate re-renders and routes the still-current URL to the view.
 */

interface HealthPayload {
  status: string;
  version?: string;
  database?: string;
  timestamp?: string;
}

async function mockApi(page: Page, health: HealthPayload) {
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/health", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(health) })
  );
}

async function loginAndOpenMaintenance(page: Page) {
  await page.goto("/maintenance");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Maintenance view – mocked", () => {

  test("renders version and healthy database from the /health response", async ({ page }) => {
    await mockApi(page, {
      status: "UP",
      version: "9.9.9-test",
      database: "UP",
      timestamp: "2026-07-20T12:34:56.789Z",
    });
    await loginAndOpenMaintenance(page);

    await expect(page.getByTestId("health-version")).toContainText("9.9.9-test", { timeout: 10_000 });
    await expect(page.getByTestId("health-overall-status")).toHaveText("Operational");
    await expect(page.getByTestId("health-status-database")).toHaveText("Healthy");
  });

  test("degrades gracefully when the database is DOWN", async ({ page }) => {
    await mockApi(page, {
      status: "DEGRADED",
      version: "9.9.9-test",
      database: "DOWN",
      timestamp: "2026-07-20T12:34:56.789Z",
    });
    await loginAndOpenMaintenance(page);

    await expect(page.getByTestId("health-overall-status")).toHaveText("Degraded", { timeout: 10_000 });
    const dbStatus = page.getByTestId("health-status-database");
    await expect(dbStatus).toHaveText("Unavailable");
    await expect(dbStatus).not.toHaveText("Healthy");
  });
});
