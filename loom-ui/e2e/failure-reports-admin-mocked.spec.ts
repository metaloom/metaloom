import { test, expect, Page } from "@playwright/test";

/**
 * Mocked e2e for the problem-report inbox.
 *
 * The read half of the failure path. Without it the Report button would write to a table nobody
 * ever opens, which is worse than not collecting reports at all - it tells the user somebody is
 * listening.
 */

const USERNAME = "admin";
const TRACE_ID = "9f2c41ab7d0e4c6fa1b83e5d72c09148";
const REPORT_UUID = "44444444-4444-4444-4444-444444444444";

const report = {
  uuid: REPORT_UUID,
  action: "createPerson",
  traceId: TRACE_ID,
  httpMethod: "POST",
  path: "/api/v1/persons",
  statusCode: 500,
  errorMessage: "Internal Server Error",
  route: "/detection",
  text: "The dialog closed but the person is not in the list.",
  triageStatus: "NEW",
  hasScreenshot: true,
  screenshotUrl: `http://localhost/api/v1/failure-reports/${REPORT_UUID}/screenshot`,
  status: { created: "2026-08-18T10:00:00Z" },
};

async function mockBackend(page: Page, opts: { reports?: unknown[] } = {}) {
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }),
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) }),
  );
  await page.route("**/api/v1/me", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ uuid: "11111111-1111-1111-1111-111111111111", username: USERNAME, enabled: true }),
    }),
  );
  await page.route("**/api/v1/failure-reports**", async route => {
    const method = route.request().method();
    if (method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ data: opts.reports ?? [report] }),
      });
      return;
    }
    if (method === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ...report, triageStatus: body.triageStatus }),
      });
      return;
    }
    await route.fulfill({ status: 204, body: "" });
  });
}

async function open(page: Page) {
  // Deep-link then sign in: auth is in memory, so a goto after login lands back on the login form.
  await page.goto("/ui/admin/failure-reports");
  await page.getByPlaceholder("Username").fill(USERNAME);
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Problem report inbox – mocked", () => {
  test("a report shows its trace id, its request and what the user said", async ({ page }) => {
    await mockBackend(page);
    await open(page);

    const row = page.getByTestId(`failure-report-row-${REPORT_UUID}`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    // The trace id is the value an operator carries over to the server log, so it leads the row.
    await expect(row).toContainText(TRACE_ID);
    await expect(row).toContainText("createPerson");
    await expect(row).toContainText("POST /api/v1/persons");
    await expect(row).toContainText("HTTP 500");
    await expect(row).toContainText("The dialog closed but the person is not in the list.");
  });

  test("triage moves the report through its states", async ({ page }) => {
    await mockBackend(page);
    await open(page);

    const row = page.getByTestId(`failure-report-row-${REPORT_UUID}`);
    await expect(row).toContainText("New");

    await row.getByRole("button", { name: /acknowledge/i }).click();
    await expect(row).toContainText("Acknowledged");
  });

  test("the screenshot opens full size rather than being inlined into the list", async ({ page }) => {
    await mockBackend(page);
    await open(page);

    // Nothing in the listing carries image bytes - that is why the screenshot lives in its own
    // table and behind its own route.
    await page.getByTestId(`failure-report-screenshot-${REPORT_UUID}`).click();
    await expect(page.getByTestId("failure-report-screenshot-dialog")).toBeVisible();
  });

  test("an empty inbox says so rather than rendering a blank table", async ({ page }) => {
    await mockBackend(page, { reports: [] });
    await open(page);

    await expect(page.getByTestId("failure-reports-empty-state")).toBeVisible({ timeout: 10_000 });
  });

  test("a failed load says it failed instead of saying the inbox is empty", async ({ page }) => {
    await mockBackend(page);
    await page.route("**/api/v1/failure-reports**", route =>
      route.fulfill({
        status: 500,
        contentType: "application/json",
        headers: { "X-Trace-Id": TRACE_ID },
        body: JSON.stringify({ message: "Internal Server Error", traceId: TRACE_ID }),
      }),
    );
    await open(page);

    await expect(page.getByTestId("failure-reports-load-failure")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("failure-reports-empty-state")).toBeHidden();
  });
});
