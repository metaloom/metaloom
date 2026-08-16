import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e proving that **every** paged list view actually pages.
 *
 * Companion to `paging-mocked.spec.ts`, which covers the mechanism in depth on one view — cursor
 * seeking, a repeated boundary row, a missing cursor, a missing total. This file is about
 * **breadth**: one case per screen that holds a `usePagedList` collection, each asserting the same
 * three things, because a screen that quietly shows only its first page is indistinguishable from
 * one showing the whole collection until the data outgrows it.
 *
 * Per view:
 *   1. the first request asks for a page size instead of taking the server default of 25,
 *   2. the footer states how much of the collection is on screen,
 *   3. "load more" seeks from `_metainfo.lastUuid`, and the second page is appended and rendered.
 *
 * The structural half — that a *new* paged view cannot ship without a footer at all — is
 * `src/features/pagedListCoverage.test.ts`, which no e2e spec can express.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const LIB_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const PAGE_SIZE = 100;
const TOTAL = 250;

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const audit = (index: number) => ({
  created: `2026-01-01T00:00:${String(index % 60).padStart(2, "0")}Z`,
  edited: `2026-02-01T00:00:${String(index % 60).padStart(2, "0")}Z`,
  creator: { uuid: ME_UUID, name: "admin" },
});

// ── Row builders ────────────────────────────────────────────────────────
// One per collection route. The uuid always ends in the row's index, which is what makes the
// keyset mock below able to answer `?from=` for every route with the same code.

const named = (prefix: string) => (index: number) => ({
  uuid: `${prefix}-${String(index).padStart(4, "0")}`,
  name: `${prefix} ${index}`,
  status: audit(index),
});

function asset(index: number) {
  return {
    uuid: `asset-${String(index).padStart(4, "0")}`,
    file: { mimeType: "image/jpeg", filename: `photo-${index}.jpg`, size: 1024, origin: "upload", firstSeen: "" },
    hashes: { sha512: `hash-${index}` },
    // Every asset sits in the one library, so the library panel pages the same rows.
    locations: [{ uuid: `loc-${index}`, libraryUuid: LIB_UUID }],
    status: audit(index),
  };
}

function pool(index: number) {
  return { ...named("pool")(index), type: "FS", path: `/mnt/pool-${index}` };
}

function task(index: number) {
  return {
    uuid: `task-${String(index).padStart(4, "0")}`,
    title: `task ${index}`,
    description: "queued work",
    priority: "MEDIUM",
    state: "PENDING",
    status: audit(index),
  };
}

function session(index: number) {
  return {
    uuid: `session-${String(index).padStart(4, "0")}`,
    name: `session ${index}`,
    description: "a conversation",
    tags: ["ingest"],
    published: false,
    status: audit(index),
  };
}

const skill = (prefix: string) => (index: number) => ({
  ...named(prefix)(index),
  description: "does a thing",
  enabled: true,
  published: prefix === "published",
});

function user(index: number) {
  return {
    uuid: `user-${String(index).padStart(4, "0")}`,
    username: `user ${index}`,
    firstname: "Test",
    lastname: `User ${index}`,
    enabled: true,
    status: audit(index),
  };
}

/** Query strings seen per collection route, so each case can assert on the wire. */
type Recorder = Record<string, string[]>;

interface Collection {
  /** Recorder key, and the name used in failures. */
  key: string;
  /** Route matcher for the collection endpoint. */
  pattern: RegExp;
  build: (index: number) => { uuid: string };
  /** Rows the fixture pretends to hold. Defaults to TOTAL. */
  total?: number;
}

const COLLECTIONS: Collection[] = [
  { key: "assets", pattern: /\/api\/v1\/assets(\?|$)/, build: asset },
  { key: "collections", pattern: /\/api\/v1\/collections(\?|$)/, build: named("collection") },
  { key: "tags", pattern: /\/api\/v1\/tags(\?|$)/, build: named("tag") },
  { key: "pools", pattern: /\/api\/v1\/pools(\?|$)/, build: pool },
  { key: "tasks", pattern: /\/api\/v1\/tasks(\?|$)/, build: task },
  { key: "chat-sessions", pattern: /\/api\/v1\/chat-sessions(\?|$)/, build: session },
  { key: "skills", pattern: /\/api\/v1\/skills(\?|$)/, build: skill("skill") },
  { key: "skills-library", pattern: /\/api\/v1\/skills\/library(\?|$)/, build: skill("published") },
  { key: "spaces", pattern: /\/api\/v1\/spaces(\?|$)/, build: named("space") },
  { key: "users", pattern: /\/api\/v1\/users(\?|$)/, build: user },
  { key: "groups", pattern: /\/api\/v1\/groups(\?|$)/, build: named("group") },
  { key: "roles", pattern: /\/api\/v1\/roles(\?|$)/, build: named("role") },
  { key: "blacklists", pattern: /\/api\/v1\/blacklists(\?|$)/, build: named("blocked") },
];

