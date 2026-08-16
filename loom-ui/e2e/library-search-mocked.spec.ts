import { test, expect, Locator, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the library panel's search box.
 *
 * It used to `Array.filter()` whatever page of `/assets` happened to be loaded, so a library with
 * more assets than one page answered a different set than the global search field did for the same
 * term. It now asks `/search/assets` scoped by `library=<uuid>`; these specs pin where the query
 * goes, what travels with it, that the term is in the URL, and how the panel degrades when the
 * deployment cannot answer.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const LIB_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const OTHER_LIB_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const POSTGRES_CAPABILITIES = ["LEXICAL", "PHRASE", "FUZZY", "HIGHLIGHT", "EXACT_TOTAL", "SUGGEST"];

interface Options {
  /** Whether the deployment can serve queries. */
  available?: boolean;
  /** Status code for GET /search/assets. 200 unless set. */
  searchStatus?: number;
  /** Hits returned per page. */
  pageSize?: number;
  /** `_metainfo.totalHits`. Defaults to one full page. */
  totalHits?: number;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const audit = { created: "2026-01-01T00:00:00Z", edited: "2026-01-01T00:00:00Z" };

/** Two assets in the library. Neither of them is ever a search hit. */
const ASSETS = [
  {
    uuid: "asset-1",
    file: { mimeType: "image/jpeg", filename: "drone-footage.jpg", size: 4096, origin: "upload", firstSeen: "" },
    hashes: { sha512: "hash-1" },
    locations: [{ uuid: "loc-1", libraryUuid: LIB_UUID }],
    status: audit,
  },
  {
    uuid: "asset-2",
    file: { mimeType: "video/mp4", filename: "sunset-clip.mp4", size: 8192, origin: "upload", firstSeen: "" },
    hashes: { sha512: "hash-2" },
    locations: [{ uuid: "loc-2", libraryUuid: LIB_UUID }],
    status: audit,
  },
];

/** Query strings seen by each route, so specs can assert on the wire. */
interface Recorder {
  assets: string[];
  search: string[];
}

async function installMocks(page: Page, options: Options = {}): Promise<Recorder> {
  const recorder: Recorder = { assets: [], search: [] };
  const available = options.available ?? true;
  const pageSize = options.pageSize ?? 2;
  const totalHits = options.totalHits ?? pageSize;

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, available
      ? { provider: "postgres", available: true, capabilities: POSTGRES_CAPABILITIES, documentCount: 120, dirtyCount: 0 }
      : { provider: "none", available: false, reason: "Search is disabled on this deployment.", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  await page.route(/\/api\/v1\/search\/assets/, route => {
    const url = new URL(route.request().url());
    recorder.search.push(url.search);

    if (options.searchStatus && options.searchStatus !== 200) {
      return json(route, { message: "Search provider is unavailable" }, options.searchStatus);
    }

    const offset = Number(url.searchParams.get("offset") ?? 0);
    const count = Math.max(0, Math.min(pageSize, totalHits - offset));
    return json(route, {
      // A hit is not an asset: no tags, no dimensions, no library membership.
      data: Array.from({ length: count }, (_, i) => ({
        type: "asset",
        uuid: `hit-${offset + i}`,
        assetUuid: `hit-${offset + i}`,
        score: 0.9,
        title: `indexed-${offset + i}.jpg`,
        subtitle: "Campaign Media",
        mimeType: "image/jpeg",
        size: 2048,
        sortDate: 1767225600.5,
      })),
      _metainfo: {
        totalHits, totalExact: true,
        // Echoes the *requested* limit, not the effective page size — a client that pages by this
        // number rather than by `data.length` walks off the end.
        perPage: 500, offset, tookMs: 3,
        provider: "postgres", capabilities: POSTGRES_CAPABILITIES, warnings: [],
      },
    });
  });

  await page.route(/\/api\/v1\/libraries(\?|$)/, route =>
    json(route, {
      data: [
        { uuid: LIB_UUID, name: "Main", meta: { description: "Primary library" }, status: audit },
        { uuid: OTHER_LIB_UUID, name: "Archive", meta: {}, status: audit },
      ],
      _metainfo: { perPage: 100, totalCount: 2 },
    }));

  await page.route(/\/api\/v1\/assets(\?|$)/, route => {
    recorder.assets.push(new URL(route.request().url()).search);
    return json(route, { data: ASSETS, _metainfo: { lastUuid: "asset-2", perPage: 100, totalCount: 2 } });
  });

  return recorder;
}

async function signIn(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/**
 * Open the library panel directly.
 *
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load the
 * URL first and sign in on it — the router resolves to the route, not to the root.
 */
async function openLibrary(page: Page, path = "/ui/library") {
  await page.goto(path);
  await signIn(page);
  await expect(page.getByTestId("library-search")).toBeVisible({ timeout: 10_000 });
}

/**
 * The panel's own box.
 *
 * Addressed by testid rather than by placeholder: the sidebar's global search field is on screen
 * too, and `getByPlaceholder(/search/i)` matches both.
 */
function searchBox(page: Page): Locator {
  return page.getByTestId("library-search").locator("input");
}

test.describe("Library search – mocked e2e", () => {

  test("typing issues a /search/assets query scoped to the selected library", async ({ page }) => {
    const recorder = await installMocks(page);
    await openLibrary(page);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("indexed");

    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });
    expect(recorder.search).toHaveLength(1);
    expect(recorder.search[0]).toContain("q=indexed");
    expect(recorder.search[0]).toContain(`library=${LIB_UUID}`);
    // A hit the loaded page never contained is on screen, and a loaded asset that is not a hit is
    // gone — proof the server answered rather than a local `filter()`.
    await expect(page.getByText("drone-footage.jpg")).toBeHidden();
  });

  test("the committed term lives in the URL, and Back re-runs the listing", async ({ page }) => {
    await installMocks(page);
    await openLibrary(page);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("indexed");
    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });
    await expect(page).toHaveURL(/[?&]q=indexed/);

    await page.goBack();
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });
    await expect(searchBox(page)).toHaveValue("");
  });

  test("a term in the URL runs on load — a filtered library is shareable", async ({ page }) => {
    const recorder = await installMocks(page);
    await openLibrary(page, "/ui/library?q=indexed");

    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });
    await expect(searchBox(page)).toHaveValue("indexed");
    expect(recorder.search[0]).toContain("q=indexed");
  });

  test("keystrokes are debounced into one request", async ({ page }) => {
    const recorder = await installMocks(page);
    await openLibrary(page);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });

    // pressSequentially types character by character, which is what the debounce is for.
    await searchBox(page).pressSequentially("indexed", { delay: 30 });
    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });

    expect(recorder.search).toHaveLength(1);
    expect(recorder.search[0]).toContain("q=indexed");
  });

  test("clearing the term restores the listing", async ({ page }) => {
    await installMocks(page);
    await openLibrary(page);

    await searchBox(page).fill("indexed");
    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("");
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("indexed-0.jpg")).toBeHidden();
  });

  test("the hit total is the server's, not the number of tiles", async ({ page }) => {
    await installMocks(page, { pageSize: 2, totalHits: 42 });
    await openLibrary(page);

    await searchBox(page).fill("indexed");
    await expect(page.getByTestId("library-search-hits")).toHaveText("42 results", { timeout: 10_000 });
  });

  test("the pager walks by data.length, not by _metainfo.perPage", async ({ page }) => {
    const recorder = await installMocks(page, { pageSize: 25, totalHits: 42 });
    await openLibrary(page);

    await searchBox(page).fill("indexed");
    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("indexed-24.jpg")).toBeVisible();
    await expect(page.getByText("indexed-25.jpg")).toBeHidden();

    await page.getByTestId("library-search-paging-button").click();

    // The second request seeks by the page actually returned. `perPage` said 500.
    await expect.poll(() => recorder.search.some(q => q.includes("offset=25"))).toBe(true);
    await expect(page.getByText("indexed-41.jpg")).toBeVisible({ timeout: 10_000 });
    // 42 of 42 are on screen, so there is nothing left to offer.
    await expect(page.getByTestId("library-search-paging")).toBeHidden();
  });

  test("switching library re-scopes the same term", async ({ page }) => {
    const recorder = await installMocks(page);
    await openLibrary(page);

    await searchBox(page).fill("indexed");
    await expect(page.getByText("indexed-0.jpg")).toBeVisible({ timeout: 10_000 });

    await page.getByRole("button", { name: "Archive" }).click();

    await expect.poll(() => recorder.search.some(q => q.includes(`library=${OTHER_LIB_UUID}`))).toBe(true);
  });

  test("with search off, the panel says so and falls back to the loaded assets", async ({ page }) => {
    const recorder = await installMocks(page, { available: false });
    await openLibrary(page);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("drone");

    await expect(page.getByTestId("library-search-degraded")).toBeVisible({ timeout: 10_000 });
    // No request was made, and the local filter still narrowed what is loaded.
    expect(recorder.search).toHaveLength(0);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible();
    await expect(page.getByText("sunset-clip.mp4")).toBeHidden();
  });

  test("a 503 mid-session retracts the search and degrades to the plain listing", async ({ page }) => {
    await installMocks(page, { searchStatus: 503 });
    await openLibrary(page);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("drone");

    await expect(page.getByTestId("library-search-degraded")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("drone-footage.jpg")).toBeVisible();
  });

  test("a 403 says the search was denied rather than showing an empty library", async ({ page }) => {
    await installMocks(page, { searchStatus: 403 });
    await openLibrary(page);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("drone");

    await expect(page.getByTestId("library-no-match")).toContainText(/permission/i, { timeout: 10_000 });
    // The empty state belongs to an empty library, never to a search that matched nothing.
    await expect(page.getByTestId("library-assets-empty-state")).toBeHidden();
  });
});
