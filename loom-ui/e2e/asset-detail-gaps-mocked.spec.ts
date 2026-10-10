import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Asset Detail behaviours no other spec reached:
 *
 *   - refused comment / annotation reactions and a refused comment delete are reported, and the
 *     screen keeps showing what the server still holds;
 *   - the image zoom: buttons, wheel, drag-to-pan, the minimap that follows the pan, and reset;
 *   - the transcript boundary arrows: they move the boundary they sit on, and no word is lost;
 *   - the server-side transcript search: clear, the 503 "not configured" sentence, other errors.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const COMMENT_UUID = "33333333-3333-3333-3333-333333333333";
const ANNOTATION_UUID = "44444444-4444-4444-4444-444444444444";
const TRANSCRIPT_UUID = "55555555-5555-5555-5555-555555555555";

/** A real 1x1 JPEG, so the picture decodes and the zoom layer is laid out like a real one. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function word(w: string, startTime: number) {
  return { word: w, startTime, endTime: startTime + 0.3, confidence: 1 };
}

function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "gaps.jpg", mimeType: "image/jpeg", size: 2048 },
    tags: [],
    status: { creator: { uuid: ME_UUID }, created: new Date(0).toISOString() },
    annotations: [{
      uuid: ANNOTATION_UUID,
      type: "FEEDBACK",
      title: "Reactable note",
      description: "",
      assetUuid: ASSET_UUID,
      status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
    }],
  };
}

function transcript() {
  return {
    uuid: TRANSCRIPT_UUID,
    assetUuid: ASSET_UUID,
    source: "manual",
    lang: "en",
    transcriptJson: {
      sections: [
        { id: "s1", title: "Opening", startTime: 0, endTime: 10, words: [word("hello", 1), word("there", 9.6)] },
        { id: "s2", title: "Middle", startTime: 10, endTime: 20, words: [word("general", 10.2), word("kenobi", 15)] },
      ],
    },
  };
}

interface Options {
  commentReactionStatus?: number;
  annotationReactionStatus?: number;
  commentDeleteStatus?: number;
  /** Status the transcript search answers with; 200 serves one hit. */
  searchStatus?: number;
}

async function installMocks(page: Page, options: Options = {}) {
  const captured = {
    commentReactionPosts: 0,
    annotationReactionPosts: 0,
    commentDeletes: 0,
    transcriptUpdates: [] as Record<string, unknown>[],
    searchRequests: [] as string[],
  };

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(new RegExp(`/api/v1/assets/${ASSET_UUID}$`), route => json(route, asset()));
  await page.route(/\/api\/v1\/assets\/[^/]+\/binary\/data/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));

  await page.route(/\/api\/v1\/assets\/[^/]+\/comments$/, route => json(route, {
    data: [{
      uuid: COMMENT_UUID,
      text: "keep-this-comment",
      assetUuid: ASSET_UUID,
      status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
    }],
  }));
  await page.route(/\/api\/v1\/comments\/[^/]+$/, route => {
    if (route.request().method() !== "DELETE") return route.fallback();
    captured.commentDeletes += 1;
    return options.commentDeleteStatus
      ? json(route, { message: "forbidden" }, options.commentDeleteStatus)
      : route.fulfill({ status: 204, body: "" });
  });
  await page.route(/\/api\/v1\/comments\/[^/]+\/reactions$/, route => {
    if (route.request().method() !== "POST") return json(route, { data: [] });
    captured.commentReactionPosts += 1;
    return json(route, { message: "refused" }, options.commentReactionStatus ?? 500);
  });
  await page.route(/\/api\/v1\/annotations\/[^/]+\/reactions$/, route => {
    if (route.request().method() !== "POST") return json(route, { data: [] });
    captured.annotationReactionPosts += 1;
    return json(route, { message: "refused" }, options.annotationReactionStatus ?? 500);
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts$/, route => json(route, { data: [transcript()] }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts\/[^/]+$/, route => {
    if (route.request().method() !== "POST") return route.fallback();
    captured.transcriptUpdates.push(route.request().postDataJSON());
    return json(route, transcript());
  });

  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "postgres", available: true, capabilities: ["LEXICAL", "HIGHLIGHT"], documentCount: 1, dirtyCount: 0 }));
  await page.route(/\/api\/v1\/search\/results/, route => {
    captured.searchRequests.push(route.request().url());
    const status = options.searchStatus ?? 200;
    if (status !== 200) return json(route, { message: status === 503 ? "no provider" : "index is rebuilding" }, status);
    return json(route, {
      data: [{
        type: "transcript", uuid: "hit-1", assetUuid: ASSET_UUID, score: 1, title: "gaps.jpg",
        subtitle: "00:00:15 · en manual", timeFromMs: 15_000, highlights: ["general <b>kenobi</b>"],
      }],
      _metainfo: { totalHits: 1, totalExact: true, perPage: 25, offset: 0, tookMs: 1, provider: "postgres", capabilities: [], warnings: [] },
    });
  });

  return captured;
}

