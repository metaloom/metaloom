import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the asset browser's search box.
 *
 * It used to `Array.filter()` whatever the first page happened to contain. It now asks
 * `/search/assets`, so these specs pin where the query goes, what travels with it, and how the
 * grid degrades when the deployment cannot answer.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const POSTGRES_CAPABILITIES = ["LEXICAL", "PHRASE", "FUZZY", "HIGHLIGHT", "EXACT_TOTAL", "SUGGEST"];

interface Options {
  /** Whether the deployment can serve queries. */
  available?: boolean;
  /** Status code for GET /search/assets. 200 unless set. */
  searchStatus?: number;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** Two assets in the collection; only one of them is a search hit. */
const ASSETS = [
  {
    uuid: "asset-1",
    file: { mimeType: "image/jpeg", filename: "drone-footage.jpg", size: 4096, origin: "upload", firstSeen: "" },
    hashes: { sha512: "hash-1" },
    status: { created: "2026-01-01T00:00:00Z" },
  },
  {
    uuid: "asset-2",
    file: { mimeType: "video/mp4", filename: "sunset-clip.mp4", size: 8192, origin: "upload", firstSeen: "" },
    hashes: { sha512: "hash-2" },
    status: { created: "2026-01-02T00:00:00Z" },
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

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, available
      ? { provider: "postgres", available: true, capabilities: POSTGRES_CAPABILITIES, documentCount: 120, dirtyCount: 0 }
      : { provider: "none", available: false, reason: "Search is disabled on this deployment.", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  await page.route(/\/api\/v1\/search\/assets/, (route) => {
    const url = new URL(route.request().url());
    recorder.search.push(url.search);

    if (options.searchStatus && options.searchStatus !== 200) {
      return json(route, { message: "Search provider is unavailable" }, options.searchStatus);
    }

    return json(route, {
      // A hit is not an asset: no tags, no dimensions, no library.
      data: [{
        type: "asset",
        uuid: "asset-9",
        assetUuid: "asset-9",
        score: 0.94,
        title: "indexed-only.jpg",
        subtitle: "Campaign Media",
        mimeType: "image/jpeg",
        size: 2048,
        sortDate: 1767225600.5,
      }],
      _metainfo: {
        totalHits: 42, totalExact: true, perPage: 100, offset: 0, tookMs: 3,
        provider: "postgres", capabilities: POSTGRES_CAPABILITIES, warnings: [],
      },
    });
  });

  await page.route(/\/api\/v1\/assets(\?|$)/, (route) => {
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
 * Open the asset browser directly.
 *
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load
 * the URL first and sign in on it — the router resolves to the route, not to the root.
 */
async function openAssets(page: Page) {
  await page.goto("/ui/assets");
  await signIn(page);
  await expect(page.getByText("drone-footage.jpg")).toBeVisible({ timeout: 10_000 });
}

function searchBox(page: Page) {
  return page.getByPlaceholder(/search assets/i);
}

test.describe("Asset search – mocked e2e", () => {

  test("typing issues a /search/assets query, not a local filter", async ({ page }) => {
    const recorder = await installMocks(page);
    await openAssets(page);

    await searchBox(page).fill("drone");

    await expect(page.getByText("indexed-only.jpg")).toBeVisible({ timeout: 10_000 });
    expect(recorder.search).toHaveLength(1);
    expect(recorder.search[0]).toContain("q=drone");
    // A hit the loaded page never contained is on screen — proof the server answered.
    await expect(page.getByText("drone-footage.jpg")).toBeHidden();
  });

  test("the count switches to the server's hit total", async ({ page }) => {
    await installMocks(page);
    await openAssets(page);

    await expect(page.getByTestId("assets-count")).toHaveText("2 assets");
    await searchBox(page).fill("drone");
    await expect(page.getByTestId("assets-count")).toHaveText("42 results", { timeout: 10_000 });
  });

  test("the type filter travels as ?mime= instead of being applied locally", async ({ page }) => {
    const recorder = await installMocks(page);
    await openAssets(page);

    await searchBox(page).fill("drone");
    await expect(page.getByText("indexed-only.jpg")).toBeVisible({ timeout: 10_000 });

    await page.getByRole("combobox").first().click();
    await page.getByRole("option", { name: "Image" }).click();

    await expect.poll(() => recorder.search.some(q => q.includes("mime=image%2F"))).toBe(true);
  });

  test("keystrokes are debounced into one request", async ({ page }) => {
    const recorder = await installMocks(page);
    await openAssets(page);

    // pressSequentially types character by character, which is what the debounce is for.
    await searchBox(page).pressSequentially("drone", { delay: 30 });
    await expect(page.getByText("indexed-only.jpg")).toBeVisible({ timeout: 10_000 });

    expect(recorder.search).toHaveLength(1);
    expect(recorder.search[0]).toContain("q=drone");
  });

  test("clearing the box returns to the browsed collection", async ({ page }) => {
    await installMocks(page);
    await openAssets(page);

    await searchBox(page).fill("drone");
    await expect(page.getByText("indexed-only.jpg")).toBeVisible({ timeout: 10_000 });

    await searchBox(page).fill("");
    await expect(page.getByText("drone-footage.jpg")).toBeVisible();
    await expect(page.getByText("indexed-only.jpg")).toBeHidden();
  });

  test("search mode hides the paging footer — deep paging is the /search screen's job", async ({ page }) => {
    await installMocks(page);
    await openAssets(page);

    await searchBox(page).fill("drone");
    await expect(page.getByText("indexed-only.jpg")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("assets-paging")).toBeHidden();
  });

  test("with search off, the box says so and falls back to the loaded rows", async ({ page }) => {
    const recorder = await installMocks(page, { available: false });
    await openAssets(page);

    await searchBox(page).fill("drone");

    await expect(page.getByTestId("assets-search-degraded")).toBeVisible({ timeout: 10_000 });
    // No request was made, and the local filter still narrowed what is loaded.
    expect(recorder.search).toHaveLength(0);
    await expect(page.getByText("drone-footage.jpg")).toBeVisible();
    await expect(page.getByText("sunset-clip.mp4")).toBeHidden();
  });

  test("a 503 mid-session retracts the search and degrades to the local filter", async ({ page }) => {
    await installMocks(page, { searchStatus: 503 });
    await openAssets(page);

    await searchBox(page).fill("drone");

    await expect(page.getByTestId("assets-search-degraded")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("drone-footage.jpg")).toBeVisible();
  });

  test("a 403 says the search was denied rather than showing an empty catalogue", async ({ page }) => {
    await installMocks(page, { searchStatus: 403 });
    await openAssets(page);

    await searchBox(page).fill("drone");

    await expect(page.getByTestId("assets-no-match")).toContainText(/permission/i, { timeout: 10_000 });
  });
});