async function installMocks(page: Page, total = TOTAL): Promise<Recorder> {
  const recorder: Recorder = Object.fromEntries(COLLECTIONS.map(c => [c.key, [] as string[]]));

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  // Search off: these specs are about the browse path, and an available backend would route the
  // asset and library boxes elsewhere.
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  // One library, so the library panel has something selected to page inside.
  await page.route(/\/api\/v1\/libraries(\?|$)/, route =>
    json(route, {
      data: [{ uuid: LIB_UUID, name: "Main", meta: {}, status: audit(0) }],
      _metainfo: { perPage: 100, totalCount: 1 },
    }));

  /** Serve one keyset page, honouring `?from=` the way the Loom list routes do. */
  const paged = (c: Collection) => async (route: Route) => {
    const url = new URL(route.request().url());
    recorder[c.key].push(url.search);

    const rows_total = c.total ?? total;
    const from = url.searchParams.get("from");
    // The cursor is the last uuid of the previous page. Every builder ends its uuid with the row
    // index, so the next page starts one past it. Read after the LAST dash — several prefixes
    // contain one themselves.
    const start = from ? Number(from.slice(from.lastIndexOf("-") + 1)) + 1 : 0;
    const rows = Array.from(
      { length: Math.min(PAGE_SIZE, Math.max(0, rows_total - start)) },
      (_, i) => c.build(start + i),
    );

    return json(route, {
      data: rows,
      _metainfo: { lastUuid: rows[rows.length - 1]?.uuid, perPage: PAGE_SIZE, totalCount: rows_total },
    });
  };

  // `/skills/library` is registered after `/skills` so the more specific matcher wins.
  for (const c of COLLECTIONS) {
    await page.route(c.pattern, paged(c));
  }

  return recorder;
}

