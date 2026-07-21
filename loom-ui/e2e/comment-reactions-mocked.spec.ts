import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for reacting to a comment on the Asset Detail view — no running
 * Loom backend required. All `/api/v1/**` calls are intercepted via `page.route`,
 * with a small in-memory reaction store so 👍 → 👍 round-trips against the
 * comment-scoped reaction routes:
 *   GET/POST /api/v1/comments/:uuid/reactions   and   DELETE /api/v1/comments/:uuid/reactions/:reactionUuid
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const COMMENT_UUID = "33333333-3333-3333-3333-333333333333";

interface StoredReaction {
  uuid: string;
  type: string;
  status: { creator: { uuid: string }; created: string };
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

function comment() {
  return {
    uuid: COMMENT_UUID,
    text: "reactable-comment",
    assetUuid: ASSET_UUID,
    status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
  };
}

async function installMocks(page: Page) {
  const reactions: StoredReaction[] = [];
  let seq = 0;

  // Catch-all first (lowest priority) — empty collections for the many list
  // endpoints AssetDetail fans out to (transcripts, annotations, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));

  // Asset-scoped comments: one seeded comment.
  await page.route(/\/api\/v1\/assets\/[^/]+\/comments$/, route =>
    json(route, { data: [comment()] })
  );

  // Comment-scoped reactions: list + create.
  await page.route(/\/api\/v1\/comments\/[^/]+\/reactions$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredReaction = {
        uuid: `reaction-${++seq}`,
        type: body.type ?? "THUMBSUP",
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      reactions.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: reactions });
  });

  // Comment reaction by uuid: delete.
  await page.route(/\/api\/v1\/comments\/[^/]+\/reactions\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/reactions/")[1].split("?")[0]);
    const idx = reactions.findIndex(r => r.uuid === uuid);
    if (idx >= 0) reactions.splice(idx, 1);
    return route.fulfill({ status: 204, body: "" });
  });
}

async function loginAndOpenComments(page: Page) {
  await installMocks(page);
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
}

test.describe("Comment reactions – mocked e2e", () => {
  test("react to a comment, then remove the reaction", async ({ page }) => {
    await loginAndOpenComments(page);

    await expect(page.getByText("reactable-comment", { exact: true })).toBeVisible({ timeout: 10_000 });

    const up = page.getByTestId("comment-reaction-up").first();
    const upCount = page.getByTestId("comment-reaction-up-count").first();

    // Initially zero thumbs-up.
    await expect(upCount).toHaveText("0");

    // React → count increments to 1.
    await up.click();
    await expect(upCount).toHaveText("1", { timeout: 10_000 });

    // Toggle off → count returns to 0.
    await up.click();
    await expect(upCount).toHaveText("0", { timeout: 10_000 });
  });
});
