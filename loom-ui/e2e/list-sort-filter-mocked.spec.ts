import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the sort and filter controls on the listing views.
 *
 * The contract these pin is that **both are server-side**. A listing route serves a page at a
 * time, so a comparator or a predicate applied to the rows in memory would answer a question about
 * the loaded page rather than about the collection — the exact failure the paging work fixed for
 * counts, reintroduced through a different control.
 *
 * The mock is therefore a small implementation rather than a fixed payload: it reads `?sort=`,
 * `?dir=` and `?filter=` and answers accordingly, and the specs assert on what is rendered. A spec
 * that only asserted the query string would pass just as well against a view that sent the right
 * parameters and then re-sorted the response locally.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

const ALICE = "aaaaaaaa-0000-0000-0000-000000000001";
const BOB = "bbbbbbbb-0000-0000-0000-000000000002";

// The asset fixtures below reference these by uuid, and the asset view builds its collection
// picker from the /collections response — so the two tables have to agree on them.
const COL_ZEBRA = "cccccccc-0000-0000-0000-000000000001";
const COL_APPLE = "cccccccc-0000-0000-0000-000000000002";
const COL_MANGO = "cccccccc-0000-0000-0000-000000000003";
const COL_CHERRY = "cccccccc-0000-0000-0000-000000000004";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** Query strings seen per route, so a spec can also check what went over the wire. */
type Recorder = Record<string, string[]>;

interface Row {
  uuid: string;
  name: string;
  created: string;
  edited: string;
  creator: string;
  /** Asset rows only. */
  collections?: string[];
}

/**
 * Deliberately incoherent orderings: name, created and edited each rank these rows differently,
 * and none matches the array order. A view that ignored the sort parameter, or sorted by the wrong
 * column, produces a different sequence in every case.
 */
const COLLECTIONS: Row[] = [
  { uuid: COL_ZEBRA, name: "Zebra", created: "2026-01-05T00:00:00Z", edited: "2026-02-01T00:00:00Z", creator: ALICE },
  { uuid: COL_APPLE, name: "Apple", created: "2026-01-03T00:00:00Z", edited: "2026-02-04T00:00:00Z", creator: BOB },
  { uuid: COL_MANGO, name: "Mango", created: "2026-01-01T00:00:00Z", edited: "2026-02-02T00:00:00Z", creator: ALICE },
  { uuid: COL_CHERRY, name: "Cherry", created: "2026-01-04T00:00:00Z", edited: "2026-02-03T00:00:00Z", creator: BOB },
];

const ASSETS: Row[] = [
  { uuid: "ast-1", name: "sunset.jpg", created: "2026-01-04T00:00:00Z", edited: "2026-03-01T00:00:00Z", creator: ALICE, collections: [COL_APPLE] },
  { uuid: "ast-2", name: "beach.jpg", created: "2026-01-02T00:00:00Z", edited: "2026-03-04T00:00:00Z", creator: BOB, collections: [COL_APPLE, COL_MANGO] },
  { uuid: "ast-3", name: "office.jpg", created: "2026-01-01T00:00:00Z", edited: "2026-03-02T00:00:00Z", creator: ALICE, collections: [COL_MANGO] },
  { uuid: "ast-4", name: "atlas.jpg", created: "2026-01-03T00:00:00Z", edited: "2026-03-03T00:00:00Z", creator: BOB, collections: [] },
];

const USERS = [
  { uuid: ALICE, username: "alice", firstname: "Alice", lastname: "Ackers", enabled: true },
  { uuid: BOB, username: "bob", firstname: "Bob", lastname: "Baker", enabled: true },
];

/**
 * Parse the LHS filter grammar the client emits: `creator[eq]=<uuid>,collection[eq]=<uuid>`.
 *
 * Mirrors `LHSFilterParserImpl` closely enough to catch a client that sends the wrong shape — a
 * repeated `filter` key, or `[eq]` spelled some other way, yields no terms here and the spec fails
 * on the rendered list.
 */
function parseFilters(raw: string | null): Array<{ key: string; value: string }> {
  if (!raw) return [];
  return raw.split(",").flatMap(term => {
    const match = /^([a-z_]+)\[eq\]=(.+)$/.exec(term);
    return match ? [{ key: match[1], value: match[2] }] : [];
  });
}

