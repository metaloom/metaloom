import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the database integrity admin screen (`/admin/db-integrity`).
 *
 * No running Loom backend: the REST API is intercepted via `page.route`. What is under test is the
 * screen's honesty rather than its layout. This panel exists to answer "is anything broken", so the
 * ways it could mislead are specific and worth pinning: reading clean when a check could not run,
 * blanking itself when a refresh fails, or showing a sample list that silently disagrees with the
 * row count beside it.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function check(code: string, category: string, severity: string, table: string, column: string | null) {
  return { code, category, severity, table, column, description: `What ${code} means and why it matters.` };
}

const DANGLING = check("DANGLING_SEARCH_DOCUMENT", "DANGLING", "ERROR", "search_document", "entity_uuid");
const TIMESTAMPS = check("TIMESTAMP_EDITED_BEFORE_CREATED", "TIMESTAMP", "ERROR", "(every audited table)", "edited");
const BLACKLIST = check("MISSING_BLACKLIST_NAME", "MANDATORY_FIELD", "WARN", "blacklist", "name");
const SINGLETON = check("LOOM_SINGLETON", "CARDINALITY", "ERROR", "loom", null);

function result(c: ReturnType<typeof check>, count: number, samples: string[] = [], error: string | null = null) {
  return { check: c, count, samples, durationMs: 3, error };
}

function report(results: ReturnType<typeof result>[]) {
  const findingCount = results.reduce((sum, r) => sum + r.count, 0);
  return {
    timestamp: "2026-08-09T11:24:07Z",
    durationMs: 312,
    checksRun: results.length,
    findingCount,
    clean: results.every(r => r.count === 0 && !r.error),
    results,
  };
}

const CLEAN = report([result(DANGLING, 0), result(TIMESTAMPS, 0), result(BLACKLIST, 0), result(SINGLETON, 0)]);

const DIRTY = report([
  result(DANGLING, 42, ["6c1f7b1e-0d0a-4b3a-9f7c-2f1d3c4b5a60 (entity_type=asset)"]),
  result(TIMESTAMPS, 0),
  result(BLACKLIST, 1, ["8a2e9c4d-77b1-42aa-9d31-5b6c7d8e9f01 (name is null)"]),
  result(SINGLETON, 0),
]);

async function installMocks(page: Page) {
  // Registered first so the specific routes below win - Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
}

async function open(page: Page) {
  // The app is mounted under /ui/, and auth is in memory - deep-link first, then sign in.
  await page.goto("/ui/admin/db-integrity");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Database integrity admin - mocked", () => {

  test("a clean database says so, and says how much was checked", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, CLEAN));
    await open(page);

    await expect(page.getByTestId("db-integrity-admin")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("db-integrity-clean")).toBeVisible();
    // "Nothing found" is only reassuring if it also says what was looked at.
    await expect(page.getByTestId("db-integrity-ran")).toContainText("4");
    await expect(page.getByTestId("db-integrity-count-error")).toHaveText("0 errors");
  });

  test("findings are grouped by category and counted by severity", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, DIRTY));
    await open(page);

    await expect(page.getByTestId("db-integrity-count-error")).toHaveText("1 errors", { timeout: 10_000 });
    await expect(page.getByTestId("db-integrity-count-warn")).toHaveText("1 warnings");

    await expect(page.getByTestId("db-integrity-group-DANGLING")).toBeVisible();
    await expect(page.getByTestId("db-integrity-group-MANDATORY_FIELD")).toBeVisible();

    // A category with nothing wrong in it is not rendered: an empty heading is a heading and no
    // information, and the summary already says how many checks ran.
    await expect(page.getByTestId("db-integrity-group-TIMESTAMP")).toHaveCount(0);
    await expect(page.getByTestId("db-integrity-group-CARDINALITY")).toHaveCount(0);
  });

  test("a passing check is not listed among the findings", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, DIRTY));
    await open(page);

    await expect(page.getByTestId("db-integrity-row-DANGLING_SEARCH_DOCUMENT")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("db-integrity-row-TIMESTAMP_EDITED_BEFORE_CREATED")).toHaveCount(0);
  });

  test("expanding a finding names the rows and admits how many it is not showing", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, DIRTY));
    await open(page);

    await page.getByTestId("db-integrity-toggle-DANGLING_SEARCH_DOCUMENT").click();
    const samples = page.getByTestId("db-integrity-samples-DANGLING_SEARCH_DOCUMENT");
    await expect(samples).toContainText("6c1f7b1e-0d0a-4b3a-9f7c-2f1d3c4b5a60");
    // 42 rows, one sample. Saying nothing about the other 41 would make the list look complete.
    await expect(samples).toContainText("41");
  });

  test("a check that could not run does not read as a passing check", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, report([
      result(DANGLING, 0, [], 'PSQLException: column "entity_uuid" does not exist'),
      result(TIMESTAMPS, 0),
    ])));
    await open(page);

    // Count 0 with an error means "unknown", not "clean" - this is the one way the screen could
    // actively mislead someone, so it gets its own test.
    await expect(page.getByTestId("db-integrity-clean")).toHaveCount(0, { timeout: 10_000 });
    await expect(page.getByTestId("db-integrity-severity-DANGLING_SEARCH_DOCUMENT")).toHaveText("Did not run");
    await expect(page.getByTestId("db-integrity-count-DANGLING_SEARCH_DOCUMENT")).toHaveText("-");
  });

  test("a failed refresh warns without blanking the last report", async ({ page }) => {
    await installMocks(page);
    let calls = 0;
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => {
      calls += 1;
      return calls === 1 ? json(route, DIRTY) : json(route, { message: "boom" }, 500);
    });
    await open(page);

    await expect(page.getByTestId("db-integrity-row-DANGLING_SEARCH_DOCUMENT")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("db-integrity-run").click();

    await expect(page.getByTestId("db-integrity-error")).toBeVisible();
    // A blank panel is a worse answer than a slightly stale one when the question is "what is wrong".
    await expect(page.getByTestId("db-integrity-row-DANGLING_SEARCH_DOCUMENT")).toBeVisible();
  });

  test("a 403 explains the missing permission rather than showing an empty report", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, { message: "forbidden" }, 403));
    await open(page);

    await expect(page.getByTestId("db-integrity-forbidden")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("db-integrity-summary")).toHaveCount(0);
  });
});
