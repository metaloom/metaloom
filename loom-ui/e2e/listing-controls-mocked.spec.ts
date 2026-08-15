import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the sort and filter controls on the remaining listing views — the admin tables,
 * tasks, skills, chat sessions, and the three screens that sort in the browser.
 *
 * Companion to `list-sort-filter-mocked.spec.ts`, which covers the assets and collections
 * behaviour in depth. This file is about **breadth**: every listing screen offers a way in, and
 * every control it offers actually reaches whatever backs the list.
 *
 * The distinction the specs turn on is which mechanism a screen uses, because getting it wrong is
 * invisible until the collection outgrows one page:
 *
 * - Backed by a Loom list route → the control is a query parameter, asserted on the wire.
 * - Not backed by one (Cortex's live worker registry, agent memory) → the control is a comparator
 *   over a set held whole in memory, asserted on the rendered order.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ALICE = "aaaaaaaa-0000-0000-0000-000000000001";
const BOB = "bbbbbbbb-0000-0000-0000-000000000002";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

type Recorder = Record<string, string[]>;

const USERS = [
  { uuid: ALICE, username: "alice", firstname: "Alice", lastname: "Ackers", enabled: true },
  { uuid: BOB, username: "bob", firstname: "Bob", lastname: "Baker", enabled: false },
];

function named(prefix: string, count: number) {
  return Array.from({ length: count }, (_, i) => ({
    uuid: `${prefix}-${i}`,
    name: `${prefix} ${i}`,
    status: {
      created: `2026-01-0${i + 1}T00:00:00Z`,
      edited: `2026-02-0${i + 1}T00:00:00Z`,
      creator: { uuid: i % 2 === 0 ? ALICE : BOB, name: "someone" },
    },
  }));
}

const TASKS = [
  { uuid: "task-1", title: "Encode the drone footage", description: "ProRes to h264", priority: "HIGH", status: "PENDING", status_: {}, },
  { uuid: "task-2", title: "Review the sunset stills", description: "colour grade pass", priority: "LOW", status: "ACCEPTED" },
].map(t => ({ ...t, status: { created: "2026-01-01T00:00:00Z", edited: "2026-02-01T00:00:00Z" }, state: t.status }));

const SKILLS = [
  { uuid: "skill-1", name: "summarize", description: "Condense a transcript", enabled: true, published: false,
    status: { created: "2026-01-01T00:00:00Z", edited: "2026-02-01T00:00:00Z" } },
  { uuid: "skill-2", name: "translate", description: "Render text in another language", enabled: false, published: false,
    status: { created: "2026-01-02T00:00:00Z", edited: "2026-02-02T00:00:00Z" } },
];

const LIBRARY_SKILLS = [
  { uuid: "skill-9", name: "captioner", description: "Write alt text", enabled: true, published: true,
    status: { created: "2026-01-03T00:00:00Z", edited: "2026-02-03T00:00:00Z", creator: { uuid: BOB, name: "bob" } } },
];

const SESSIONS = [
  { uuid: "sess-1", name: "drone-pipeline", description: "Building the ingest graph", tags: ["pipeline"], published: false,
    status: { created: "2026-01-01T00:00:00Z", edited: "2026-02-01T00:00:00Z" } },
  { uuid: "sess-2", name: "colour-notes", description: "Grading conversation", tags: ["colour"], published: true,
    status: { created: "2026-01-02T00:00:00Z", edited: "2026-02-02T00:00:00Z" } },
];

/**
 * Memory notes, deliberately in an order that is neither alphabetical nor by date, so a screen
 * that ignored the sort would render this sequence unchanged.
 */
const MEMORY_ENTRIES = [
  { uuid: "m-1", scope: "user", id: "notes/drone", title: "Zulu drone settings", size: 120, version: 1,
    created: "2026-01-03T00:00:00Z", edited: "2026-02-01T00:00:00Z", editor: "admin", sessionName: "ingest" },
  { uuid: "m-2", scope: "user", id: "notes/colour", title: "Alpha colour targets", size: 240, version: 1,
    created: "2026-01-01T00:00:00Z", edited: "2026-02-03T00:00:00Z", editor: "admin", sessionName: "grading" },
  { uuid: "m-3", scope: "user", id: "notes/audio", title: "Mike audio levels", size: 90, version: 1,
    created: "2026-01-02T00:00:00Z", edited: "2026-02-02T00:00:00Z", editor: "admin", sessionName: "mix" },
];

const PROCESSORS = [
  { nodeId: "w-zulu", name: "zulu", host: "10.0.0.3:9090", priority: 1, capabilities: ["GPU"], status: "ONLINE", lastSeen: "2026-02-01T00:00:00Z" },
  { nodeId: "w-alpha", name: "alpha", host: "10.0.0.1:9090", priority: 1, capabilities: ["CPU"], status: "ONLINE", lastSeen: "2026-02-03T00:00:00Z" },
  { nodeId: "w-mike", name: "mike", host: "10.0.0.2:9090", priority: 1, capabilities: ["GPU"], status: "OFFLINE", lastSeen: "2026-02-02T00:00:00Z" },
];

async function installMocks(page: Page): Promise<Recorder> {
  const recorder: Recorder = {
    spaces: [], users: [], groups: [], roles: [], blacklists: [],
    tasks: [], skills: [], skillLibrary: [], sessions: [],
  };

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  const serve = (key: string, rows: unknown[]) => (route: Route) => {
    recorder[key].push(new URL(route.request().url()).search);
    return json(route, {
      data: rows,
      _metainfo: { lastUuid: (rows[rows.length - 1] as { uuid?: string })?.uuid, perPage: 100, totalCount: rows.length },
    });
  };

  await page.route(/\/api\/v1\/spaces(\?|$)/, serve("spaces", named("space", 3)));
  await page.route(/\/api\/v1\/groups(\?|$)/, serve("groups", named("group", 3)));
  await page.route(/\/api\/v1\/roles(\?|$)/, serve("roles", named("role", 3)));
  await page.route(/\/api\/v1\/blacklists(\?|$)/, serve("blacklists", named("blocked", 3)));
  await page.route(/\/api\/v1\/tasks(\?|$)/, serve("tasks", TASKS));
  await page.route(/\/api\/v1\/skills\/library(\?|$)/, serve("skillLibrary", LIBRARY_SKILLS));
  await page.route(/\/api\/v1\/skills(\?|$)/, serve("skills", SKILLS));
  await page.route(/\/api\/v1\/chat-sessions(\?|$)/, serve("sessions", SESSIONS));

  // Users is both a listing under test and the source of every creator picker, so it is served
  // last-registered and records its own query strings.
  await page.route(/\/api\/v1\/users(\?|$)/, serve("users", USERS));

  await page.route(/\/api\/v1\/memory\/scopes/, (route) =>
    json(route, { scopes: [{ ref: "user", scope: "user", label: "My notes", writable: true, count: 3, maxEntries: 50, bytes: 450, maxBytes: 10240 }] }));
  await page.route(/\/api\/v1\/memory(\?|$)/, (route) => json(route, { entries: MEMORY_ENTRIES }));
  await page.route(/\/api\/v1\/processors(\?|$)/, (route) => json(route, { data: PROCESSORS, _metainfo: { totalCount: PROCESSORS.length } }));

  return recorder;
}

async function signIn(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function open(page: Page, path: string) {
  await page.goto(`/ui${path}`);
  await signIn(page);
}

/** Pick an option out of a MUI Select identified by its display testid. */
async function choose(page: Page, testId: string, optionValue: string) {
  await page.getByTestId(testId).click();
  await page.getByTestId(`${testId}-option-${optionValue}`).click();
}

const lastQuery = (recorder: Recorder, key: string) => recorder[key][recorder[key].length - 1];

// ── Server-backed listings ──────────────────────────────────────────────

/**
 * One row per listing screen backed by a Loom list route.
 *
 * A table rather than a spec each: what is being asserted is identical everywhere, and the thing
 * that regresses is coverage — a new screen ships with a search box and nobody notices the missing
 * sort. Adding a row is then the cheapest way to keep that from happening.
 */
const SERVER_VIEWS = [
  { name: "admin spaces", path: "/admin/spaces", prefix: "admin-spaces", key: "spaces", filter: { testId: "admin-spaces-filter-creator", option: ALICE, expect: `creator[eq]=${ALICE}` } },
  { name: "admin users", path: "/admin/users", prefix: "admin-users", key: "users", filter: { testId: "admin-users-filter-enabled", option: "true", expect: "enabled[eq]=true" } },
  { name: "admin groups", path: "/admin/groups", prefix: "admin-groups", key: "groups", filter: { testId: "admin-groups-filter-creator", option: ALICE, expect: `creator[eq]=${ALICE}` } },
  { name: "admin roles", path: "/admin/permissions", prefix: "admin-roles", key: "roles", filter: { testId: "admin-roles-filter-creator", option: ALICE, expect: `creator[eq]=${ALICE}` } },
  { name: "admin blacklist", path: "/admin/blacklist", prefix: "admin-blacklist", key: "blacklists", filter: { testId: "admin-blacklist-filter-creator", option: ALICE, expect: `creator[eq]=${ALICE}` } },
  { name: "tasks", path: "/tasks", prefix: "tasks", key: "tasks", filter: { testId: "tasks-filter-status", option: "ACCEPTED", expect: "status[eq]=ACCEPTED" } },
  { name: "chat sessions", path: "/chat/sessions", prefix: "chat-sessions", key: "sessions", filter: { testId: "chat-sessions-filter-creator", option: ALICE, expect: `creator[eq]=${ALICE}` } },
];

test.describe("Listing controls – server-backed views", () => {
  for (const view of SERVER_VIEWS) {
    test(`${view.name}: search, filter and sort all reach the server`, async ({ page }) => {
      const recorder = await installMocks(page);
      await open(page, view.path);

      await expect(page.getByTestId(`${view.prefix}-search`)).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId(`${view.prefix}-sort`)).toBeVisible();
      await expect(page.getByTestId(view.filter.testId)).toBeVisible();

      // Presence is not the assertion — a control wired to a local comparator would look the
      // same. Picking an option has to change the request.
      await choose(page, `${view.prefix}-sort`, "name");
      await expect.poll(() => lastQuery(recorder, view.key)).toContain("sort=name");

      await page.getByTestId(`${view.prefix}-sort-direction`).click();
      await expect.poll(() => lastQuery(recorder, view.key)).toContain("dir=desc");

      await choose(page, view.filter.testId, view.filter.option);
      await expect.poll(() => lastQuery(recorder, view.key)).toContain(encodeURIComponent(view.filter.expect));
    });
  }

  test("tasks: status and priority narrow together", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/tasks");
    await expect(page.getByTestId("tasks-search")).toBeVisible({ timeout: 10_000 });

    await choose(page, "tasks-filter-status", "PENDING");
    await choose(page, "tasks-filter-priority", "HIGH");

    const query = lastQuery(recorder, "tasks");
    expect(query).toContain(encodeURIComponent("status[eq]=PENDING"));
    expect(query).toContain(encodeURIComponent("priority[eq]=HIGH"));
  });

  test("tasks now page, so the header reports the collection rather than the rows fetched", async ({ page }) => {
    await installMocks(page);
    await open(page, "/tasks");

    // The screen used to fetch exactly one page and render it as the task list.
    await expect(page.getByTestId("tasks-count")).toHaveText(/^2 /, { timeout: 10_000 });
  });

  test("skills: the enabled filter applies to whichever tab is showing", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/skills");
    await expect(page.getByTestId("skills-mine-search")).toBeVisible({ timeout: 10_000 });

    await choose(page, "skills-filter-enabled", "true");
    await expect.poll(() => lastQuery(recorder, "skills")).toContain(encodeURIComponent("enabled[eq]=true"));

    await page.getByTestId("skills-library-tab").click();
    await expect.poll(() => lastQuery(recorder, "skillLibrary")).toContain(encodeURIComponent("enabled[eq]=true"));
  });

  test("skills: the creator filter belongs to the library tab alone", async ({ page }) => {
    await installMocks(page);
    await open(page, "/skills");
    await expect(page.getByTestId("skills-mine-search")).toBeVisible({ timeout: 10_000 });

    // Every skill on "mine" has the same creator, so the control would be a no-op there.
    await expect(page.getByTestId("skills-filter-creator")).toHaveCount(0);

    await page.getByTestId("skills-library-tab").click();
    await expect(page.getByTestId("skills-filter-creator")).toBeVisible();
  });

  test("changing a filter restarts the listing rather than seeking with a stale cursor", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/admin/groups");
    await expect(page.getByTestId("admin-groups-search")).toBeVisible({ timeout: 10_000 });

    await choose(page, "admin-groups-filter-creator", ALICE);

    // A cursor points into one particular filtered ordering; carrying it across would resume in
    // the middle of a result set that no longer exists.
    await expect.poll(() => lastQuery(recorder, "groups")).not.toContain("from=");
  });
});

