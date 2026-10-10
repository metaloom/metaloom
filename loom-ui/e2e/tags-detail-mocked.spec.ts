import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the tag tree's write paths and its detail panel.
 *
 * Until this spec the only successful-path coverage of `TagsView` was the rating stars
 * (`tags-ratings-mocked.spec.ts`) and the failure toast (`error-feedback-mocked.spec.ts`): create,
 * rename, move-by-save, delete and the search filter had no spec at all. The first test pins a race:
 * the detail panel's rating is one request per click, and an answer for the previously clicked tag
 * used to land in the panel of the tag clicked after it.
 *
 * Routes exercised:
 *   GET/POST   /api/v1/tags
 *   POST/DELETE /api/v1/tags/:uuid
 *   GET        /api/v1/tags/:uuid/rating
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const SLOW_UUID = "aaaaaaaa-0000-0000-0000-000000000001";
const FAST_UUID = "aaaaaaaa-0000-0000-0000-000000000002";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface StoredTag { uuid: string; name: string; collection: string }

interface Recorder {
  creates: Record<string, unknown>[];
  updates: { uuid: string; body: Record<string, unknown> }[];
  deletes: string[];
  /** Rating answers for SLOW_UUID actually delivered: one from the tree, one per panel click. */
  slowServed: number;
}

async function installMocks(page: Page, opts: { slowRatingMs?: number } = {}): Promise<Recorder> {
  const rec: Recorder = { creates: [], updates: [], deletes: [], slowServed: 0 };
  const tags: StoredTag[] = [
    { uuid: SLOW_UUID, name: "chorus", collection: "music" },
    { uuid: FAST_UUID, name: "verse", collection: "music" },
  ];
  const ratings: Record<string, number> = { [SLOW_UUID]: 9, [FAST_UUID]: 2 };
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/tags(\?|$)/, route => {
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { name: string; collection: string };
      rec.creates.push(body);
      const created = { uuid: `created-${++seq}`, ...body };
      tags.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: tags, _metainfo: { totalCount: tags.length } });
  });

  await page.route(/\/api\/v1\/tags\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/tags/")[1].split("?")[0]);
    if (route.request().method() === "DELETE") {
      rec.deletes.push(uuid);
      const idx = tags.findIndex(t => t.uuid === uuid);
      if (idx >= 0) tags.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    const body = route.request().postDataJSON() as Partial<StoredTag>;
    rec.updates.push({ uuid, body: body as Record<string, unknown> });
    const stored = tags.find(t => t.uuid === uuid);
    if (!stored) return json(route, { message: "no such tag" }, 404);
    Object.assign(stored, body);
    return json(route, stored);
  });

  // After the by-uuid route: matched last-registered first, and `/tags/x/rating` must not be
  // answered as a tag.
  await page.route(/\/api\/v1\/tags\/[^/]+\/rating$/, async route => {
    const uuid = route.request().url().split("/tags/")[1].split("/rating")[0];
    if (uuid === SLOW_UUID && opts.slowRatingMs) await new Promise(r => setTimeout(r, opts.slowRatingMs));
    if (uuid === SLOW_UUID) rec.slowServed++;
    return json(route, ratings[uuid] === undefined ? {} : { rating: ratings[uuid] }).catch(() => {});
  });
  return rec;
}

async function openTags(page: Page) {
  await page.goto("/ui/tags");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByText("chorus", { exact: true })).toBeVisible({ timeout: 10_000 });
}

/** The value of the detail panel's editable rating: the checked radio, "" when unrated. */
function panelRating(page: Page) {
  return page.getByTestId("tags-rating").locator("input[type=radio]:checked");
}

test.describe("Tag tree writes and detail panel – mocked e2e", () => {
  test("a slow rating answer for the previous tag does not land in the next tag's panel", async ({ page }) => {
    const rec = await installMocks(page, { slowRatingMs: 1_500 });
    await openTags(page);

    await page.getByText("chorus", { exact: true }).click();
    await page.getByText("verse", { exact: true }).click();
    await expect(panelRating(page)).toHaveValue("2", { timeout: 5_000 });

    // Outlast the slow answer for "chorus" (9) - the tree's own request and the panel's - then
    // check the panel still shows "verse".
    await expect.poll(() => rec.slowServed, { timeout: 10_000 }).toBeGreaterThanOrEqual(2);
    await expect(page.getByText("verse", { exact: true })).toBeVisible();
    await expect(panelRating(page)).toHaveValue("2");
  });

  test("creating a tag posts it and files it under its collection", async ({ page }) => {
    const rec = await installMocks(page);
    await openTags(page);

    await page.getByTestId("tag-new-name").fill("  bridge ");
    await page.getByTestId("tag-new-collection").fill("song parts");
    await page.getByTestId("tag-create-button").click();

    await expect.poll(() => rec.creates, { timeout: 5_000 }).toEqual([{ name: "bridge", collection: "song parts" }]);
    await expect(page.getByText("song parts", { exact: true })).toBeVisible();
    await expect(page.getByText("bridge", { exact: true })).toBeVisible();
    await expect(page.getByTestId("tag-new-name")).toHaveValue("");
  });

  test("an empty collection on create falls back to general", async ({ page }) => {
    const rec = await installMocks(page);
    await openTags(page);

    await page.getByTestId("tag-new-name").fill("intro");
    await page.getByTestId("tag-new-name").press("Enter");
    await expect.poll(() => rec.creates, { timeout: 5_000 }).toEqual([{ name: "intro", collection: "general" }]);
  });

  test("saving the detail panel renames and moves the tag", async ({ page }) => {
    const rec = await installMocks(page);
    await openTags(page);

    await page.getByText("verse", { exact: true }).click();
    await page.getByLabel("Name", { exact: true }).fill("verse 2");
    await page.getByLabel("Collection", { exact: true }).fill("lyrics");
    await page.getByRole("button", { name: "Save", exact: true }).click();

    await expect.poll(() => rec.updates, { timeout: 5_000 })
      .toEqual([{ uuid: FAST_UUID, body: { name: "verse 2", collection: "lyrics" } }]);
    await expect(page.getByText("lyrics", { exact: true })).toBeVisible();
    await expect(page.getByText("verse 2", { exact: true })).toBeVisible();
  });

  test("deleting a tag from its row menu removes it", async ({ page }) => {
    const rec = await installMocks(page);
    await openTags(page);

    const row = page.getByText("verse", { exact: true });
    await row.hover();
    // The row's kebab is the only icon button inside it.
    await row.locator("xpath=ancestor::div[contains(@class,'MuiBox-root')][1]/..").getByRole("button").last().click();
    await page.getByRole("menuitem").click();

    await expect.poll(() => rec.deletes, { timeout: 5_000 }).toEqual([FAST_UUID]);
    await expect(page.getByText("verse", { exact: true })).toHaveCount(0);
    await expect(page.getByText("chorus", { exact: true })).toBeVisible();
  });

  test("the search box narrows the tree to matching tags", async ({ page }) => {
    await installMocks(page);
    await openTags(page);

    await page.getByTestId("tags-search").locator("input").fill("vers");
    await expect(page.getByText("verse", { exact: true })).toBeVisible();
    await expect(page.getByText("chorus", { exact: true })).toHaveCount(0);
  });
});
