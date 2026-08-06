import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for lexical search — the sidebar field, the /search view, and the honest-degradation
 * paths. Every `/api/v1/**` call is intercepted; the search routes answer from a small fixture.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

const POSTGRES_CAPABILITIES = ["LEXICAL", "PHRASE", "FUZZY", "HIGHLIGHT", "FACETS", "EXACT_TOTAL", "SUGGEST"];

interface Options {
  /** What GET /search/status answers. */
  status?: Record<string, unknown>;
  /** Status code for GET /search/results. 200 unless set. */
  resultsStatus?: number;
  /** Body for a non-200 results response. */
  resultsError?: string;
  /** Total hit count reported by the fixture, so the pager can be exercised. */
  totalHits?: number;
  /** Suggestion payload. Pass {} to model the absent-`data` case. */
  suggestions?: Record<string, unknown>;
  /** Facet block returned with the results. */
  facets?: Record<string, { value: string; count: number }[]>;
  /** Permission-narrowing warnings. */
  warnings?: string[];
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function hit(index: number, overrides: Record<string, unknown> = {}) {
  return {
    type: "asset",
    uuid: `asset-${index}`,
    assetUuid: `asset-${index}`,
    score: 1 - index / 100,
    title: `sunset-beach-${index}.jpg`,
    subtitle: "Campaign Media",
    matchedIn: "title",
    highlights: [`a <b>quarterly</b> report for asset ${index}`],
    mimeType: "image/jpeg",
    ...overrides,
  };
}

/** Query strings seen by each search route, so specs can assert on the wire. */
interface Recorder {
  results: string[];
  suggestions: string[];
}

async function installMocks(page: Page, options: Options = {}): Promise<Recorder> {
  const recorder: Recorder = { results: [], suggestions: [] };
  const totalHits = options.totalHits ?? 2;

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, options.status ?? {
      provider: "postgres",
      available: true,
      capabilities: POSTGRES_CAPABILITIES,
      documentCount: 120,
      dirtyCount: 0,
    }));

  await page.route(/\/api\/v1\/search\/suggestions/, (route) => {
    recorder.suggestions.push(route.request().url());
    return json(route, options.suggestions ?? {
      data: [
        { text: "sunset-beach.jpg", type: "asset", uuid: "asset-1", score: 0.9 },
        { text: "sunrise-ridge.jpg", type: "asset", uuid: "asset-2", score: 0.7 },
      ],
    });
  });

  await page.route(/\/api\/v1\/search\/results/, (route) => {
    const url = new URL(route.request().url());
    recorder.results.push(url.search);

    if (options.resultsStatus && options.resultsStatus !== 200) {
      return json(route, { message: options.resultsError ?? "boom" }, options.resultsStatus);
    }

    const offset = Number(url.searchParams.get("offset") ?? 0);
    const term = url.searchParams.get("q") ?? "";
    // One spec drives a deliberately empty result set.
    const empty = term === "nothingmatchesthis";
    const remaining = Math.max(0, totalHits - offset);
    const pageSize = Math.min(25, remaining);

    return json(route, {
      data: empty ? [] : Array.from({ length: pageSize }, (_, i) => hit(offset + i)),
      _metainfo: {
        totalHits: empty ? 0 : totalHits,
        totalExact: true,
        perPage: 25,
        offset,
        tookMs: 4,
        provider: "postgres",
        capabilities: POSTGRES_CAPABILITIES,
        warnings: options.warnings ?? [],
      },
      ...(options.facets ? { facets: options.facets } : {}),
    });
  });

  return recorder;
}

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

