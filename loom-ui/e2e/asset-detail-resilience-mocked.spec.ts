import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for how the Asset Detail view behaves when parts of the server say no.
 *
 * The happy paths are covered elsewhere (comments, annotations, transcripts, binaries …). This
 * file pins down the failure and race behaviour those specs never exercise:
 *
 *   - an asset that cannot be loaded is reported, not shown as an endless loading bar;
 *   - one side list failing (comments 500, persons 403) does not blank the others;
 *   - a refused tag is reported instead of the word silently disappearing;
 *   - a double-click on a comment's thumbs-up creates one reaction, not two;
 *   - the hover-only comment actions become visible when reached by keyboard;
 *   - the image zoom controls have accessible names.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const COMMENT_UUID = "33333333-3333-3333-3333-333333333333";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "resilience.jpg", mimeType: "image/jpeg", size: 2048 },
    tags: [],
    status: { creator: { uuid: ME_UUID }, created: new Date(0).toISOString() },
  };
}

function transcript() {
  return {
    uuid: "transcript-1",
    assetUuid: ASSET_UUID,
    source: "whisper",
    lang: "en",
    transcriptJson: {
      sections: [{
        id: "s1", title: "Opening chapter", startTime: 0, endTime: 10,
        words: [{ word: "hello", startTime: 0, endTime: 1, confidence: 1 }],
      }],
    },
  };
}

function task() {
  return { uuid: "task-1", title: "Review the cut", priority: "HIGH", taskStatus: "PENDING" };
}

function comment() {
  return {
    uuid: COMMENT_UUID,
    text: "mine-to-edit",
    assetUuid: ASSET_UUID,
    status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
  };
}

interface Options {
  assetStatus?: number;
  commentsStatus?: number;
  personsStatus?: number;
  tagStatus?: number;
  /** Hold every comment-reaction POST until this resolves. */
  holdReactionPost?: Promise<void>;
}