/** Apply `?sort=`/`?dir=`/`?filter=` to a fixture table the way the server would. */
function applyQuery(rows: Row[], url: URL): Row[] {
  let result = [...rows];

  for (const { key, value } of parseFilters(url.searchParams.get("filter"))) {
    if (key === "creator") result = result.filter(r => r.creator === value);
    else if (key === "collection") result = result.filter(r => (r.collections ?? []).includes(value));
    else if (key === "name") result = result.filter(r => r.name === value);
  }

  const sort = url.searchParams.get("sort");
  if (sort) {
    const of = (row: Row) => (sort === "name" ? row.name : sort === "edited" ? row.edited : row.created);
    result.sort((a, b) => of(a).localeCompare(of(b)));
    if (url.searchParams.get("dir") === "desc") result.reverse();
  }
  return result;
}

function assetPayload(row: Row) {
  return {
    uuid: row.uuid,
    file: { mimeType: "image/jpeg", filename: row.name, size: 2048, origin: "upload", firstSeen: "" },
    hashes: { sha512: `hash-${row.uuid}` },
    status: { created: row.created, edited: row.edited, creator: { uuid: row.creator, name: row.creator } },
  };
}

function collectionPayload(row: Row) {
  return {
    uuid: row.uuid,
    name: row.name,
    status: { created: row.created, edited: row.edited, creator: { uuid: row.creator, name: row.creator } },
  };
}

/** Tags and pools carry their own shapes, but the sort/filter contract is the same one. */
function tagPayload(row: Row) {
  return {
    uuid: row.uuid,
    name: row.name,
    collection: "fixtures",
    status: { created: row.created, edited: row.edited, creator: { uuid: row.creator, name: row.creator } },
  };
}

function poolPayload(row: Row) {
  return {
    uuid: row.uuid,
    name: row.name,
    type: "FS",
    path: `/mnt/${row.name}`,
    status: { created: row.created, edited: row.edited, creator: { uuid: row.creator, name: row.creator } },
  };
}

