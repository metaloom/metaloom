import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the sidebar's global search field, beyond the happy path in search-mocked.
 *
 * The field is a typeahead, so most of what can go wrong with it is about *time*: an answer that
 * arrives after the question stopped mattering. The suggestions route here is held open on demand,
 * so a spec can type, change its mind, and only then let the stale answer land.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const SUGGESTIONS = {
  data: [
    { text: "street-crossing.jpg", type: "asset", uuid: "asset-1", score: 0.9 },
    { text: "sunrise-ridge.jpg", type: "asset", uuid: "asset-2", score: 0.7 },
    { text: "summer", type: "tag", uuid: "tag-1", score: 0.5 },
  ],
};

interface Gate {
  /** URLs the suggestions route was asked for. */
  requests: string[];
  /** How many suggestion responses have been handed back to the browser. */
  served: number;
  /** While true, suggestion requests are parked until {@link release} is called. */
  hold: boolean;
  release: () => void;
}

async function installMocks(page: Page): Promise<Gate> {
  let parked: Array<() => void> = [];
  const gate: Gate = {
    requests: [],
    served: 0,
    hold: false,
    release: () => { const p = parked; parked = []; p.forEach(fn => fn()); },
  };

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, route => json(route, {
    provider: "postgres", available: true, capabilities: ["LEXICAL", "SUGGEST"], documentCount: 3, dirtyCount: 0,
  }));
  await page.route(/\/api\/v1\/search\/results/, route => json(route, {
    data: [], _metainfo: { totalHits: 0, totalExact: true, perPage: 25, offset: 0, tookMs: 1, provider: "postgres", capabilities: [], warnings: [] },
  }));
  await page.route(/\/api\/v1\/search\/suggestions/, async route => {
    gate.requests.push(route.request().url());
    if (gate.hold) {
      await new Promise<void>(resolve => parked.push(resolve));
    }
    await json(route, SUGGESTIONS);
    gate.served += 1;
  });
  return gate;
}

async function login(page: Page) {
  await page.goto("/ui/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("global-search-input")).toBeVisible({ timeout: 10_000 });
}

test.describe("Global search field – mocked", () => {
  test("an answer that lands after the field was cleared does not reopen the dropdown", async ({ page }) => {
    const gate = await installMocks(page);
    await login(page);
    const input = page.getByTestId("global-search-input");

    gate.hold = true;
    await input.fill("su");
    await expect.poll(() => gate.requests.length, { timeout: 5_000 }).toBe(1);

    // Changed their mind while the request was out.
    await input.fill("");
    gate.release();

    // The answer has been served; give the client a moment to (wrongly) paint it. A negative
    // assertion needs a bounded wait - there is no event for "did not reopen".
    await expect.poll(() => gate.served, { timeout: 5_000 }).toBeGreaterThan(0);
    await page.waitForTimeout(300);
    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);
    await expect(input).toHaveValue("");
  });

  test("an answer that lands after Enter does not float over the results page", async ({ page }) => {
    const gate = await installMocks(page);
    await login(page);
    const input = page.getByTestId("global-search-input");

    gate.hold = true;
    await input.fill("sun");
    await expect.poll(() => gate.requests.length, { timeout: 5_000 }).toBe(1);
    await input.press("Enter");
    await expect(page).toHaveURL(/\/ui\/search\?q=sun$/);

    gate.release();
    await expect.poll(() => gate.served, { timeout: 5_000 }).toBeGreaterThan(0);
    await page.waitForTimeout(300);
    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);
  });

  test("Escape closes the dropdown and an answer in flight does not reopen it", async ({ page }) => {
    const gate = await installMocks(page);
    await login(page);
    const input = page.getByTestId("global-search-input");

    await input.fill("su");
    await expect(page.getByTestId("global-search-suggestions")).toBeVisible({ timeout: 5_000 });
    await input.press("Escape");
    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);

    gate.hold = true;
    await input.fill("sun");
    await expect.poll(() => gate.requests.length, { timeout: 5_000 }).toBe(2);
    await input.press("Escape");
    gate.release();
    await expect.poll(() => gate.served, { timeout: 5_000 }).toBe(2);
    await page.waitForTimeout(300);
    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);
  });

  test("arrow keys walk the suggestions, wrap at both ends, and Enter searches the highlighted one", async ({ page }) => {
    await installMocks(page);
    await login(page);
    const input = page.getByTestId("global-search-input");

    await input.fill("su");
    await expect(page.getByTestId("global-search-suggestions")).toBeVisible({ timeout: 5_000 });
    const item = (i: number) => page.getByTestId(`global-search-suggestion-${i}`);
    await expect(item(0)).not.toHaveClass(/Mui-selected/);

    // Up from "nothing highlighted" goes to the last entry, not the first.
    await input.press("ArrowUp");
    await expect(item(2)).toHaveClass(/Mui-selected/);
    await input.press("ArrowDown");
    await expect(item(0)).toHaveClass(/Mui-selected/);
    await input.press("ArrowDown");
    await expect(item(1)).toHaveClass(/Mui-selected/);
    await expect(item(0)).not.toHaveClass(/Mui-selected/);

    // Enter searches for the highlighted suggestion's text, not for what was typed.
    await input.press("Enter");
    await expect(page).toHaveURL(/\/ui\/search\?q=sunrise-ridge\.jpg$/);
    await expect(page.getByTestId("global-search-suggestions")).toHaveCount(0);
  });

  test("the suggestion's type is labelled, and a whitespace-only Enter goes nowhere", async ({ page }) => {
    await installMocks(page);
    await login(page);
    const input = page.getByTestId("global-search-input");

    await input.fill("su");
    await expect(page.getByTestId("global-search-suggestion-2")).toContainText("summer");
    // The type caption is translated, never the raw key.
    await expect(page.getByTestId("global-search-suggestion-2")).not.toContainText("search.types.");

    await input.fill("   ");
    await input.press("Enter");
    await expect(page).toHaveURL(/\/ui\/$/);
  });
});