// ── Browser-sorted screens ──────────────────────────────────────────────

/**
 * Cortex and memory are not Loom collections — the worker registry is live in-memory state and
 * memory has its own scoped API — so their sort is a comparator, and the assertion is the rendered
 * order rather than a query string.
 */
test.describe("Listing controls – browser-sorted views", () => {

  test("cortex sorts its workers by name without a request", async ({ page }) => {
    await installMocks(page);
    await open(page, "/cortex");

    await expect(page.getByTestId("cortex-sort")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("cortex-search")).toBeVisible();

    // Both filters were already here; the sort is the new part.
    const names = () => page.locator("[data-worker-name]").evaluateAll(n => n.map(e => e.getAttribute("data-worker-name")));
    await choose(page, "cortex-sort", "name");
    await expect.poll(names).toEqual(["alpha", "mike", "zulu"]);

    await page.getByTestId("cortex-sort-direction").click();
    await expect.poll(names).toEqual(["zulu", "mike", "alpha"]);
  });

  test("memory sorts notes by title and by last edited", async ({ page }) => {
    await installMocks(page);
    await open(page, "/memory");

    await expect(page.getByTestId("memory-sort")).toBeVisible({ timeout: 10_000 });

    const ids = () => page.locator("[data-memory-id]").evaluateAll(n => n.map(e => e.getAttribute("data-memory-id")));

    // Default is most-recently-edited first: colour 02-03, audio 02-02, drone 02-01.
    await expect.poll(ids).toEqual(["notes/colour", "notes/audio", "notes/drone"]);

    // Switching the column keeps the direction — descending by title is Zulu / Mike / Alpha,
    // which is a different order than the ids alone would give.
    await choose(page, "memory-sort", "name");
    await expect.poll(ids).toEqual(["notes/drone", "notes/audio", "notes/colour"]);

    await page.getByTestId("memory-sort-direction").click();
    await expect.poll(ids).toEqual(["notes/colour", "notes/audio", "notes/drone"]);
  });

  test("object detection filters by confidence band", async ({ page }) => {
    await installMocks(page);
    await open(page, "/detection");

    // Faces is the first tab; objects is the second. Neither has a route of its own.
    await page.getByRole("tab").nth(1).click();
    await expect(page.getByTestId("objectdetection-search")).toBeVisible({ timeout: 10_000 });
    // The second filter the screen was missing: "what did the model only half-believe".
    await expect(page.getByTestId("objectdetection-filter-confidence")).toBeVisible();
  });

  test("face detection filters clusters by assignment, and hides it on the persons panel", async ({ page }) => {
    await installMocks(page);
    await open(page, "/detection");

    await expect(page.getByTestId("facedetection-search")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("facedetection-filter-assignment")).toBeVisible();

    // A person is not assigned to anything, so the control does not belong on that panel.
    await page.getByRole("button", { name: /persons/i }).first().click();
    await expect(page.getByTestId("facedetection-filter-assignment")).toHaveCount(0);
  });
});