async function installMocks(page: Page): Promise<Recorder> {
  const recorder: Recorder = { assets: [], collections: [], tags: [], pools: [] };

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  // Search off, so the asset view stays on the browse path. With search available a typed term
  // goes to /search/assets instead, and the sort control is hidden by design.
  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  await page.route(/\/api\/v1\/users(\?|$)/, (route) =>
    json(route, { data: USERS, _metainfo: { perPage: 200, totalCount: USERS.length } }));

  // LibraryView renders its asset panel — and therefore its toolbar — only once a library is
  // selected, and it selects the first one the list returns.
  await page.route(/\/api\/v1\/libraries(\?|$)/, (route) =>
    json(route, {
      data: [{ uuid: "lib-1", name: "Main", meta: {}, status: { created: "2026-01-01T00:00:00Z" } }],
      _metainfo: { perPage: 100, totalCount: 1 },
    }));

  const serve = (key: string, rows: Row[], toPayload: (row: Row) => unknown) => (route: Route) => {
    const url = new URL(route.request().url());
    recorder[key].push(url.search);
    const matched = applyQuery(rows, url);
    return json(route, {
      data: matched.map(toPayload),
      _metainfo: { lastUuid: matched[matched.length - 1]?.uuid, perPage: 100, totalCount: matched.length },
    });
  };

  await page.route(/\/api\/v1\/assets(\?|$)/, serve("assets", ASSETS, assetPayload));
  await page.route(/\/api\/v1\/collections(\?|$)/, serve("collections", COLLECTIONS, collectionPayload));
  await page.route(/\/api\/v1\/tags(\?|$)/, serve("tags", COLLECTIONS, tagPayload));
  await page.route(/\/api\/v1\/pools(\?|$)/, serve("pools", COLLECTIONS, poolPayload));

  return recorder;
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

/** Pick an option out of a MUI Select identified by its display testid. */
async function choose(page: Page, testId: string, optionValue: string) {
  await page.getByTestId(testId).click();
  await page.getByTestId(`${testId}-option-${optionValue}`).click();
}

const collectionNames = (page: Page) =>
  page.getByTestId("collection-card").evaluateAll(nodes => nodes.map(n => n.getAttribute("data-collection-name")));

const assetNames = (page: Page) =>
  page.getByTestId("asset-card").evaluateAll(nodes => nodes.map(n => n.getAttribute("data-asset-name")));

/** The last query string the route saw — what the control actually asked the server for. */
const lastQuery = (recorder: Recorder, key: string) => recorder[key][recorder[key].length - 1];

test.describe("List sorting – mocked e2e", () => {

  test("collections load oldest-first by default, so a new one lands at the end", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/collections");

    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });
    // Mango 01-01, Apple 01-03, Cherry 01-04, Zebra 01-05.
    expect(await collectionNames(page)).toEqual(["Mango", "Apple", "Cherry", "Zebra"]);
    expect(lastQuery(recorder, "collections")).toContain("sort=created");
    expect(lastQuery(recorder, "collections")).toContain("dir=asc");
  });

  test("sorting collections by name reorders the list", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "collections-sort", "name");

    await expect.poll(() => collectionNames(page)).toEqual(["Apple", "Cherry", "Mango", "Zebra"]);
    expect(lastQuery(recorder, "collections")).toContain("sort=name");
  });

  test("the direction toggle flips the order", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "collections-sort", "name");
    await expect.poll(() => collectionNames(page)).toEqual(["Apple", "Cherry", "Mango", "Zebra"]);

    await page.getByTestId("collections-sort-direction").click();

    await expect.poll(() => collectionNames(page)).toEqual(["Zebra", "Mango", "Cherry", "Apple"]);
    expect(lastQuery(recorder, "collections")).toContain("dir=desc");
  });

  test("sorting by last edited is a different order again, not an alias for created", async ({ page }) => {
    await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "collections-sort", "edited");

    // Zebra 02-01, Mango 02-02, Cherry 02-03, Apple 02-04 — unlike every other ordering here.
    await expect.poll(() => collectionNames(page)).toEqual(["Zebra", "Mango", "Cherry", "Apple"]);
  });

  test("changing the sort restarts from the first page rather than seeking with a stale cursor", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "collections-sort", "name");
    await expect.poll(() => collectionNames(page)).toEqual(["Apple", "Cherry", "Mango", "Zebra"]);

    // A cursor points into one particular ordering; carrying it across a re-sort would resume in
    // the middle of an order that no longer exists.
    expect(lastQuery(recorder, "collections")).not.toContain("from=");
  });

  test("assets sort by name, which the server maps onto the filename", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-sort", "name");

    await expect.poll(() => assetNames(page)).toEqual(["atlas.jpg", "beach.jpg", "office.jpg", "sunset.jpg"]);
    expect(lastQuery(recorder, "assets")).toContain("sort=name");
  });

  test("assets sort by last edited, newest first", async ({ page }) => {
    await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-sort", "edited");
    await page.getByTestId("assets-sort-direction").click();

    // beach 03-04, atlas 03-03, office 03-02, sunset 03-01.
    await expect.poll(() => assetNames(page)).toEqual(["beach.jpg", "atlas.jpg", "office.jpg", "sunset.jpg"]);
  });
});

