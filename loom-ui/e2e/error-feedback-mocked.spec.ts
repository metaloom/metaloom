import { test, expect, Page } from "@playwright/test";

/**
 * Mocked e2e for the failure path.
 *
 * The rule under test, recorded in `spec/loom/ui/LOOM_UI.md` §11.2: a failed write must be
 * distinguishable from a successful one. Before this existed, the sites below cleared their form
 * and closed their dialog outside the try/catch, so a rejected create looked exactly like an
 * accepted one and the user walked away believing in a row that does not exist.
 *
 * Every assertion here is about what the user can see: the toast, the dialog that stayed open,
 * the row that is still there, and the trace id they can quote.
 */

const USERNAME = "admin";
const TRACE_ID = "9f2c41ab7d0e4c6fa1b83e5d72c09148";

/** A 500 in the shape ServerFailureHandler actually produces, trace id and all. */
function serverError() {
  return {
    status: 500,
    contentType: "application/json",
    headers: { "X-Trace-Id": TRACE_ID },
    body: JSON.stringify({ message: "Internal Server Error", traceId: TRACE_ID }),
  };
}

async function mockBackend(page: Page) {
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
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill(USERNAME);
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Failure feedback – mocked", () => {
  test("a rejected tag create toasts and keeps the typed name", async ({ page }) => {
    await mockBackend(page);
    await page.route("**/api/v1/tags", route =>
      route.request().method() === "POST"
        ? route.fulfill(serverError())
        : route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }),
    );
    await login(page);

    await page.getByTestId("sidebar-item-/tags").click();
    const nameField = page.getByTestId("tag-new-name");
    await expect(nameField).toBeVisible({ timeout: 10_000 });
    await nameField.fill("doomed-tag");
    await page.getByTestId("tag-create-button").click();

    // (a) the failure is visible at all - this whole path used to be an unhandled promise rejection
    await expect(page.getByTestId("toast-report-failure")).toBeVisible({ timeout: 10_000 });
    // (b) the user's input survives, so a retry does not mean retyping
    await expect(nameField).toHaveValue("doomed-tag");
  });

  test("the toast offers a report, and the report carries the trace id", async ({ page }) => {
    await mockBackend(page);
    await page.route("**/api/v1/tags", route =>
      route.request().method() === "POST"
        ? route.fulfill(serverError())
        : route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }),
    );

    const submitted: Array<Record<string, unknown>> = [];
    await page.route("**/api/v1/failure-reports", async route => {
      submitted.push(JSON.parse(route.request().postData() ?? "{}"));
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({ uuid: "22222222-2222-2222-2222-222222222222", action: "createTag", triageStatus: "NEW" }),
      });
    });

    await login(page);
    await page.getByTestId("sidebar-item-/tags").click();
    await page.getByTestId("tag-new-name").fill("doomed-tag");
    await page.getByTestId("tag-create-button").click();

    await page.getByTestId("toast-report-failure").click();

    const dialog = page.getByTestId("failure-report-dialog");
    await expect(dialog).toBeVisible();
    // The trace id is shown, not hidden behind a "details" disclosure: it is the one value that
    // resolves the report against the server log, and a user may want to quote it elsewhere.
    await expect(page.getByTestId("failure-report-trace-id")).toContainText(TRACE_ID);
    await expect(page.getByTestId("failure-report-action")).toContainText("createTag");

    await page.getByTestId("failure-report-text").fill("I pressed create and nothing appeared.");
    await page.getByTestId("failure-report-submit").click();

    await expect(page.getByTestId("failure-report-submitted")).toBeVisible();
    expect(submitted).toHaveLength(1);
    expect(submitted[0]).toMatchObject({
      action: "createTag",
      traceId: TRACE_ID,
      statusCode: 500,
      text: "I pressed create and nothing appeared.",
    });
  });

  test("a failed load says it failed instead of saying it is empty", async ({ page }) => {
    await mockBackend(page);
    await page.route("**/api/v1/libraries**", route => route.fulfill(serverError()));
    await login(page);

    await page.getByTestId("sidebar-item-/library").click();

    // The distinction this test exists for: "could not load" is a statement about the request,
    // "no libraries yet" is a statement about the user's data, and only one of them is true here.
    await expect(page.getByTestId("library-load-failure")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("library-empty-state")).toBeHidden();
  });

  test("a rejected blacklist delete keeps the row", async ({ page }) => {
    const entry = {
      uuid: "33333333-3333-3333-3333-333333333333",
      name: "blocked-thing",
      status: { created: "2026-01-01T00:00:00Z" },
    };
    await mockBackend(page);
    await page.route("**/api/v1/blacklists**", route =>
      route.request().method() === "DELETE"
        ? route.fulfill(serverError())
        : route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [entry] }) }),
    );
    // Deep-link BEFORE signing in: auth is held in memory, so a goto after login would reload the
    // page straight back to the login form. Same pattern as db-integrity-mocked.spec.ts.
    await page.goto("/ui/admin/blacklist");
    await page.getByPlaceholder("Username").fill(USERNAME);
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

    const row = page.getByText("blocked-thing").first();
    await expect(row).toBeVisible({ timeout: 10_000 });

    await page.getByTestId(`blacklist-delete-${entry.uuid}`).click();

    await expect(page.getByTestId("toast-report-failure")).toBeVisible({ timeout: 10_000 });
    // The row is still there, because it is still in the database. A bare `.catch(() => {})` used
    // to leave the user believing the opposite.
    await expect(row).toBeVisible();
  });

  test("an expired session produces one message, not one per widget", async ({ page }) => {
    await mockBackend(page);
    await login(page);

    // Every subsequent call 401s - which is what an expired token looks like from the browser.
    await page.route("**/api/v1/**", route =>
      route.fulfill({
        status: 401,
        contentType: "application/json",
        headers: { "X-Trace-Id": TRACE_ID },
        body: JSON.stringify({ message: "Unauthorized", traceId: TRACE_ID }),
      }),
    );

    await page.getByTestId("sidebar-item-/assets").click();

    // Back at the login form, with exactly one explanation. The per-widget failure toasts are
    // suppressed for 401 on purpose: the session is the failure, not each screen that noticed it.
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/session has expired/i)).toHaveCount(1);
  });
});