async function installMocks(page: Page, options: Options = {}) {
  const captured = { reactionPosts: 0, tagPosts: 0 };

  // Catch-all first (lowest priority): empty collections for every list AssetDetail fans out to.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(new RegExp(`/api/v1/assets/${ASSET_UUID}$`), route =>
    options.assetStatus && options.assetStatus >= 400
      ? json(route, { message: "not found" }, options.assetStatus)
      : json(route, asset()));

  await page.route(/\/api\/v1\/assets\/[^/]+\/comments$/, route =>
    options.commentsStatus ? json(route, { message: "boom" }, options.commentsStatus) : json(route, { data: [comment()] }));
  await page.route(/\/api\/v1\/persons(\?.*)?$/, route =>
    options.personsStatus ? json(route, { message: "forbidden" }, options.personsStatus) : json(route, { data: [] }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts$/, route => json(route, { data: [transcript()] }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/tasks$/, route => json(route, { data: [task()] }));

  await page.route(/\/api\/v1\/assets\/[^/]+\/tags$/, route => {
    if (route.request().method() !== "POST") return route.fallback();
    captured.tagPosts += 1;
    return options.tagStatus
      ? json(route, { message: "forbidden" }, options.tagStatus)
      : json(route, { uuid: "tag-1", name: "x", collection: "default" }, 201);
  });

  let seq = 0;
  await page.route(/\/api\/v1\/comments\/[^/]+\/reactions$/, async route => {
    if (route.request().method() !== "POST") return json(route, { data: [] });
    captured.reactionPosts += 1;
    if (options.holdReactionPost) await options.holdReactionPost;
    return json(route, {
      uuid: `reaction-${++seq}`, type: "THUMBSUP",
      status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
    }, 201);
  });

  return captured;
}

/** Deep-link first, then sign in: auth is in-memory, so a goto after signing in would drop it. */
async function openAssetDetail(page: Page) {
  await page.goto(`/ui/assets/${ASSET_UUID}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page).toHaveURL(new RegExp(`/assets/${ASSET_UUID}`));
}

test.describe("Asset detail – failure and race behaviour (mocked)", () => {
  test("an asset that cannot be loaded is reported instead of loading forever", async ({ page }) => {
    await installMocks(page, { assetStatus: 404 });
    await openAssetDetail(page);

    await expect(page.getByTestId("asset-load-failed")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("This asset could not be loaded", { exact: false })).toBeVisible();
    await expect(page.getByRole("progressbar")).toHaveCount(0);

    await page.getByRole("button", { name: "Back to assets", exact: true }).click();
    await expect(page).toHaveURL(/\/assets$/);
  });

  test("a failing comments list and a 403 on persons do not blank transcripts and tasks", async ({ page }) => {
    await installMocks(page, { commentsStatus: 500, personsStatus: 403 });
    await openAssetDetail(page);

    await expect(page.getByTestId("asset-name")).toHaveValue("resilience.jpg", { timeout: 10_000 });
    // Before the fix all six lists shared one Promise.all with no catch: the comments 500 rejected
    // it, so neither the transcript nor the task below ever reached the screen.
    await expect(page.getByTestId("transcript-section")).toHaveCount(1);
    await expect(page.getByTestId("transcript-section-title")).toHaveValue("Opening chapter");
    await expect(page.getByRole("tab", { name: "Tasks (1)", exact: true })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Comments (0)", exact: true })).toBeVisible();

    await page.getByRole("tab", { name: "Tasks (1)", exact: true }).click();
    await expect(page.getByTestId("asset-task-item")).toContainText("Review the cut");
  });

  test("a refused tag is reported rather than vanishing", async ({ page }) => {
    const captured = await installMocks(page, { tagStatus: 403 });
    await openAssetDetail(page);

    const input = page.getByTestId("tag-input");
    await input.fill("forbidden-word");
    await input.press("Enter");

    await expect.poll(() => captured.tagPosts, { timeout: 5_000 }).toBe(1);
    await expect(page.getByText("The tag could not be added.", { exact: true })).toBeVisible({ timeout: 5_000 });
  });

  test("the description is read-only rather than a field that swallows keystrokes", async ({ page }) => {
    await installMocks(page);
    await openAssetDetail(page);

    await expect(page.getByTestId("asset-description")).toHaveAttribute("readonly", "");
  });

  test("double-clicking a comment's thumbs-up creates exactly one reaction", async ({ page }) => {
    let release!: () => void;
    const hold = new Promise<void>(resolve => { release = resolve; });
    const captured = await installMocks(page, { holdReactionPost: hold });
    await openAssetDetail(page);

    await page.getByRole("tab", { name: "Comments (1)", exact: true }).click();
    const up = page.getByTestId("comment-reaction-up").first();
    await expect(up).toBeVisible({ timeout: 10_000 });

    // Two clicks while the first POST is still in flight.
    await up.click();
    await expect.poll(() => captured.reactionPosts, { timeout: 5_000 }).toBe(1);
    await up.click();
    release();

    await expect(page.getByTestId("comment-reaction-up-count").first()).toHaveText("1", { timeout: 5_000 });
    // Give a duplicate request every chance to show up before asserting it never did.
    await page.waitForLoadState("networkidle");
    expect(captured.reactionPosts).toBe(1);
  });

  test("the comment edit/delete actions become visible when focused by keyboard", async ({ page }) => {
    await installMocks(page);
    await openAssetDetail(page);

    await page.getByRole("tab", { name: "Comments (1)", exact: true }).click();
    const edit = page.getByTestId("comment-edit").first();
    await expect(edit).toBeAttached({ timeout: 10_000 });
    // The actions wrapper is opacity 0 until hovered; move the pointer away so hover is not what
    // reveals it.
    await page.mouse.move(0, 0);
    const actions = page.locator(".comment-actions").first();
    await expect(actions).toHaveCSS("opacity", "0");

    await edit.focus();
    await expect(actions).toHaveCSS("opacity", "1");
  });

  test("the image zoom controls have accessible names", async ({ page }) => {
    await installMocks(page);
    await openAssetDetail(page);

    await expect(page.getByRole("button", { name: "Zoom in", exact: true })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("button", { name: "Zoom out", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Reset zoom", exact: true })).toBeVisible();
  });
});