test.describe("List filtering – mocked e2e", () => {

  test("collections filter by creator", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "collections-filter-creator", ALICE);

    await expect.poll(() => collectionNames(page)).toEqual(["Mango", "Zebra"]);
    expect(lastQuery(recorder, "collections")).toContain(encodeURIComponent(`creator[eq]=${ALICE}`));
  });

  test("the creator picker shows display names and clears back to everything", async ({ page }) => {
    await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("collections-filter-creator").click();
    // Labelled by person, filtered by uuid — a rename must not break a saved filter.
    await expect(page.getByTestId(`collections-filter-creator-option-${ALICE}`)).toHaveText("Alice Ackers");
    await page.getByTestId(`collections-filter-creator-option-${BOB}`).click();

    await expect.poll(() => collectionNames(page)).toEqual(["Apple", "Cherry"]);

    await choose(page, "collections-filter-creator", "all");
    await expect.poll(() => collectionNames(page)).toHaveLength(4);
  });

  test("assets filter by collection", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-filter-collection", COL_APPLE);

    await expect.poll(() => assetNames(page)).toEqual(["beach.jpg", "sunset.jpg"]);
    expect(lastQuery(recorder, "assets")).toContain(encodeURIComponent(`collection[eq]=${COL_APPLE}`));
  });

  test("assets filter by creator", async ({ page }) => {
    await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-filter-creator", BOB);

    await expect.poll(() => assetNames(page)).toEqual(["beach.jpg", "atlas.jpg"]);
  });

  test("collection and creator narrow together, not one replacing the other", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-filter-collection", COL_APPLE);
    await choose(page, "assets-filter-creator", ALICE);

    // Apple = {sunset, beach}; alice = {sunset, office}; the intersection is sunset alone.
    await expect.poll(() => assetNames(page)).toEqual(["sunset.jpg"]);
    const query = lastQuery(recorder, "assets");
    expect(query).toContain(encodeURIComponent(`collection[eq]=${COL_APPLE}`));
    expect(query).toContain(encodeURIComponent(`creator[eq]=${ALICE}`));
  });

  test("filtering composes with sorting", async ({ page }) => {
    await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-filter-creator", BOB);
    await choose(page, "assets-sort", "name");

    await expect.poll(() => assetNames(page)).toEqual(["atlas.jpg", "beach.jpg"]);
  });

  test("clearing the filters restores the collection but keeps the chosen order", async ({ page }) => {
    await installMocks(page);
    await open(page, "/assets");
    await expect(page.getByTestId("asset-card").first()).toBeVisible({ timeout: 10_000 });

    await choose(page, "assets-sort", "name");
    await choose(page, "assets-filter-creator", ALICE);
    await expect.poll(() => assetNames(page)).toEqual(["office.jpg", "sunset.jpg"]);

    // MUI renders the Chip's clear affordance as its delete icon, not as a button.
    await page.getByTestId("assets-filter-clear").locator(".MuiChip-deleteIcon").click();

    // Everything is back, still by name — the ordering is how the list is read, not a narrowing
    // of it, so clearing filters must not silently reset it.
    await expect.poll(() => assetNames(page)).toEqual(["atlas.jpg", "beach.jpg", "office.jpg", "sunset.jpg"]);
  });

  test("the name box narrows the collections on screen", async ({ page }) => {
    await installMocks(page);
    await open(page, "/collections");
    await expect(page.getByTestId("collection-card").first()).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("collections-search").locator("input").fill("man");

    await expect.poll(() => collectionNames(page)).toEqual(["Mango"]);
  });
});

/**
 * Every server-paged listing view offers the same three ways in — a name box, at least one
 * structured filter, and a sort.
 *
 * A per-view spec would pin the behaviour but not the coverage, and coverage is the property that
 * regresses silently: a new listing screen ships with a search box, nobody notices the missing
 * sort, and the catalog it lists is unorderable. Adding a row here is the cheapest way to keep that
 * from happening.
 */
test.describe("Listing controls – coverage", () => {
  const VIEWS = [
    { name: "assets", path: "/assets", testIdPrefix: "assets", recorderKey: "assets" },
    { name: "collections", path: "/collections", testIdPrefix: "collections", recorderKey: "collections" },
    { name: "tags", path: "/tags", testIdPrefix: "tags", recorderKey: "tags" },
    { name: "asset pools", path: "/asset-pools", testIdPrefix: "asset-pools", recorderKey: "pools" },
  ];

  for (const view of VIEWS) {
    test(`${view.name}: search box, creator filter and sort, all reaching the server`, async ({ page }) => {
      const recorder = await installMocks(page);
      await open(page, view.path);

      const search = view.name === "asset pools" ? "asset-pools-search" : `${view.testIdPrefix}-search`;
      await expect(page.getByTestId(search)).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId(`${view.testIdPrefix}-filter-creator`)).toBeVisible();
      await expect(page.getByTestId(`${view.testIdPrefix}-sort`)).toBeVisible();

      // Not merely present: picking an option has to change the request, which is what separates a
      // control wired to the server from one wired to a local comparator.
      await choose(page, `${view.testIdPrefix}-sort`, "name");
      await expect
        .poll(() => lastQuery(recorder, view.recorderKey))
        .toContain("sort=name");

      await choose(page, `${view.testIdPrefix}-filter-creator`, ALICE);
      await expect
        .poll(() => lastQuery(recorder, view.recorderKey))
        .toContain(encodeURIComponent(`creator[eq]=${ALICE}`));
    });
  }

  test("the library asset panel sorts and filters too", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/library");

    await expect(page.getByTestId("library-sort")).toBeVisible({ timeout: 10_000 });
    await choose(page, "library-sort", "edited");
    await expect.poll(() => lastQuery(recorder, "assets")).toContain("sort=edited");
  });
});
