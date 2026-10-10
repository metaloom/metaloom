import { test, expect, Page } from "@playwright/test";

/**
 * Mocked e2e for the asset browser's layout controls and the single-asset delete.
 *
 *   - grid / list switch, and the card-size switch (what each size shows, and how wide a card is);
 *   - deleting one asset from a card and from a list row: cancel sends nothing, confirm sends one
 *     DELETE for that uuid and drops only that asset, a refusal keeps it and says so, and a double
 *     click on confirm sends one DELETE rather than a second that 404s into a failure toast;
 *   - the hover-only delete buttons become visible when reached by keyboard.
 */

const KEEP_UUID = "11111111-1111-1111-1111-111111111111";
const DROP_UUID = "22222222-2222-2222-2222-222222222222";

function assetResponse(uuid: string, filename: string) {
  return {
    uuid,
    file: { filename, mimeType: "image/jpeg", size: 1024, origin: "upload" },
    tags: [{ name: "alpha" }, { name: "beta" }, { name: "gamma" }],
    status: { created: new Date(0).toISOString() },
  };
}

async function mockRest(page: Page, options: { deleteStatus?: number; holdDelete?: Promise<void> } = {}) {
  const captured = { deletes: [] as string[] };
  const assets = [assetResponse(KEEP_UUID, "keep-me.jpg"), assetResponse(DROP_UUID, "drop-me.jpg")];

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }));
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) }));
  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: assets }) }));
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, async route => {
    if (route.request().method() !== "DELETE") return route.fallback();
    captured.deletes.push(route.request().url());
    if (options.holdDelete) await options.holdDelete;
    if (options.deleteStatus) {
      return route.fulfill({ status: options.deleteStatus, contentType: "application/json", body: JSON.stringify({ message: "no" }) });
    }
    // A second DELETE for the same uuid is what the real server answers with a 404.
    const already = captured.deletes.filter(u => u === route.request().url()).length > 1;
    return already
      ? route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "gone" }) })
      : route.fulfill({ status: 204, body: "" });
  });
  return captured;
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Assets", exact: true }).first().click();
  await expect(page.getByTestId("asset-card")).toHaveCount(2, { timeout: 10_000 });
}

const card = (page: Page, name: string) => page.getByTestId("asset-card").filter({ hasText: name });
const row = (page: Page, name: string) => page.getByTestId("asset-row").filter({ hasText: name });

