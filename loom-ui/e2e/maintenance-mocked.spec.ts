import { test, expect, Page } from "@playwright/test";

/**
 * Smoke tests for the Maintenance view, which renders live service state from
 * `GET /api/v1/health` plus authoritative instance/system info from `GET /api/v1`.
 *
 * No running Loom backend is required: the REST API is intercepted via
 * `page.route`. A catch-all returns empty lists for everything, then `login`,
 * `health` and the instance-info route are overridden with concrete payloads.
 * The `/maintenance` route has no sidebar entry, so we navigate to it directly;
 * the AuthGate shows the login form while the URL stays `/maintenance`, and on a
 * successful (mocked) login the gate re-renders and routes the still-current URL
 * to the view.
 */

interface HealthPayload {
  status: string;
  version?: string;
  database?: string;
  timestamp?: string;
}

interface InfoPayload {
  version: string;
  dbRevision?: string;
  lastUsed?: string;
}

async function mockApi(page: Page, health: HealthPayload, info?: InfoPayload) {
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/health", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(health) })
  );
  // Instance info is mounted at the API base path itself (GET /api/v1, no sub-path),
  // which the `**/api/v1/**` catch-all does NOT match — so it needs its own route.
  await page.route("**/api/v1", route =>
    info
      ? route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(info) })
      : route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "no info" }) })
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

  test("renders DB revision and last-used from the instance-info response", async ({ page }) => {
    await mockApi(
      page,
      { status: "UP", version: "9.9.9-test", database: "UP", timestamp: "2026-07-20T12:34:56.789Z" },
      { version: "9.9.9-test", dbRevision: "V2.5", lastUsed: "2026-07-19T08:15:00" },
    );
    await loginAndOpenMaintenance(page);

    await expect(page.getByTestId("info-db-revision")).toContainText("V2.5", { timeout: 10_000 });
    await expect(page.getByTestId("info-last-used")).toContainText("Last used:");
  });

  test("omits instance-info chips when the info endpoint fails", async ({ page }) => {
    // No info payload → the mocked info route returns 500; the chips must not render,
    // and the overall health state stays Operational (info failure is non-critical).
    await mockApi(page, {
      status: "UP",
      version: "9.9.9-test",
      database: "UP",
      timestamp: "2026-07-20T12:34:56.789Z",
    });
    await loginAndOpenMaintenance(page);

    await expect(page.getByTestId("health-overall-status")).toHaveText("Operational", { timeout: 10_000 });
    await expect(page.getByTestId("info-db-revision")).toHaveCount(0);
    await expect(page.getByTestId("info-last-used")).toHaveCount(0);
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