test.describe("Search – mocked e2e", () => {

  test("the sidebar field appears when a provider is available", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await expect(page.getByTestId("global-search-input")).toBeVisible({ timeout: 10_000 });
  });

  test("the sidebar field is hidden when search is unavailable", async ({ page }) => {
    await installMocks(page, {
      status: { provider: "none", available: false, reason: "Search is disabled on this deployment.", capabilities: [], documentCount: 0, dirtyCount: 0 },
    });
    await login(page);

    // Wait for something that is always there, so this is not just an early assertion.
    await expect(page.getByRole("button", { name: "Assets" }).first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("global-search-input")).toHaveCount(0);
  });

  test("a failing status call hides the box instead of blanking the app", async ({ page }) => {
    await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
    await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
    await page.route(/\/api\/v1\/me$/, (route) => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
    await page.route(/\/api\/v1\/search\/status$/, (route) => json(route, { message: "Missing permission READ_SEARCH" }, 403));
    await login(page);

    await expect(page.getByRole("button", { name: "Assets" }).first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("global-search-input")).toHaveCount(0);
  });

  test("typing fires one debounced suggestions request and opens the dropdown", async ({ page }) => {
    const recorder = await installMocks(page);
    await login(page);

    await page.getByTestId("global-search-input").fill("sun");
    await expect(page.getByTestId("global-search-suggestions")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("global-search-suggestion-0")).toContainText("sunset-beach.jpg");

    // Debounced: the four keystrokes of "sun" collapse into a single call.
    expect(recorder.suggestions).toHaveLength(1);
  });

  test("a single character fires no suggestions request", async ({ page }) => {
    const recorder = await installMocks(page);
    await login(page);

    await page.getByTestId("global-search-input").fill("s");
    await page.waitForTimeout(600);

    expect(recorder.suggestions).toHaveLength(0);
    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);
  });

  test("a suggestions response without a data key does not crash the dropdown", async ({ page }) => {
    // The list envelope omits `data` entirely when nothing matched.
    await installMocks(page, { suggestions: {} });
    await login(page);

    await page.getByTestId("global-search-input").fill("zzz");
    await page.waitForTimeout(600);

    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);
    await expect(page.getByTestId("global-search-input")).toBeVisible();
  });

  test("Enter navigates to the search route with the term", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await page.getByTestId("global-search-input").fill("quarterly");
    await page.getByTestId("global-search-input").press("Enter");

    await expect(page).toHaveURL(/\/ui\/search\?q=quarterly$/);
    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 10_000 });
  });

  test("clicking a suggestion searches for its text", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await page.getByTestId("global-search-input").fill("sun");
    await page.getByTestId("global-search-suggestion-0").click();

    await expect(page).toHaveURL(/\/ui\/search\?q=sunset-beach\.jpg$/);
  });

  test("the view seeds its field from the q parameter", async ({ page }) => {
    await installMocks(page);
    await openSearch(page, "?q=quarterly");

    await expect(page.getByTestId("search-input")).toHaveValue("quarterly", { timeout: 10_000 });
    await expect(page.getByTestId("search-results")).toBeVisible();
  });

  test("the empty state shows only when there is no query", async ({ page }) => {
    await installMocks(page);
    await openSearch(page);

    await expect(page.getByTestId("search-empty-state")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("search-syntax-hint")).toBeVisible();
  });

  test("a zero-result query shows the inline hint, not the empty state", async ({ page }) => {
    await installMocks(page);
    await openSearch(page, "?q=nothingmatchesthis");

    await expect(page.getByTestId("search-no-results")).toBeVisible({ timeout: 10_000 });
    // The empty state offers "start here" framing and must never appear for a searched result.
    await expect(page.getByTestId("search-empty-state")).toHaveCount(0);
  });

  test("a type chip narrows the types parameter and resets the offset", async ({ page }) => {
    const recorder = await installMocks(page, { totalHits: 200 });
    await openSearch(page, "?q=quarterly&offset=25");
    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("search-type-transcript").click();
    await expect(page).toHaveURL(/types=transcript/);
    await expect(page).not.toHaveURL(/offset=/);

    const last = recorder.results[recorder.results.length - 1];
    expect(new URLSearchParams(last).get("types")).toBe("transcript");
  });

  test("unindexed entity types are not offered as filters", async ({ page }) => {
    await installMocks(page);
    await openSearch(page, "?q=quarterly");
    await expect(page.getByTestId("search-type-filter")).toBeVisible({ timeout: 10_000 });

    // The indexer builds no documents for these, so a chip would be a guaranteed-empty filter.
    await expect(page.getByTestId("search-type-detection")).toHaveCount(0);
    await expect(page.getByTestId("search-type-segment")).toHaveCount(0);
    await expect(page.getByTestId("search-type-asset")).toBeVisible();
  });

  test("facet chips render and apply a filter when FACETS is advertised", async ({ page }) => {
    const recorder = await installMocks(page, {
      facets: { mime_type: [{ value: "image/jpeg", count: 7 }] },
    });
    await openSearch(page, "?q=quarterly");

    await expect(page.getByTestId("search-facets")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("search-facet-mime_type-image/jpeg").click();

    await expect(page).toHaveURL(/mime=image%2Fjpeg/);
    const last = recorder.results[recorder.results.length - 1];
    expect(new URLSearchParams(last).get("mime")).toBe("image/jpeg");
  });

  test("facet chips are absent when FACETS is not advertised", async ({ page }) => {
    const recorder = await installMocks(page, {
      status: { provider: "postgres", available: true, capabilities: ["LEXICAL", "SUGGEST"], documentCount: 1, dirtyCount: 0 },
      facets: { mime_type: [{ value: "image/jpeg", count: 7 }] },
    });
    await openSearch(page, "?q=quarterly");
    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 10_000 });

    await expect(page.getByTestId("search-facets")).toHaveCount(0);
    // And the request does not ask for facets it cannot use.
    expect(new URLSearchParams(recorder.results[0]).get("facets")).toBeNull();
  });

  test("highlight fragments render as marks and never as live markup", async ({ page }) => {
    await installMocks(page, { totalHits: 1 });
    await page.route(/\/api\/v1\/search\/results/, (route) =>
      json(route, {
        data: [hit(0, { highlights: ['<img src=x onerror=alert(1)> a <b>quarterly</b> note'] })],
        _metainfo: { totalHits: 1, totalExact: true, perPage: 25, offset: 0, tookMs: 3, provider: "postgres", capabilities: POSTGRES_CAPABILITIES, warnings: [] },
      }));
    await openSearch(page, "?q=quarterly");

    const snippet = page.getByTestId("search-hit-snippet").first();
    await expect(snippet).toBeVisible({ timeout: 10_000 });
    await expect(snippet.locator("mark")).toHaveText("quarterly");
    // ts_headline does not escape the source document; injecting it would execute this.
    await expect(snippet.locator("img")).toHaveCount(0);
    await expect(snippet).toContainText("<img src=x onerror=alert(1)>");
  });

  test("the pager advances the offset by a page", async ({ page }) => {
    const recorder = await installMocks(page, { totalHits: 200 });
    await openSearch(page, "?q=quarterly");
    await expect(page.getByTestId("search-pager")).toBeVisible({ timeout: 10_000 });

    await expect(page.getByTestId("search-pager-prev")).toBeDisabled();
    await page.getByTestId("search-pager-next").click();

    await expect(page).toHaveURL(/offset=25/);
    expect(new URLSearchParams(recorder.results[recorder.results.length - 1]).get("offset")).toBe("25");
  });

  test("Next is disabled at the deep-paging cap and issues no request", async ({ page }) => {
    const recorder = await installMocks(page, { totalHits: 100_000 });
    await openSearch(page, "?q=quarterly&offset=1000");
    await expect(page.getByTestId("search-pager")).toBeVisible({ timeout: 10_000 });

    await expect(page.getByTestId("search-pager-next")).toBeDisabled();
    await expect(page.getByTestId("search-pager-depth-limit")).toBeVisible();

    const before = recorder.results.length;
    await page.getByTestId("search-pager-next").click({ force: true }).catch(() => {});
    await page.waitForTimeout(300);
    expect(recorder.results).toHaveLength(before);
  });

  test("an offset past the cap is clamped rather than sent", async ({ page }) => {
    const recorder = await installMocks(page, { totalHits: 100_000 });
    await openSearch(page, "?q=quarterly&offset=99999");
    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 10_000 });

    // The provider answers 400 past 1000, so the client must correct it before asking.
    expect(new URLSearchParams(recorder.results[0]).get("offset")).toBe("1000");
  });

  test("permission narrowing warnings are surfaced", async ({ page }) => {
    await installMocks(page, {
      warnings: ["Some entity types were excluded from this search: missing permission READ_TAG."],
    });
    await openSearch(page, "?q=quarterly");

    await expect(page.getByTestId("search-warnings")).toContainText("READ_TAG", { timeout: 10_000 });
  });

  test("a 503 mid-session toasts, shows the panel and retracts the sidebar field", async ({ page }) => {
    await installMocks(page, {
      resultsStatus: 503,
      resultsError: "Search is unavailable: Search is disabled on this deployment.",
    });
    await login(page);
    await expect(page.getByTestId("global-search-input")).toBeVisible({ timeout: 10_000 });

    // Navigate through the field itself: a full page load would drop the in-memory token, and
    // this spec is specifically about the field disappearing without one.
    await page.getByTestId("global-search-input").fill("quarterly");
    await page.getByTestId("global-search-input").press("Enter");

    await expect(page.getByTestId("search-unavailable")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("search-unavailable-provider")).toContainText("postgres");
    await expect(page.getByTestId("global-search-input")).toHaveCount(0);
  });

  test("a 400 renders the server message inline without crashing the view", async ({ page }) => {
    await installMocks(page, {
      resultsStatus: 400,
      resultsError: "The postgres search provider supports only LEXICAL mode. Requested: SEMANTIC.",
    });
    await openSearch(page, "?q=quarterly");

    // The error code never reaches the wire, so the message is the whole payload.
    await expect(page.getByTestId("search-error")).toContainText("only LEXICAL mode", { timeout: 10_000 });
    await expect(page.getByTestId("search-input")).toBeVisible();
  });

  test("a 403 explains the permission rather than showing an empty index", async ({ page }) => {
    await installMocks(page, {
      resultsStatus: 403,
      resultsError: "You may search, but you may not read any of the requested entity types.",
    });
    await openSearch(page, "?q=quarterly");

    await expect(page.getByTestId("search-error")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("search-no-results")).toHaveCount(0);
  });

  test("no semantic toggle is rendered for the Postgres capability set", async ({ page }) => {
    const recorder = await installMocks(page);
    await openSearch(page, "?q=quarterly");
    await expect(page.getByTestId("search-results")).toBeVisible({ timeout: 10_000 });

    // The server answers 400 for SEMANTIC; that rejection must stay invisible.
    await expect(page.getByTestId("search-mode-toggle")).toHaveCount(0);
    expect(new URLSearchParams(recorder.results[0]).get("mode")).toBeNull();
  });

  test("a mode toggle appears when the provider advertises SEMANTIC", async ({ page }) => {
    await installMocks(page, {
      status: { provider: "future", available: true, capabilities: [...POSTGRES_CAPABILITIES, "SEMANTIC"], documentCount: 1, dirtyCount: 0 },
    });
    await openSearch(page, "?q=quarterly");

    await expect(page.getByTestId("search-mode-toggle")).toBeVisible({ timeout: 10_000 });
  });

  test("the collapsed rail shows a search button instead of the input", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await expect(page.getByTestId("global-search-input")).toBeVisible({ timeout: 10_000 });

    await page.getByRole("button", { name: /collapse/i }).click();

    await expect(page.getByTestId("global-search-input")).toHaveCount(0);
    await expect(page.getByTestId("global-search-button")).toBeVisible();
    await page.getByTestId("global-search-button").click();
    await expect(page).toHaveURL(/\/ui\/search$/);
  });

  test("active filters can be cleared", async ({ page }) => {
    await installMocks(page);
    await openSearch(page, "?q=quarterly&types=asset&mime=image%2Fjpeg");
    await expect(page.getByTestId("search-active-filters")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("search-filters-clear").click();

    await expect(page).not.toHaveURL(/types=/);
    await expect(page).not.toHaveURL(/mime=/);
  });
});
