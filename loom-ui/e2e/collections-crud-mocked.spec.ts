import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for creating, renaming and deleting a collection.
 *
 * `CollectionsView` was covered only for listing concerns — sort, creator filter, paging, the empty
 * state — and through `collections-backend.spec.ts`, which needs a live server. None of its write
 * paths had a mocked spec, and its edit/delete buttons had no testid to aim one at.
 *
 * Routes exercised:
 *   GET/POST    /api/v1/collections
 *   POST/DELETE /api/v1/collections/:uuid
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const HOLIDAY = "cccccccc-0000-0000-0000-000000000001";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface Recorder {
  creates: unknown[];
  updates: { uuid: string; body: unknown }[];
  deletes: string[];
}

async function installMocks(page: Page, opts: { deleteDelayMs?: number; failDelete?: boolean } = {}): Promise<Recorder> {
  const rec: Recorder = { creates: [], updates: [], deletes: [] };
  const collections = [{ uuid: HOLIDAY, name: "Holiday 2025", status: { created: "2026-01-01T00:00:00Z", edited: "2026-01-02T00:00:00Z" } }];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/collections(\?|$)/, route => {
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { name: string };
      rec.creates.push(body);
      const created = { uuid: `created-${++seq}`, name: body.name };
      collections.push(created as typeof collections[number]);
      return json(route, created, 201);
    }
    return json(route, { data: collections, _metainfo: { totalCount: collections.length } });
  });

  await page.route(/\/api\/v1\/collections\/[^/]+$/, async route => {
    const uuid = decodeURIComponent(route.request().url().split("/collections/")[1].split("?")[0]);
    const idx = collections.findIndex(c => c.uuid === uuid);
    if (route.request().method() === "DELETE") {
      rec.deletes.push(uuid);
      if (opts.deleteDelayMs) await new Promise(r => setTimeout(r, opts.deleteDelayMs));
      if (opts.failDelete) return json(route, { message: "nope" }, 500).catch(() => {});
      if (idx < 0) return json(route, { message: "no such collection" }, 404).catch(() => {});
      collections.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" }).catch(() => {});
    }
    const body = route.request().postDataJSON() as { name: string };
    rec.updates.push({ uuid, body });
    if (idx < 0) return json(route, { message: "no such collection" }, 404);
    collections[idx] = { ...collections[idx], ...body };
    return json(route, collections[idx]);
  });
  return rec;
}

async function openCollections(page: Page) {
  await page.goto("/ui/collections");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByTestId("collection-card")).toHaveCount(1, { timeout: 10_000 });
}

function card(page: Page, name: string) {
  return page.locator(`[data-testid=collection-card][data-collection-name="${name}"]`);
}

test.describe("Collections CRUD – mocked e2e", () => {
  test("creating a collection posts the trimmed name and adds a card", async ({ page }) => {
    const rec = await installMocks(page);
    await openCollections(page);

    await page.getByTestId("collections-new").click();
    await expect(page.getByTestId("collection-create-save")).toBeDisabled();
    await page.getByTestId("collection-create-name").fill("  Wedding  ");
    await page.getByTestId("collection-create-save").click();

    await expect.poll(() => rec.creates, { timeout: 5_000 }).toEqual([{ name: "Wedding" }]);
    await expect(card(page, "Wedding")).toBeVisible();
    await expect(page.getByTestId("collection-card")).toHaveCount(2);
  });

  test("renaming a collection prefills its name and shows the stored one", async ({ page }) => {
    const rec = await installMocks(page);
    await openCollections(page);

    await card(page, "Holiday 2025").hover();
    await card(page, "Holiday 2025").getByTestId("collection-edit").click();
    await expect(page.getByTestId("collection-edit-name")).toHaveValue("Holiday 2025");
    await page.getByTestId("collection-edit-name").fill("Holiday 2026");
    await page.getByTestId("collection-edit-save").click();

    await expect.poll(() => rec.updates, { timeout: 5_000 }).toEqual([{ uuid: HOLIDAY, body: { name: "Holiday 2026" } }]);
    await expect(card(page, "Holiday 2026")).toBeVisible();
    await expect(card(page, "Holiday 2025")).toHaveCount(0);
  });

  test("delete asks first, and a double-click on confirm sends one request", async ({ page }) => {
    const rec = await installMocks(page, { deleteDelayMs: 600 });
    await openCollections(page);

    await card(page, "Holiday 2025").hover();
    await card(page, "Holiday 2025").getByTestId("collection-delete").click();
    // Nothing has been sent yet: the button only opens the confirmation.
    expect(rec.deletes).toEqual([]);

    await page.getByTestId("collection-delete-confirm").dblclick();
    await expect(page.getByTestId("collections-empty-state")).toBeVisible({ timeout: 5_000 });
    expect(rec.deletes).toEqual([HOLIDAY]);
  });

  test("a rejected delete keeps the card", async ({ page }) => {
    const rec = await installMocks(page, { failDelete: true });
    await openCollections(page);

    await card(page, "Holiday 2025").hover();
    await card(page, "Holiday 2025").getByTestId("collection-delete").click();
    await page.getByTestId("collection-delete-confirm").click();

    await expect.poll(() => rec.deletes, { timeout: 5_000 }).toEqual([HOLIDAY]);
    await expect(card(page, "Holiday 2025")).toBeVisible();
  });
});