test.describe("Asset browser – views and single delete (mocked)", () => {
  test("the list view shows rows instead of cards, and back", async ({ page }) => {
    await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "List view", exact: true }).click();
    await expect(page.getByTestId("asset-row")).toHaveCount(2);
    await expect(page.getByTestId("asset-card")).toHaveCount(0);
    await expect(row(page, "keep-me.jpg")).toBeVisible();
    // The card-size control only means something for cards.
    await expect(page.getByTestId("assets-card-size-large")).toBeHidden();

    await page.getByRole("button", { name: "Grid view", exact: true }).click();
    await expect(page.getByTestId("asset-card")).toHaveCount(2);
    await expect(page.getByTestId("asset-row")).toHaveCount(0);
  });

  test("card sizes change what a card shows and how wide it is", async ({ page }) => {
    await mockRest(page);
    await login(page);
    const keep = card(page, "keep-me.jpg");

    // Medium (default): the name and the first two tags.
    await expect(page.getByTestId("assets-card-size-medium")).toHaveAttribute("aria-pressed", "true");
    await expect(keep.getByText("keep-me.jpg", { exact: true })).toBeVisible();
    await expect(keep.getByText("gamma", { exact: true })).toHaveCount(0);
    const mediumWidth = (await keep.boundingBox())!.width;

    // Large: every tag.
    await page.getByRole("button", { name: "Large — tags, size, duration", exact: true }).click();
    await expect(keep.getByText("gamma", { exact: true })).toBeVisible();
    const largeWidth = (await keep.boundingBox())!.width;

    // Small: thumbnail only.
    await page.getByRole("button", { name: "Small — thumbnail only", exact: true }).click();
    // No name to filter by any more, so by position: keep-me is first.
    await expect(page.getByTestId("asset-card")).toHaveCount(2);
    await expect(page.getByText("keep-me.jpg", { exact: true })).toHaveCount(0);
    const smallWidth = (await page.getByTestId("asset-card").first().boundingBox())!.width;

    expect(smallWidth).toBeLessThan(mediumWidth);
    expect(mediumWidth).toBeLessThan(largeWidth);
  });

  test("cancelling a delete sends nothing", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await card(page, "drop-me.jpg").hover();
    await card(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true }).click();
    await page.getByTestId("asset-delete-cancel").click();

    await expect(page.getByTestId("asset-delete-confirm")).toHaveCount(0);
    await expect(page.getByTestId("asset-card")).toHaveCount(2);
    expect(captured.deletes).toEqual([]);
  });

  test("confirming a delete from a card removes that asset only", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await card(page, "drop-me.jpg").hover();
    await card(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true }).click();
    await page.getByTestId("asset-delete-confirm").click();

    await expect(page.getByText("Asset deleted", { exact: true })).toBeVisible();
    await expect(card(page, "drop-me.jpg")).toHaveCount(0);
    await expect(card(page, "keep-me.jpg")).toBeVisible();
    expect(captured.deletes).toHaveLength(1);
    expect(captured.deletes[0]).toMatch(new RegExp(`/assets/${DROP_UUID}$`));
  });

  test("a list row deletes its own asset", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await page.getByRole("button", { name: "List view", exact: true }).click();

    await row(page, "drop-me.jpg").hover();
    await row(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true }).click();
    await page.getByTestId("asset-delete-confirm").click();

    await expect(row(page, "drop-me.jpg")).toHaveCount(0);
    await expect(row(page, "keep-me.jpg")).toBeVisible();
    expect(captured.deletes[0]).toMatch(new RegExp(`/assets/${DROP_UUID}$`));
  });

  test("a refused delete says so and keeps the asset", async ({ page }) => {
    const captured = await mockRest(page, { deleteStatus: 403 });
    await login(page);

    await card(page, "drop-me.jpg").hover();
    await card(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true }).click();
    await page.getByTestId("asset-delete-confirm").click();

    await expect(page.getByText("Failed to delete asset", { exact: true })).toBeVisible();
    await expect(card(page, "drop-me.jpg")).toBeVisible();
    expect(captured.deletes).toHaveLength(1);
  });

  test("a double click on confirm sends one delete, not a second that fails", async ({ page }) => {
    let release!: () => void;
    const hold = new Promise<void>(resolve => { release = resolve; });
    const captured = await mockRest(page, { holdDelete: hold });
    await login(page);

    await card(page, "drop-me.jpg").hover();
    await card(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true }).click();
    const confirm = page.getByTestId("asset-delete-confirm");
    await confirm.click();
    await expect.poll(() => captured.deletes.length).toBe(1);
    // The first DELETE is still in flight: the button must already refuse a second press.
    await expect(confirm).toBeDisabled();
    await confirm.click({ force: true });
    release();

    await expect(page.getByText("Asset deleted", { exact: true })).toBeVisible();
    await expect(page.getByText("Failed to delete asset", { exact: true })).toHaveCount(0);
    expect(captured.deletes).toHaveLength(1);
  });

  test("the hover-only delete buttons show when reached by keyboard", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await page.mouse.move(0, 0);

    const cardDelete = card(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true });
    const cardActions = card(page, "drop-me.jpg").locator(".asset-actions");
    await expect(cardActions).toHaveCSS("opacity", "0");
    await cardDelete.focus();
    await expect(cardActions).toHaveCSS("opacity", "1");

    await page.getByRole("button", { name: "List view", exact: true }).click();
    await page.mouse.move(0, 0);
    const rowDelete = row(page, "drop-me.jpg").getByRole("button", { name: "Delete", exact: true });
    await expect(rowDelete).toHaveCSS("opacity", "0");
    await rowDelete.focus();
    await expect(rowDelete).toHaveCSS("opacity", "1");
  });
});
