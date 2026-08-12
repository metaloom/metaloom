import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for comment authoring on the Asset Detail view — no running Loom
 * backend required. All `/api/v1/**` calls are intercepted via `page.route`,
 * with a small in-memory comment store so post → edit → delete round-trips
 * against the asset-scoped routes:
 *   GET/POST /api/v1/assets/:uuid/comments   and   POST/DELETE /api/v1/comments/:uuid
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

interface StoredComment {
  uuid: string;
  text: string;
  assetUuid: string;
  status: { creator: { uuid: string }; created: string };
}

/** What the browser actually sent, so a *missing* write can be asserted rather than inferred. */
interface Recorder {
  /** Bodies of every POST /comments/:uuid (the update route). */
  updates: { uuid: string; body: Record<string, unknown> }[];
}

function recorder(): Recorder {
  return { updates: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "mock-asset.jpg", mimeType: "image/jpeg", size: 1024 },
    status: { creator: { uuid: ME_UUID } },
  };
}

async function installMocks(page: Page, seed: StoredComment[], rec: Recorder) {
  const comments: StoredComment[] = [...seed];
  let seq = 0;

  // Catch-all first (lowest priority) — empty collections for the many
  // list endpoints AssetDetail fans out to (reactions, transcripts, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route =>
    json(route, { token: "fake-jwt" })
  );
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  // Asset list + detail
  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));

  // Asset-scoped comments: list + create
  await page.route(/\/api\/v1\/assets\/[^/]+\/comments$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredComment = {
        uuid: `comment-${++seq}`,
        text: body.text ?? "",
        assetUuid: ASSET_UUID,
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      comments.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: comments });
  });

  // Comment by uuid: update (POST) + delete
  await page.route(/\/api\/v1\/comments\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/comments/")[1].split("?")[0]);
    const found = comments.find(c => c.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = comments.findIndex(c => c.uuid === uuid);
      if (idx >= 0) comments.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    // POST update
    const body = JSON.parse(route.request().postData() || "{}");
    rec.updates.push({ uuid, body });
    if (found && body.text != null) found.text = body.text;
    return json(route, found ?? {}, 200);
  });
}

async function loginAndOpenAssetDetail(page: Page, seed: StoredComment[] = [], rec: Recorder = recorder()) {
  await installMocks(page, seed, rec);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Assets" }).first().click();
  const assetLink = page.getByText("mock-asset.jpg").first();
  await expect(assetLink).toBeVisible({ timeout: 10_000 });
  await assetLink.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  await page.getByRole("tab", { name: /comments/i }).click();
  return rec;
}

function seededComment(text: string): StoredComment {
  return {
    uuid: "seeded-comment",
    text,
    assetUuid: ASSET_UUID,
    status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
  };
}

test.describe("Comments – mocked e2e", () => {
  test("post → edit → delete a comment", async ({ page }) => {
    await loginAndOpenAssetDetail(page);

    const text = "mocked-comment";
    const editedText = "mocked-comment-edited";

    // Post
    const composer = page.getByPlaceholder("Write a comment…");
    await expect(composer).toBeVisible({ timeout: 5_000 });
    await composer.fill(text);
    await page.getByRole("button", { name: "Post comment" }).click();
    await expect(page.getByText(text, { exact: true })).toBeVisible({ timeout: 10_000 });

    // Edit
    await page.locator("div").filter({ hasText: text }).last().hover();
    await page.getByTestId("comment-edit").click();
    await page.getByTestId("comment-edit-field").fill(editedText);
    await page.getByTestId("comment-save").click();
    await expect(page.getByText(editedText, { exact: true })).toBeVisible({ timeout: 10_000 });

    // Delete
    await page.locator("div").filter({ hasText: editedText }).last().hover();
    await page.getByTestId("comment-delete").click();
    await expect(page.getByText(editedText, { exact: true })).toBeHidden({ timeout: 10_000 });
  });

  /**
   * The cancel half of the edit round-trip above. `comment-cancel` sits on the edit form — the
   * asset composer itself has no cancel, it is simply always open on the tab. What this guards is
   * the draft: an editor that kept the abandoned text would re-open on it, and the next save would
   * write words the author already backed out of.
   */
  test("cancelling a comment edit writes nothing and re-opens on the stored text", async ({ page }) => {
    const text = "the stored comment";
    const rec = await loginAndOpenAssetDetail(page, [seededComment(text)]);

    await expect(page.getByText(text, { exact: true })).toBeVisible({ timeout: 10_000 });

    await page.locator("div").filter({ hasText: text }).last().hover();
    await page.getByTestId("comment-edit").click();
    await page.getByTestId("comment-edit-field").fill("abandoned text");
    await page.getByTestId("comment-cancel").click();

    // The form closes, the list still shows what the server holds, and no write went out.
    await expect(page.getByTestId("comment-edit-field")).toHaveCount(0);
    await expect(page.getByText(text, { exact: true })).toBeVisible();
    await expect(page.getByText("abandoned text", { exact: true })).toHaveCount(0);
    expect(rec.updates).toEqual([]);

    // Re-opening starts from the stored text, not from the draft that was thrown away.
    await page.locator("div").filter({ hasText: text }).last().hover();
    await page.getByTestId("comment-edit").click();
    await expect(page.getByTestId("comment-edit-field")).toHaveValue(text);
    expect(rec.updates).toEqual([]);
  });
});
