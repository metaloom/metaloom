import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for a single search hit row (SearchHitRow) and the "search unavailable" panel
 * (SearchUnavailable): where a click goes, how the timecode and subtitle render, and that the
 * server's reason text reaches the screen.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const CAPABILITIES = ["LEXICAL", "PHRASE", "FUZZY", "HIGHLIGHT", "EXACT_TOTAL", "SUGGEST"];

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page, hits: Record<string, unknown>[], status?: Record<string, unknown>) {
  await page.route(/\/api\/v1\//, (route) => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, (route) => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, (route) => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, (route) =>
    json(route, status ?? { provider: "postgres", available: true, capabilities: CAPABILITIES, documentCount: 3, dirtyCount: 0 }));
  await page.route(/\/api\/v1\/search\/results/, (route) =>
    json(route, {
      data: hits,
      _metainfo: {
        totalHits: hits.length, totalExact: true, perPage: 25, offset: 0, tookMs: 2,
        provider: "postgres", capabilities: CAPABILITIES, warnings: [],
      },
    }));
}

async function openSearch(page: Page, search: string) {
  await page.goto(`/ui/search${search}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

const transcriptHit = {
  type: "transcript",
  uuid: "transcript-1",
  assetUuid: "asset-episode",
  score: 0.9,
  title: "episode-07.mp4",
  subtitle: "Season 2 transcript",
  matchedIn: "content",
  highlights: ["the <b>harbour</b> at dawn"],
  timeFromMs: 3_725_400,
};

const assetHit = {
  type: "asset",
  uuid: "asset-photo",
  assetUuid: "asset-photo",
  score: 0.8,
  title: "harbour.jpg",
  matchedIn: "title",
  mimeType: "image/jpeg",
};

test.describe("Search hit rows – mocked e2e", () => {

  test("a transcript hit renders its timecode and subtitle, and a click opens the asset at that time", async ({ page }) => {
    await installMocks(page, [transcriptHit]);
    await openSearch(page, "?q=harbour");

    const row = page.getByTestId("search-hit").first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toHaveAttribute("data-hit-type", "transcript");
    // 3 725 400 ms = 1h 2m 5.4s, formatted h:mm:ss once past the hour.
    await expect(row.getByTestId("search-hit-timecode")).toHaveText("1:02:05");
    await expect(row.getByText("Season 2 transcript", { exact: true })).toBeVisible();

    await row.click();
    // Seconds, floored: the player's currentTime unit.
    await expect(page).toHaveURL(/\/ui\/assets\/asset-episode\?t=3725$/);
  });

  test("an asset hit navigates to its own detail route without a time parameter", async ({ page }) => {
    await installMocks(page, [assetHit]);
    await openSearch(page, "?q=harbour");

    const row = page.getByTestId("search-hit").first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    // No offset, no subtitle: neither is rendered as an empty element.
    await expect(row.getByTestId("search-hit-timecode")).toHaveCount(0);
    await expect(row.locator(".MuiTypography-caption")).toHaveCount(0);

    await row.click();
    await expect(page).toHaveURL(/\/ui\/assets\/asset-photo$/);
  });

  test("a hit at offset zero opens the asset without ?t=", async ({ page }) => {
    await installMocks(page, [{ ...transcriptHit, timeFromMs: 0 }]);
    await openSearch(page, "?q=harbour");

    const row = page.getByTestId("search-hit").first();
    await expect(row.getByTestId("search-hit-timecode")).toHaveText("0:00", { timeout: 10_000 });
    await row.click();
    await expect(page).toHaveURL(/\/ui\/assets\/asset-episode$/);
  });

  test("a transcript hit without an assetUuid is not a link", async ({ page }) => {
    const { assetUuid: _omitted, ...orphan } = transcriptHit;
    await installMocks(page, [orphan, assetHit]);
    await openSearch(page, "?q=harbour");

    const rows = page.getByTestId("search-hit");
    await expect(rows).toHaveCount(2, { timeout: 10_000 });
    await expect(rows.nth(0)).toHaveCSS("cursor", "default");
    await rows.nth(0).click();
    await expect(page).toHaveURL(/\/ui\/search\?q=harbour$/);

    // The fence: the second row is a link, so a navigation after both clicks proves the first
    // click had its chance to navigate and did not.
    await rows.nth(1).click();
    await expect(page).toHaveURL(/\/ui\/assets\/asset-photo$/);
  });
});

test.describe("Search unavailable – mocked e2e", () => {

  test("the panel names the provider and the server's reason", async ({ page }) => {
    await installMocks(page, [], {
      provider: "none",
      available: false,
      reason: "Search is disabled on this deployment.",
      capabilities: [],
      documentCount: 0,
      dirtyCount: 0,
    });
    await openSearch(page, "");

    await expect(page.getByTestId("search-unavailable")).toBeVisible({ timeout: 10_000 });
    const provider = page.getByTestId("search-unavailable-provider");
    await expect(provider).toContainText("none");
    await expect(provider).toContainText("— Search is disabled on this deployment.");
    await expect(page.getByTestId("search-input")).toHaveCount(0);
  });

  test("without a reason the panel shows the provider only", async ({ page }) => {
    await installMocks(page, [], {
      provider: "none", available: false, capabilities: [], documentCount: 0, dirtyCount: 0,
    });
    await openSearch(page, "");

    const provider = page.getByTestId("search-unavailable-provider");
    await expect(provider).toContainText("none", { timeout: 10_000 });
    await expect(provider).not.toContainText("—");
  });
});
