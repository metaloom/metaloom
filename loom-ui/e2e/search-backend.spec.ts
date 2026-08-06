import { test, expect, Page, APIRequestContext } from "@playwright/test";

/**
 * Backend e2e for lexical search, against a running demo container.
 *
 * Prerequisites:
 *   ./start-postgres.sh && ./start-demo.sh
 *   VITE_API_BASE_URL=/api/v1 VITE_PROXY_TARGET=http://localhost:8092
 *
 * The assertions lean on the demo corpus seeded by DemoDatabaseInitializer — transcript text
 * ("quarterly update", "dividends", "championship finals") and filenames (sunset-beach.jpg,
 * drone-coastal.mp4). Search documents come from the V2.59 triggers, which the demo seeding
 * cannot bypass, so no search-specific fixture is needed.
 */

const API = "http://localhost:8092/api/v1";

async function signIn(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function login(page: Page) {
  await page.goto("/");
  await signIn(page);
}

/**
 * Open a /search URL directly.
 *
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load
 * the URL first and sign in on it — the router resolves to the route, not to the root.
 */
async function openSearch(page: Page, search = "") {
  await page.goto(`/ui/search${search}`);
  await signIn(page);
}

/** Run a query by loading its URL, signing in if the session was dropped by the page load. */
async function searchFor(page: Page, term: string) {
  await page.goto(`/ui/search?q=${encodeURIComponent(term)}`);
  if (await page.getByPlaceholder("Username").count() > 0) await signIn(page);
  await expect(page.getByTestId("search-input")).toHaveValue(term, { timeout: 10_000 });
  // The summary is rendered for any completed query, zero hits included — the no-results hint is
  // shown alongside it, so waiting on both would be a strict-mode collision.
  await expect(page.getByTestId("search-summary")).toBeVisible({ timeout: 15_000 });
}

/** Titles of the hits currently on the page. */
async function hitTitles(page: Page): Promise<string[]> {
  if (await page.getByTestId("search-no-results").count() > 0) return [];
  return page.getByTestId("search-hit").allInnerTexts();
}

/** A token for the direct-API assertions, which have no UI affordance to click. */
async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/login`, { data: { username: "admin", password: "finger" } });
  expect(res.ok()).toBeTruthy();
  return (await res.json()).token;
}

test.describe("Search - backend e2e", () => {

  test("search is available and the sidebar field is shown", async ({ page }) => {
    await login(page);
    await expect(page.getByTestId("global-search-input")).toBeVisible({ timeout: 10_000 });

    await openSearch(page);
    await expect(page.getByTestId("search-unavailable")).toHaveCount(0);
    await expect(page.getByTestId("search-empty-state")).toBeVisible({ timeout: 10_000 });
  });

  test("a plain term finds transcript content", async ({ page }) => {
    await searchFor(page, "dividends");

    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 15_000 });
    expect((await hitTitles(page)).length).toBeGreaterThan(0);
  });

  test("a quoted phrase matches only the real phrase", async ({ page }) => {
    await searchFor(page, '"quarterly update"');
    const phraseHits = (await hitTitles(page)).length;
    expect(phraseHits).toBeGreaterThan(0);

    // The same words in the wrong order are not the phrase.
    await searchFor(page, '"update quarterly"');
    expect((await hitTitles(page)).length).toBeLessThan(phraseHits);
  });

  test("an or query returns at least as much as either term alone", async ({ page }) => {
    await searchFor(page, "dividends");
    const dividends = (await hitTitles(page)).length;

    await searchFor(page, "championship");
    const championship = (await hitTitles(page)).length;

    await searchFor(page, "dividends or championship");
    const either = (await hitTitles(page)).length;

    expect(either).toBeGreaterThanOrEqual(Math.max(dividends, championship));
  });

  test("a negation excludes matching documents", async ({ page }) => {
    await searchFor(page, "quarterly");
    const all = await hitTitles(page);
    expect(all.length).toBeGreaterThan(0);

    await searchFor(page, "quarterly -podcast");
    const narrowed = await hitTitles(page);

    expect(narrowed.length).toBeLessThanOrEqual(all.length);
    expect(narrowed.join(" ")).not.toContain("podcast-episode1.mp3");
  });

  test("a filename term finds the asset", async ({ page }) => {
    await searchFor(page, "sunset");

    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 15_000 });
    expect((await hitTitles(page)).join(" ")).toContain("sunset-beach");
  });

  test("the type filter narrows the result set to one kind", async ({ page }) => {
    await openSearch(page, "?q=quarterly&types=asset");
    await expect(page.getByTestId("search-summary")).toBeVisible({ timeout: 15_000 });

    const rows = page.getByTestId("search-hit");
    const count = await rows.count();
    for (let i = 0; i < count; i++) {
      await expect(rows.nth(i)).toHaveAttribute("data-hit-type", "asset");
    }
  });

  test("highlighted snippets render as marks", async ({ page }) => {
    await searchFor(page, "dividends");

    const snippet = page.getByTestId("search-hit-snippet").first();
    await expect(snippet).toBeVisible({ timeout: 15_000 });
    await expect(snippet.locator("mark").first()).toBeVisible();
  });

  test("typeahead suggests an asset from a prefix", async ({ page }) => {
    await login(page);

    await page.getByTestId("global-search-input").fill("dro");
    await expect(page.getByTestId("global-search-suggestions")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("global-search-suggestions")).toContainText("drone-coastal");
  });

  // ── Contract checks with no UI affordance ───────────────────────────
  // These pin the server behaviour the UI deliberately hides. If any of them starts passing
  // differently, a control currently withheld from the user may need to appear.

  test("mode=SEMANTIC is a handled 400 naming the provider", async ({ request }) => {
    const token = await apiToken(request);

    const res = await request.get(`${API}/search/results?q=quarterly&mode=SEMANTIC`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.message).toContain("LEXICAL");
    // The error code is discarded by the failure handler, so status is the only signal.
    expect(body.code).toBeUndefined();
  });

  test("paging past the offset cap is a 400, which is why the pager clamps", async ({ request }) => {
    const token = await apiToken(request);

    const res = await request.get(`${API}/search/results?q=quarterly&offset=2000`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.status()).toBe(400);
    expect((await res.json()).message).toContain("1000");
  });

  test("status advertises the postgres capability set without SEMANTIC", async ({ request }) => {
    const token = await apiToken(request);

    const res = await request.get(`${API}/search/status`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.ok()).toBeTruthy();
    const status = await res.json();
    expect(status.available).toBe(true);
    expect(status.provider).toBe("postgres");
    expect(status.capabilities).toContain("FACETS");
    expect(status.capabilities).not.toContain("SEMANTIC");
    expect(status.capabilities).not.toContain("DEEP_PAGING");
  });

  test("the facet map echoes the requested facet spellings", async ({ request }) => {
    const token = await apiToken(request);

    const res = await request.get(
      `${API}/search/results?q=quarterly&facets=mime_type,entity_type`,
      { headers: { Authorization: `Bearer ${token}` } },
    );

    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Object.keys(body.facets ?? {})).toEqual(expect.arrayContaining(["mime_type", "entity_type"]));
  });

  test("a blank term is rejected, which is why the client refuses it locally", async ({ request }) => {
    const token = await apiToken(request);

    const res = await request.get(`${API}/search/results?q=`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.status()).toBe(400);
    expect((await res.json()).message).toContain("(q)");
  });
});
