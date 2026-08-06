import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the search fields added to the listing views that had none: tasks, skills
 * (both tabs), memory, chat sessions, and the roles admin.
 *
 * Each is a local filter over the loaded rows, so the contract under test is the same everywhere:
 * typing narrows the list, an unmatched term shows the inline no-match hint rather than the
 * EmptyState (LOOM_UI.md §7.5), and clearing restores every row.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const TASKS = [
  { uuid: "task-1", title: "Encode the drone footage", description: "ProRes to h264", priority: "HIGH", status: { created: "2026-01-01T00:00:00Z" } },
  { uuid: "task-2", title: "Review the sunset stills", description: "colour grade pass", priority: "LOW", status: { created: "2026-01-02T00:00:00Z" } },
];

const SKILLS = [
  { uuid: "skill-1", name: "summarize", description: "Condense a transcript", enabled: true, published: false },
  { uuid: "skill-2", name: "translate", description: "Render text in another language", enabled: true, published: false },
];

const LIBRARY_SKILLS = [
  { uuid: "skill-9", name: "captioner", description: "Write alt text for an image", enabled: true, published: true },
  { uuid: "skill-8", name: "tagger", description: "Suggest tags for an asset", enabled: true, published: true },
];

const SESSIONS = [
  { uuid: "sess-1", name: "drone-pipeline", description: "Building the ingest graph", tags: ["pipeline"], published: false },
  { uuid: "sess-2", name: "colour-notes", description: "Grading conversation", tags: ["colour"], published: true },
];

const ROLES = [
  { uuid: "role-1", name: "editor", permissions: ["READ_ASSET"] },
  { uuid: "role-2", name: "auditor", permissions: ["READ_SEARCH"] },
];

const MEMORY_ENTRIES = [
  { id: "notes/drone", title: "Drone settings", edited: "2026-01-01T00:00:00Z", editor: "admin", sessionName: "ingest", size: 120 },
  { id: "notes/colour", title: "Colour targets", edited: "2026-01-02T00:00:00Z", editor: "admin", sessionName: "grading", size: 240 },
];

async function installMocks(page: Page) {
  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  const list = (rows: unknown[]) => (route: Route) =>
    json(route, { data: rows, _metainfo: { perPage: 100, totalCount: rows.length } });

  await page.route(/\/api\/v1\/tasks(\?|$)/, list(TASKS));
  await page.route(/\/api\/v1\/skills\/library(\?|$)/, list(LIBRARY_SKILLS));
  await page.route(/\/api\/v1\/skills(\?|$)/, list(SKILLS));
  await page.route(/\/api\/v1\/chat-sessions(\?|$)/, list(SESSIONS));
  await page.route(/\/api\/v1\/roles(\?|$)/, list(ROLES));

  await page.route(/\/api\/v1\/memory\/scopes/, (route) =>
    json(route, { scopes: [{ ref: "user", scope: "user", label: "My notes", writable: true, count: 2, maxEntries: 50, bytes: 360, maxBytes: 10240 }] }));
  await page.route(/\/api\/v1\/memory(\?|$)/, (route) => json(route, { entries: MEMORY_ENTRIES }));
}

