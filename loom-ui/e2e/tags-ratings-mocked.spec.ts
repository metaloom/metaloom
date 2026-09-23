import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the rating beside a tag in the tree.
 *
 * A rating is **per user**: `/tags` does not carry one, and there is no bulk route, so the tree
 * asks for each tag's rating separately. That is the reason this is worth a spec of its own —
 * the interesting behaviour is not that stars render but that the screen distinguishes three
 * states it could easily collapse into one: rated, asked-and-unrated, and not-asked-yet. Only the
 * first draws anything.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const RATED_UUID = "aaaaaaaa-0000-0000-0000-000000000001";
const UNRATED_UUID = "aaaaaaaa-0000-0000-0000-000000000002";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface Recorder {
  /** Which tags the screen asked about, so "one request per tag" is observable. */
  ratingReads: string[];
  ratingWrites: { uuid: string; rating: number }[];
}

async function installMocks(page: Page): Promise<Recorder> {
  const rec: Recorder = { ratingReads: [], ratingWrites: [] };
  const ratings: Record<string, number | undefined> = { [RATED_UUID]: 7 };

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // Registered before the list route below, because Playwright matches last-registered first and
  // `/tags/<uuid>/rating` would otherwise be served as a tag listing.
  await page.route(/\/api\/v1\/tags\/[^/]+\/rating$/, route => {
    const uuid = route.request().url().split("/tags/")[1].split("/rating")[0];
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { rating: number };
      rec.ratingWrites.push({ uuid, rating: body.rating });
      ratings[uuid] = body.rating;
      return json(route, { rating: body.rating });
    }
    rec.ratingReads.push(uuid);
    return json(route, ratings[uuid] === undefined ? {} : { rating: ratings[uuid] });
  });

  await page.route(/\/api\/v1\/tags(\?|$)/, route => json(route, {
    data: [
      { uuid: RATED_UUID, name: "chorus", collection: "music" },
      { uuid: UNRATED_UUID, name: "verse", collection: "music" },
    ],
    _metainfo: { totalCount: 2, perPage: 100 },
  }));
  return rec;
}

async function openTags(page: Page) {
  await page.goto("/ui/tags");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByText("chorus")).toBeVisible({ timeout: 10_000 });
}

test.describe("Tag ratings in the tree – mocked", () => {
  test("a rated tag wears its stars in the row; an unrated one wears nothing", async ({ page }) => {
    const rec = await installMocks(page);
    await openTags(page);

    const rated = page.locator(`[data-testid=tag-row-rating][data-tag-id="${RATED_UUID}"]`);
    await expect(rated).toBeVisible({ timeout: 10_000 });
    await expect(rated).toHaveAttribute("data-rating", "7");
    // Ten stars, which is the scale the server enforces — not five with the value halved.
    // Read-only, so MUI draws spans rather than the radio labels the editable control uses.
    await expect(rated.locator(".MuiRating-icon")).toHaveCount(10);
    await expect(rated.locator(".MuiRating-iconFilled")).toHaveCount(7);

    // An empty row rather than ten empty stars: the tree's job is to show at a glance which of
    // three hundred tags anybody has judged, and a row of grey stars on every one defeats it.
    await expect(page.locator(`[data-testid=tag-row-rating][data-tag-id="${UNRATED_UUID}"]`)).toHaveCount(0);

    // One request per tag, and each asked about exactly once — a key present with a null value
    // means "asked, unrated", which is what stops the effect asking again on every render.
    await expect.poll(() => rec.ratingReads.length, { timeout: 5_000 }).toBeGreaterThanOrEqual(2);
    expect(new Set(rec.ratingReads)).toEqual(new Set([RATED_UUID, UNRATED_UUID]));
  });

  test("rating a tag in the detail panel updates the stars beside its row", async ({ page }) => {
    const rec = await installMocks(page);
    await openTags(page);

    await page.getByText("verse").click();
    const panel = page.getByTestId("tags-rating");
    await expect(panel).toBeVisible({ timeout: 5_000 });

    // The row reads a map, the panel a field. They used to be two independent pieces of state,
    // so a rating set here left the row showing whatever it held when the page loaded.
    await panel.locator("label").nth(3).click();
    await expect.poll(() => rec.ratingWrites.length, { timeout: 5_000 }).toBe(1);
    expect(rec.ratingWrites[0]).toEqual({ uuid: UNRATED_UUID, rating: 4 });

    const row = page.locator(`[data-testid=tag-row-rating][data-tag-id="${UNRATED_UUID}"]`);
    await expect(row).toHaveAttribute("data-rating", "4", { timeout: 5_000 });
  });
});
