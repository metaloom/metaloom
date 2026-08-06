import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for keyset paging on the list screens.
 *
 * Every Loom collection route caps at 25 rows by default (`QueryParameterKey.LIMIT`) and the UI
 * used to issue bare `fetch`es against them, so a page looked like a collection. These specs pin
 * the fix: the header reports `_metainfo.totalCount`, a "load more" button seeks from
 * `_metainfo.lastUuid`, and the two pages concatenate without duplicates.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const PAGE_SIZE = 100;

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** Query strings seen per collection route, so specs can assert on the wire. */
type Recorder = Record<string, string[]>;

interface Options {
  /** How many rows the fixture pretends to hold. */
  total?: number;
  /** Rows returned per page. Defaults to the whole first page. */
  perPage?: number;
  /** Omit `lastUuid` from the metainfo — the server gave us no cursor to seek from. */
  noCursor?: boolean;
  /** Drop `totalCount` — the "we only know the page came back full" fallback. */
  noTotal?: boolean;
  /** Repeat the seek boundary row on page two, which some routes do. */
  repeatBoundary?: boolean;
}

function asset(index: number) {
  return {
    uuid: `asset-${String(index).padStart(4, "0")}`,
    file: { mimeType: "image/jpeg", filename: `photo-${index}.jpg`, size: 1024, origin: "upload", firstSeen: "" },
    hashes: { sha512: `hash-${index}` },
    status: { created: "2026-01-01T00:00:00Z" },
  };
}

function collection(index: number) {
  return {
    uuid: `collection-${String(index).padStart(4, "0")}`,
    name: `Collection ${index}`,
    status: { created: "2026-01-01T00:00:00Z" },
  };
}

async function installMocks(page: Page, options: Options = {}): Promise<Recorder> {
  const recorder: Recorder = { assets: [], collections: [] };
  const total = options.total ?? 300;
  const perPage = options.perPage ?? PAGE_SIZE;

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  // Search off, so the asset box stays on the browse path these specs are about.
  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  /** Serve one keyset page of `build(i)` rows, honouring `?from=`. */
  const paged = (key: string, build: (i: number) => { uuid: string }) => async (route: Route) => {
    const url = new URL(route.request().url());
    recorder[key].push(url.search);

    const from = url.searchParams.get("from");
    // The cursor is the last uuid of the previous page; rows are ordered, so its index + 1 is
    // where this page starts.
    let start = 0;
    if (from) {
      const index = Number(from.split("-")[1]);
      start = options.repeatBoundary ? index : index + 1;
    }
    const rows = Array.from({ length: Math.min(perPage, Math.max(0, total - start)) }, (_, i) => build(start + i));
    const last = rows[rows.length - 1];

    return json(route, {
      data: rows,
      _metainfo: {
        ...(options.noCursor ? {} : { lastUuid: last?.uuid }),
        perPage,
        ...(options.noTotal ? {} : { totalCount: total }),
      },
    });
  };

  await page.route(/\/api\/v1\/assets(\?|$)/, paged("assets", asset));
  await page.route(/\/api\/v1\/collections(\?|$)/, paged("collections", collection));

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
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load
 * the URL first and sign in on it — the router resolves to the route, not to the root.
 */
async function open(page: Page, path: string) {
  await page.goto(`/ui${path}`);
  await signIn(page);
}

test.describe("List paging – mocked e2e", () => {

  test("the asset list requests a page size instead of taking the server default of 25", async ({ page }) => {
    const recorder = await installMocks(page);
    await open(page, "/assets");

    await expect(page.getByTestId("assets-paging")).toBeVisible({ timeout: 10_000 });
    expect(recorder.assets[0]).toContain(`limit=${PAGE_SIZE}`);
    expect(recorder.assets[0]).not.toContain("from=");
  });

  test("the header reports the collection total, not the number of rows fetched", async ({ page }) => {
    await installMocks(page, { total: 300 });
    await open(page, "/assets");

    await expect(page.getByTestId("assets-count")).toHaveText(/^300 /, { timeout: 10_000 });
  });

  test("the footer states how much of the collection is on screen", async ({ page }) => {
    await installMocks(page, { total: 300 });
    await open(page, "/assets");

    await expect(page.getByTestId("assets-paging-count")).toHaveText("Showing 100 of 300", { timeout: 10_000 });
  });

  test("load more seeks from the last uuid and concatenates without duplicates", async ({ page }) => {
    const recorder = await installMocks(page, { total: 300 });
    await open(page, "/assets");

    await expect(page.getByTestId("assets-paging-button")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("assets-paging-button").click();

    await expect(page.getByTestId("assets-paging-count")).toHaveText("Showing 200 of 300");
    // The second request carries the cursor the first response reported.
    expect(recorder.assets).toHaveLength(2);
    expect(recorder.assets[1]).toContain("from=asset-0099");
    expect(recorder.assets[1]).toContain(`limit=${PAGE_SIZE}`);
  });

  test("a repeated boundary row is dropped rather than shown twice", async ({ page }) => {
    // Some routes treat `?from=` as inclusive, so the seek row comes back on both pages.
    await installMocks(page, { total: 300, repeatBoundary: true });
    await open(page, "/assets");

    await page.getByTestId("assets-paging-button").click();

    // 100 + 100 with one overlap = 199 distinct rows, not 200.
    await expect(page.getByTestId("assets-paging-count")).toHaveText("Showing 199 of 300");
  });

  test("the footer disappears once the whole collection is loaded", async ({ page }) => {
    await installMocks(page, { total: 40 });
    await open(page, "/assets");

    await expect(page.getByTestId("assets-count")).toHaveText(/^40 /, { timeout: 10_000 });
    await expect(page.getByTestId("assets-paging")).toBeHidden();
  });

  test("without a cursor the button is not offered — refetching page one would duplicate rows", async ({ page }) => {
    await installMocks(page, { total: 300, noCursor: true });
    await open(page, "/assets");

    await expect(page.getByTestId("assets-count")).toHaveText(/^300 /, { timeout: 10_000 });
    await expect(page.getByTestId("assets-paging-count")).toHaveText("Showing 100 of 300");
    await expect(page.getByTestId("assets-paging-button")).toBeHidden();
  });

  test("a full page with no total still offers more", async ({ page }) => {
    await installMocks(page, { total: 300, noTotal: true });
    await open(page, "/assets");

    await expect(page.getByTestId("assets-paging-button")).toBeVisible({ timeout: 10_000 });
  });

  test("collections page the same way", async ({ page }) => {
    const recorder = await installMocks(page, { total: 250 });
    await open(page, "/collections");

    await expect(page.getByTestId("collections-count")).toHaveText(/^250 /, { timeout: 10_000 });
    await page.getByTestId("collections-paging-button").click();

    await expect(page.getByTestId("collections-paging-count")).toHaveText("Showing 200 of 250");
    expect(recorder.collections[1]).toContain("from=collection-0099");
  });
});