async function signIn(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/**
 * Open a route directly.
 *
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load
 * the URL first and sign in on it — the router resolves to the route, not to the root.
 */
async function open(page: Page, path: string) {
  await page.goto(`/ui${path}`);
  await signIn(page);
}

test.describe("List search fields – mocked e2e", () => {

  test("tasks: filters on title, shows the no-match hint, restores on clear", async ({ page }) => {
    await installMocks(page);
    await open(page, "/tasks");

    const search = page.getByTestId("tasks-search").locator("input");
    await expect(search).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Encode the drone footage")).toBeVisible();

    await search.fill("drone");
    await expect(page.getByText("Encode the drone footage")).toBeVisible();
    await expect(page.getByText("Review the sunset stills")).toBeHidden();

    await search.fill("nothingmatchesthis");
    // The inline hint, never the EmptyState — the collection is not empty.
    await expect(page.getByTestId("tasks-no-match")).toBeVisible();
    await expect(page.getByTestId("tasks-empty-state")).toBeHidden();

    await search.fill("");
    await expect(page.getByText("Review the sunset stills")).toBeVisible();
  });

  test("tasks: filters on description too", async ({ page }) => {
    await installMocks(page);
    await open(page, "/tasks");

    await page.getByTestId("tasks-search").locator("input").fill("colour grade");
    await expect(page.getByText("Review the sunset stills")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Encode the drone footage")).toBeHidden();
  });

  test("skills: each tab keeps its own term", async ({ page }) => {
    await installMocks(page);
    await open(page, "/skills");

    await expect(page.getByTestId("skills-mine-search")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("skills-mine-search").locator("input").fill("summ");
    await expect(page.getByTestId("skill-row-summarize")).toBeVisible();
    await expect(page.getByTestId("skill-row-translate")).toBeHidden();

    // Switching tabs must not carry the filter across.
    await page.getByTestId("skills-library-tab").click();
    await expect(page.getByTestId("skills-library-search").locator("input")).toHaveValue("");
    await expect(page.getByTestId("skill-library-row-captioner")).toBeVisible();

    await page.getByTestId("skills-mine-tab").click();
    await expect(page.getByTestId("skills-mine-search").locator("input")).toHaveValue("summ");
  });

  test("skills: an unmatched term shows the hint, not the empty state", async ({ page }) => {
    await installMocks(page);
    await open(page, "/skills");

    await page.getByTestId("skills-mine-search").locator("input").fill("nothingmatchesthis");
    await expect(page.getByTestId("skills-no-match")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("skills-empty-state")).toBeHidden();
  });

  test("memory: filters on id and title, and distinguishes empty from no-match", async ({ page }) => {
    await installMocks(page);
    await open(page, "/memory");

    const search = page.getByTestId("memory-search").locator("input");
    await expect(search).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-row-notes/drone")).toBeVisible();

    await search.fill("colour");
    await expect(page.getByTestId("memory-row-notes/colour")).toBeVisible();
    await expect(page.getByTestId("memory-row-notes/drone")).toBeHidden();

    await search.fill("nothingmatchesthis");
    await expect(page.getByTestId("memory-no-match")).toBeVisible();
    await expect(page.getByTestId("memory-empty")).toBeHidden();

    await search.fill("");
    await expect(page.getByTestId("memory-row-notes/drone")).toBeVisible();
  });

  test("chat sessions: filters on name and tags", async ({ page }) => {
    await installMocks(page);
    await open(page, "/chat/sessions");

    const search = page.getByTestId("chat-sessions-search").locator("input");
    await expect(search).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-session-row-drone-pipeline")).toBeVisible();

    await search.fill("colour");
    await expect(page.getByTestId("chat-session-row-colour-notes")).toBeVisible();
    await expect(page.getByTestId("chat-session-row-drone-pipeline")).toBeHidden();

    await search.fill("pipeline");
    await expect(page.getByTestId("chat-session-row-drone-pipeline")).toBeVisible();

    await search.fill("nothingmatchesthis");
    await expect(page.getByTestId("chat-sessions-no-match")).toBeVisible();

    await search.fill("");
    await expect(page.getByTestId("chat-session-row-colour-notes")).toBeVisible();
  });

  test("roles admin: filters the role list", async ({ page }) => {
    await installMocks(page);
    await open(page, "/admin/permissions");

    const search = page.getByTestId("admin-roles-search").locator("input");
    await expect(search).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("admin-role-row-auditor")).toBeVisible();

    await search.fill("edit");
    await expect(page.getByTestId("admin-role-row-editor")).toBeVisible();
    await expect(page.getByTestId("admin-role-row-auditor")).toBeHidden();

    await search.fill("nothingmatchesthis");
    await expect(page.getByTestId("admin-roles-no-match")).toBeVisible();

    await search.fill("");
    await expect(page.getByTestId("admin-role-row-auditor")).toBeVisible();
  });
});