/** The uuid the first page ends on — what `?from=` must carry to fetch the second. */
function cursorAfterFirstPage(key: string): string {
  const collection = COLLECTIONS.find(c => c.key === key);
  if (!collection) throw new Error(`no collection fixture for "${key}"`);
  return collection.build(PAGE_SIZE - 1).uuid;
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
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load the
 * URL first and sign in on it — the router resolves to the route, not to the root.
 */
async function open(page: Page, path: string) {
  await page.goto(`/ui${path}`);
  await signIn(page);
}

// ── The views ───────────────────────────────────────────────────────────

interface Case {
  name: string;
  path: string;
  /** `ListPaging` testid prefix. */
  testId: string;
  /** Recorder key for the route this view pages. */
  key: string;
  /** Text rendered by the row at the given index — used to prove page two arrived on screen. */
  rowText: (index: number) => string;
  /**
   * Address the row by testid instead of by text.
   *
   * Needed where a row's text runs into the next field: the roles rail renders the name and then
   * the permission count, so "role 10" followed by "0 permissions" reads as "role 100" to a
   * substring locator, and the two rows become indistinguishable.
   */
  rowTestId?: (index: number) => string;
  /** Extra step after loading, before asserting — switching to a tab, mostly. */
  before?: (page: Page) => Promise<void>;
}

function row(page: Page, c: Case, index: number) {
  return c.rowTestId
    ? page.getByTestId(c.rowTestId(index))
    : page.getByText(c.rowText(index), { exact: false }).first();
}

const CASES: Case[] = [
  { name: "assets", path: "/assets", testId: "assets-paging", key: "assets", rowText: i => `photo-${i}.jpg` },
  { name: "library assets", path: "/library", testId: "library-assets-paging", key: "assets", rowText: i => `photo-${i}.jpg` },
  { name: "collections", path: "/collections", testId: "collections-paging", key: "collections", rowText: i => `collection ${i}` },
  { name: "tags", path: "/tags", testId: "tags-paging", key: "tags", rowText: i => `tag ${i}` },
  { name: "asset pools", path: "/asset-pools", testId: "asset-pools-paging", key: "pools", rowText: i => `pool ${i}` },
  { name: "tasks", path: "/tasks", testId: "tasks-paging", key: "tasks", rowText: i => `task ${i}` },
  { name: "chat sessions", path: "/chat/sessions", testId: "chat-sessions-paging", key: "chat-sessions", rowText: i => `session ${i}` },
  { name: "skills (mine)", path: "/skills", testId: "skills-mine-paging", key: "skills", rowText: i => `skill ${i}` },
  {
    name: "skills (library)", path: "/skills", testId: "skills-library-paging", key: "skills-library",
    rowText: i => `published ${i}`,
    // The two tabs page independently; the library one is only mounted once it is selected.
    before: async page => { await page.getByRole("tab").nth(1).click(); },
  },
  { name: "admin spaces", path: "/admin/spaces", testId: "admin-spaces-paging", key: "spaces", rowText: i => `space ${i}` },
  { name: "admin users", path: "/admin/users", testId: "admin-users-paging", key: "users", rowText: i => `user ${i}` },
  { name: "admin groups", path: "/admin/groups", testId: "admin-groups-paging", key: "groups", rowText: i => `group ${i}` },
  {
    name: "admin roles", path: "/admin/permissions", testId: "admin-roles-paging", key: "roles",
    rowText: i => `role ${i}`, rowTestId: i => `admin-role-row-role ${i}`,
  },
  { name: "admin blacklist", path: "/admin/blacklist", testId: "admin-blacklist-paging", key: "blacklists", rowText: i => `blocked ${i}` },
];

test.describe("List paging – every paged view", () => {

  for (const c of CASES) {
    test(`${c.name}: asks for a page, states the total, and loads the next one`, async ({ page }) => {
      const recorder = await installMocks(page);
      await open(page, c.path);
      await c.before?.(page);

      // 1. A page size travels with the first request; without it the server caps at 25 and the
      //    footer would understate the collection by four fifths.
      const footer = page.getByTestId(c.testId);
      await expect(footer).toBeVisible({ timeout: 10_000 });
      const first = recorder[c.key].find(q => !q.includes("from="));
      expect(first, `${c.name}: no cursorless first request was recorded`).toBeDefined();
      expect(first).toContain(`limit=${PAGE_SIZE}`);

      // 2. The footer counts the collection, not the rows fetched.
      await expect(page.getByTestId(`${c.testId}-count`)).toHaveText(`Showing ${PAGE_SIZE} of ${TOTAL}`);
      // The first row of page two cannot be on screen yet — otherwise step 3 proves nothing.
      await expect(row(page, c, PAGE_SIZE)).toBeHidden();

      // 3. Load more seeks from the cursor, and the rows arrive rather than merely being counted.
      await page.getByTestId(`${c.testId}-button`).click();

      await expect(page.getByTestId(`${c.testId}-count`)).toHaveText(`Showing ${PAGE_SIZE * 2} of ${TOTAL}`);
      await expect(row(page, c, PAGE_SIZE)).toBeVisible();
      const seeked = recorder[c.key].filter(q => q.includes("from="));
      expect(seeked.length, `${c.name}: load more issued no seek request`).toBeGreaterThan(0);
      expect(seeked[seeked.length - 1]).toContain(`limit=${PAGE_SIZE}`);
      // The cursor must be the last row of page one — a view that sent an offset, or re-sent page
      // one, would still count to 200 if the mock let it, and would duplicate rows against a real
      // server.
      expect(seeked[seeked.length - 1]).toContain(`from=${cursorAfterFirstPage(c.key)}`);
    });
  }

  for (const c of CASES) {
    test(`${c.name}: no footer once the whole collection fits on one page`, async ({ page }) => {
      await installMocks(page, 40);
      await open(page, c.path);
      await c.before?.(page);

      // The row is proof the view loaded; the hidden footer is the assertion. A footer that is
      // always drawn would offer a "load more" that fetches nothing.
      await expect(row(page, c, 0)).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId(c.testId)).toBeHidden();
    });
  }
});