/** Deep-link first, then sign in. */
async function openAssetDetail(page: Page) {
  await page.goto(`/ui/assets/${ASSET_UUID}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("zoomable-image")).toBeVisible({ timeout: 10_000 });
}

/** The picture layer's CSS transform as [scale, translateX, translateY]. */
async function pictureTransform(page: Page): Promise<[number, number, number]> {
  return page.getByTestId("zoomable-image-picture").evaluate(el => {
    const m = new DOMMatrixReadOnly(getComputedStyle(el).transform);
    return [m.a, m.e, m.f] as [number, number, number];
  });
}

test.describe("Asset detail – remaining gaps (mocked)", () => {
  // ── Refusals ──────────────────────────────────────────────────────────

  test("a refused comment reaction says so and the count stays at zero", async ({ page }) => {
    const captured = await installMocks(page);
    await openAssetDetail(page);
    await page.getByRole("tab", { name: "Comments (1)", exact: true }).click();

    await page.getByTestId("comment-reaction-up").first().click();

    await expect(page.getByText("Failed to add reaction", { exact: true })).toBeVisible();
    await expect.poll(() => captured.commentReactionPosts).toBe(1);
    await expect(page.getByTestId("comment-reaction-up-count").first()).toHaveText("0");
  });

  test("a refused comment delete says so and the comment stays", async ({ page }) => {
    const captured = await installMocks(page, { commentDeleteStatus: 403 });
    await openAssetDetail(page);
    await page.getByRole("tab", { name: "Comments (1)", exact: true }).click();

    await page.getByTestId("comment-delete").first().focus();
    await page.getByTestId("comment-delete").first().click();

    await expect(page.getByText("Failed to delete comment", { exact: true })).toBeVisible();
    await expect.poll(() => captured.commentDeletes).toBe(1);
    await expect(page.getByText("keep-this-comment", { exact: true })).toBeVisible();
  });

  test("a refused annotation reaction says so and the count stays at zero", async ({ page }) => {
    const captured = await installMocks(page);
    await openAssetDetail(page);
    await page.getByRole("tab", { name: /annotations/i }).click();
    await expect(page.getByText("Reactable note", { exact: true })).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("annotation-reaction-up").first().click();

    await expect(page.getByText("Failed to add reaction", { exact: true })).toBeVisible();
    await expect.poll(() => captured.annotationReactionPosts).toBe(1);
    await expect(page.getByTestId("annotation-reaction-up-count").first()).toHaveText("0");
  });

  // ── Zoom ──────────────────────────────────────────────────────────────

  test("zoom in and out step by 50%, show the level, and the minimap only while zoomed", async ({ page }) => {
    await installMocks(page);
    await openAssetDetail(page);

    expect((await pictureTransform(page))[0]).toBe(1);
    await expect(page.getByTestId("zoomable-image-minimap")).toHaveCount(0);

    await page.getByTestId("zoomable-image-zoom-in").click();
    await page.getByTestId("zoomable-image-zoom-in").click();
    await expect.poll(async () => (await pictureTransform(page))[0]).toBe(2);
    await expect(page.getByTestId("zoomable-image")).toContainText("200%");
    await expect(page.getByTestId("zoomable-image-minimap")).toBeVisible();

    await page.getByTestId("zoomable-image-zoom-out").click();
    await expect.poll(async () => (await pictureTransform(page))[0]).toBe(1.5);
    await page.getByTestId("zoomable-image-zoom-out").click();
    await expect.poll(async () => (await pictureTransform(page))[0]).toBe(1);
    // Never below 1: a further zoom-out is a no-op, not a shrunken picture.
    await page.getByTestId("zoomable-image-zoom-out").click();
    expect((await pictureTransform(page))[0]).toBe(1);
    await expect(page.getByTestId("zoomable-image-minimap")).toHaveCount(0);
  });

  test("the wheel zooms in and back out", async ({ page }) => {
    await installMocks(page);
    await openAssetDetail(page);
    const frame = page.getByTestId("zoomable-image");
    await frame.hover();

    await page.mouse.wheel(0, -300);
    await expect.poll(async () => (await pictureTransform(page))[0]).toBeGreaterThan(1);

    await page.mouse.wheel(0, 3000);
    await expect.poll(async () => (await pictureTransform(page))[0]).toBe(1);
  });

  test("dragging pans only while zoomed, the minimap follows, and reset recentres", async ({ page }) => {
    await installMocks(page);
    await openAssetDetail(page);
    const frame = page.getByTestId("zoomable-image");
    const box = (await frame.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;

    // Unzoomed, a drag moves nothing.
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx + 40, cy + 30, { steps: 4 });
    await page.mouse.up();
    expect(await pictureTransform(page)).toEqual([1, 0, 0]);

    await page.getByTestId("zoomable-image-zoom-in").click();
    await page.getByTestId("zoomable-image-zoom-in").click();
    const viewport = page.getByTestId("zoomable-image-minimap-viewport");
    const leftBefore = (await viewport.boundingBox())!.x;

    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx + 40, cy + 30, { steps: 4 });
    await page.mouse.up();

    const [scale, tx, ty] = await pictureTransform(page);
    expect(scale).toBe(2);
    expect(tx).toBeCloseTo(40, 0);
    expect(ty).toBeCloseTo(30, 0);
    // Moving the picture right shows more of its left side: the indicator moves left.
    const leftAfter = (await viewport.boundingBox())!.x;
    expect(leftAfter).toBeLessThan(leftBefore);

    await page.getByTestId("zoomable-image-zoom-reset").click();
    await expect.poll(() => pictureTransform(page)).toEqual([1, 0, 0]);
    await expect(page.getByTestId("zoomable-image-minimap")).toHaveCount(0);
  });

  // ── Transcript boundary arrows ────────────────────────────────────────

  test("the down arrow moves the boundary it sits on later, and keeps every word", async ({ page }) => {
    const captured = await installMocks(page);
    await openAssetDetail(page);

    const down = page.getByRole("button", { name: "Move section start later", exact: true });
    await expect(down).toBeVisible({ timeout: 10_000 });
    await down.click();

    await expect.poll(() => captured.transcriptUpdates.length).toBe(1);
    const sections = (captured.transcriptUpdates[0].transcriptJson as { sections: { startTime: number; endTime: number; words: { word: string }[] }[] }).sections;
    expect(sections[0].endTime).toBe(10.5);
    expect(sections[1].startTime).toBe(10.5);
    // The far end of the second section did not move: the arrow nudged its own boundary only.
    expect(sections[1].endTime).toBe(20);
    expect(sections.flatMap(s => s.words.map(w => w.word))).toEqual(["hello", "there", "general", "kenobi"]);
    expect(sections[0].words.map(w => w.word)).toEqual(["hello", "there", "general"]);
  });

  test("the up arrow moves the boundary earlier and hands the crossed word to the next section", async ({ page }) => {
    const captured = await installMocks(page);
    await openAssetDetail(page);

    const up = page.getByRole("button", { name: "Move section start earlier", exact: true });
    await expect(up).toBeVisible({ timeout: 10_000 });
    await up.click();

    await expect.poll(() => captured.transcriptUpdates.length).toBe(1);
    const sections = (captured.transcriptUpdates[0].transcriptJson as { sections: { startTime: number; endTime: number; words: { word: string }[] }[] }).sections;
    expect(sections[0].endTime).toBe(9.5);
    expect(sections[1].startTime).toBe(9.5);
    expect(sections[0].words.map(w => w.word)).toEqual(["hello"]);
    expect(sections[1].words.map(w => w.word)).toEqual(["there", "general", "kenobi"]);
  });

  // ── Server-side transcript search ─────────────────────────────────────

  test("clear empties the box and drops the results", async ({ page }) => {
    const captured = await installMocks(page);
    await openAssetDetail(page);

    await page.getByTestId("transcript-search-input").fill("kenobi");
    await expect(page.getByTestId("transcript-search-hit")).toHaveCount(1, { timeout: 10_000 });
    expect(captured.searchRequests.length).toBeGreaterThan(0);

    await page.getByTestId("transcript-search-clear").click();
    await expect(page.getByTestId("transcript-search-input")).toHaveValue("");
    await expect(page.getByTestId("transcript-search-results")).toHaveCount(0);
    await expect(page.getByTestId("transcript-search-hit")).toHaveCount(0);
  });

  test("a 503 says search is not configured, not that nothing matched", async ({ page }) => {
    await installMocks(page, { searchStatus: 503 });
    await openAssetDetail(page);

    await page.getByTestId("transcript-search-input").fill("kenobi");
    await expect(page.getByTestId("transcript-search-error")).toHaveText(
      "Search is not configured on this deployment.", { timeout: 10_000 });
    await expect(page.getByText("Nothing said here matches that.", { exact: true })).toHaveCount(0);
  });

  test("any other failure is reported as an error, and clearing removes it", async ({ page }) => {
    await installMocks(page, { searchStatus: 500 });
    await openAssetDetail(page);

    await page.getByTestId("transcript-search-input").fill("kenobi");
    const error = page.getByTestId("transcript-search-error");
    await expect(error).toBeVisible({ timeout: 10_000 });
    await expect(error).not.toHaveText("Search is not configured on this deployment.");
    await expect(page.getByTestId("transcript-search-results")).toHaveCount(0);

    await page.getByTestId("transcript-search-clear").click();
    await expect(error).toHaveCount(0);
  });
});
