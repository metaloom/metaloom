import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the asset-detail video player and the tag input beside it.
 *
 * Three defects, all of which left a screen that looked finished:
 *
 *  - **Seeking reached only the opening seconds.** The player was a `<video controls>` pointed at
 *    the on-demand remux. A fragmented MP4 over a pipe has no index, so `seekable` is empty and
 *    the native scrubber has nothing to scrub; it also reports the pipe's length as the file's,
 *    which drew a five-second bar for a 43-minute episode. The native controls are gone and the
 *    app's own timeline is the seek surface — a seek past the buffer becomes a *new request* at
 *    that offset.
 *  - **Nothing knew how long a video was.** `asset_video_comp` has carried duration since V1 and
 *    has no producer, so an ingested file reports none, and the element cannot supply one either.
 *    `GET /assets/:uuid/media-info` measures it with ffprobe; without that number there is nowhere
 *    on the timeline to click.
 *  - **Tagging had no suggestions.** A plain text field, so the same idea was coined as
 *    "interview", "Interview" and "interviews" and `tag` is `UNIQUE (name, collection)`.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

/** What the probe reports, in seconds. Deliberately long: the bug only shows on a long file. */
const PROBE_DURATION = 2580;

/** A 1x1 JPEG: the crop route has to answer with image bytes, not with what is in them. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/**
 * A video asset with **no** `videoComponents`.
 *
 * That is the real shape of everything ingested into this deployment, not an edge case: no node
 * writes the component row, so `asset.duration` is empty for all 22 episodes on the box.
 */
function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "episode.mkv", mimeType: "video/x-matroska", size: 4_500_000_000 },
    videoComponents: [],
    tags: [{ uuid: "bbbbbbbb-0000-0000-0000-000000000001", name: "approved", collection: "default" }],
    annotations: [],
    status: { creator: { uuid: ME_UUID }, created: "2026-09-19T22:50:42Z" },
  };
}

const DETECTION_A = "33333333-3333-3333-3333-333333333333";
const DETECTION_B = "44444444-4444-4444-4444-444444444444";

/** 23.976fps, so frame 24000 is second 1001 — a face two thirds of the way into the episode. */
const FRAME_A = 240;
const FRAME_B = 24_000;

interface Recorder {
  streamRequests: string[];
  tagPosts: { name: string }[];
}

/**
 * Two face detections, carrying a frame *number* in `frameNumber`.
 *
 * That is the column, and it is why the probe's frame rate matters: the panel used to hand the
 * frame number to a seek as if it were seconds, which asked for second 24000 of a 43-minute file.
 */
function detections() {
  return [
    { uuid: DETECTION_A, assetUuid: ASSET_UUID, type: "face", frameNumber: FRAME_A, confidence: 0.9,
      bboxX: 0.1, bboxY: 0.1, bboxWidth: 0.2, bboxHeight: 0.3 },
    { uuid: DETECTION_B, assetUuid: ASSET_UUID, type: "face", frameNumber: FRAME_B, confidence: 0.8,
      bboxX: 0.5, bboxY: 0.2, bboxWidth: 0.2, bboxHeight: 0.3 },
  ];
}

/** What the whisper node actually writes: `segments`, in milliseconds, with no `sections`. */
function transcript() {
  return {
    uuid: "55555555-5555-5555-5555-555555555555",
    assetUuid: ASSET_UUID,
    source: "whisper",
    lang: "en",
    model: "ggml-large-v3-turbo.bin",
    transcriptText: "Good afternoon. Jim Menard, Director of Photography.",
    transcriptJson: {
      segments: [
        { from: 0, to: 7000, text: " Good afternoon." },
        { from: 7000, to: 9400, text: " Jim Menard, Director of Photography." },
        { from: 125_000, to: 127_000, text: " So here we have the Pegasus." },
      ],
    },
    status: { creator: { uuid: ME_UUID }, created: "2026-09-19T23:48:05Z" },
  };
}

async function installMocks(page: Page, rec: Recorder, opts: { probe?: null } = {}) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // The vocabulary the tag input suggests from.
  await page.route(/\/api\/v1\/tags(\?|$)/, route =>
    json(route, {
      data: [
        { uuid: "t1", name: "interview", collection: "default" },
        { uuid: "t2", name: "establishing-shot", collection: "default" },
        { uuid: "t3", name: "approved", collection: "default" },
      ],
      _metainfo: { totalCount: 3 },
    })
  );

  await page.route(/\/api\/v1\/assets\/[^/]+\/tags$/, route => {
    const body = JSON.parse(route.request().postData() || "{}") as { name: string };
    rec.tagPosts.push({ name: body.name });
    return json(route, { uuid: "new-tag", name: body.name, collection: "default" });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/media-info$/, route =>
    json(route, opts.probe === null ? {} : {
      duration: PROBE_DURATION, frameRate: 23.976, width: 1920, height: 1080,
      videoCodec: "h264", audioCodec: "ac3", streamable: true,
    })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-token$/, route =>
    json(route, { token: "fake-media-token", expiresIn: 600 })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/poster/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: Buffer.from("") })
  );
  // Not decodable, and it does not need to be: what is under test is which URL was asked for.
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream/, route => {
    rec.streamRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "video/mp4", body: Buffer.from("") });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections(\?|$)/, route =>
    json(route, { data: detections(), _metainfo: { totalCount: 2 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts(\?|$)/, route =>
    json(route, { data: [transcript()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => json(route, asset()));
}

function recorder(): Recorder {
  return { streamRequests: [], tagPosts: [] };
}

async function open(page: Page, rec: Recorder, opts: { probe?: null } = {}) {
  await installMocks(page, rec, opts);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Assets", exact: true }).first().click();
  const link = page.getByText("episode.mkv").first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
}

test.describe("Asset detail player – mocked e2e", () => {
  test("the timeline spans the measured duration, not what the pipe has delivered", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    // 2580s is 43:00. The element would report a couple of seconds of arrived stream, and the
    // asset itself reports nothing at all, so this number can only have come from the probe.
    await expect(page.getByTestId("video-timeline-bar")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("43:00").first()).toBeVisible({ timeout: 10_000 });
  });

  test("the player carries no native control bar", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    const video = page.getByTestId("asset-video");
    await expect(video).toBeVisible({ timeout: 10_000 });

    // The native bar cannot seek this source and lies about its length, so offering it is worse
    // than offering nothing. Our own transport is beside it.
    await expect(video).not.toHaveAttribute("controls", /.*/);
    await expect(page.getByTestId("asset-video-playpause")).toBeVisible();
  });

  test("clicking the timeline re-requests the stream at that offset", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    await expect(page.getByTestId("video-timeline-bar")).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => rec.streamRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    await page.getByTestId("video-timeline-bar").click({ position: { x: bar.width / 2, y: 14 } });

    // Half of 2580 is 1290. A tolerance because the click lands on a pixel, not on a second - the
    // assertion is "it asked for the middle of the file", which is the thing that did not work.
    await expect.poll(() => {
      const last = rec.streamRequests[rec.streamRequests.length - 1] ?? "";
      const t = Number(new URL(last, "http://x").searchParams.get("t") ?? "0");
      return Math.abs(t - PROBE_DURATION / 2);
    }, { timeout: 10_000 }).toBeLessThan(60);
  });

  test("with no probe the player still renders and the timeline says nothing false", async ({ page }) => {
    const rec = recorder();
    await open(page, rec, { probe: null });

    // Degrading to zero is correct here; inventing a length would put every marker in the wrong
    // place and make a broken seek look like a working one.
    await expect(page.getByTestId("asset-video")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("asset-video-time")).toContainText("--:--");
  });

  // ── Layout ─────────────────────────────────────────────────

  test("the transport is inside the media area, not clipped off the bottom of it", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const slot = page.getByTestId("asset-media-area");
    const container = page.getByTestId("asset-video-container");
    const controls = page.getByTestId("asset-video-controls");
    await expect(controls).toBeVisible({ timeout: 10_000 });

    // The slot itself has to have a height. It is a flex item in a column whose other children —
    // timeline, tags, metadata, transcript — routinely overrun the pane, and the scroller below
    // has `flex-basis: 0`, so it has a scaled shrink factor of zero and never gives up any space:
    // all of it comes out of its siblings. On the deployment that squashed the media area to 0px,
    // and the player, having computed a perfectly correct 966x380, overflowed upward out of an
    // `overflow: hidden` parent. The page showed a timeline with no video above it.
    const slotBox = await slot.boundingBox();
    expect(slotBox!.height).toBeGreaterThan(150);

    // And the player is inside the slot, not hanging out of the top of it.
    const inner = await container.boundingBox();
    expect(inner!.y).toBeGreaterThanOrEqual(slotBox!.y - 1);
    expect(inner!.y + inner!.height).toBeLessThanOrEqual(slotBox!.y + slotBox!.height + 1);

    // The `sx` a caller passes sizes the *player*, and it used to size only the picture — so the
    // picture filled the slot on its own and the control bar was pushed past the bottom edge of
    // an `overflow: hidden` parent. Visible is not enough to catch that: a partly clipped bar
    // still reports visible. The bar has to be inside the box.
    const outer = await container.boundingBox();
    const bar = await controls.boundingBox();
    expect(outer).not.toBeNull();
    expect(bar).not.toBeNull();
    expect(bar!.y + bar!.height).toBeLessThanOrEqual(outer!.y + outer!.height + 1);
    expect(bar!.height).toBeGreaterThan(20);

    // And the picture is not taller than the box that clips it, which is what cut the top off it.
    const video = await page.getByTestId("asset-video").boundingBox();
    expect(video!.height).toBeLessThanOrEqual(outer!.height + 1);
  });

  test("dragging the divider past the tab labels leaves the icons", async ({ page }) => {
    // Pinned, because what is under test is a pixel threshold: at the project's default viewport
    // the sidebar is already close to it and the test would be measuring the viewport.
    await page.setViewportSize({ width: 1600, height: 900 });
    const rec = recorder();
    await open(page, rec);

    const sidebar = page.getByTestId("asset-sidebar");
    await expect(sidebar).toBeVisible({ timeout: 10_000 });
    const wide = await sidebar.boundingBox();

    // The labels used to be the floor on how narrow the sidebar could go: six words of tab text
    // is around 420px, and the divider simply stopped there however far it was dragged.
    await expect(sidebar).toHaveAttribute("data-compact", "false");
    await expect(page.getByRole("tab", { name: /overview/i })).toContainText(/overview/i);

    const body = await page.getByTestId("asset-video-container").boundingBox();
    await page.mouse.move(wide!.x - 3, wide!.y + 100);
    await page.mouse.down();
    await page.mouse.move(body!.x + body!.width * 2, wide!.y + 100, { steps: 10 });
    await page.mouse.up();

    await expect(sidebar).toHaveAttribute("data-compact", "true", { timeout: 5_000 });
    const narrow = await sidebar.boundingBox();
    expect(narrow!.width).toBeLessThan(wide!.width);
    // The label is gone but the tab is still identifiable and still reachable.
    await expect(page.getByRole("tab", { name: /overview/i })).toBeVisible();
  });

  // ── Faces ──────────────────────────────────────────────────

  async function openFacesTab(page: Page) {
    await page.getByRole("tab", { name: /faces/i }).click();
    await expect(page.getByTestId("asset-face-tile").first()).toBeVisible({ timeout: 10_000 });
  }

  test("clicking a face seeks to where it appears, a beat early", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    await openFacesTab(page);

    await page.getByTestId("asset-face-tile").nth(1).click();

    // Frame 24000 at 23.976fps is second 1001, and the request asks for 1000: a click lands a
    // quarter second early so the box lights up as the face comes round. Before this the panel
    // handed the frame *number* to the seek, which asked for second 24000 of a 43-minute file.
    await expect.poll(() => {
      const last = rec.streamRequests[rec.streamRequests.length - 1] ?? "";
      return Number(new URL(last, "http://x").searchParams.get("t") ?? "-1");
    }, { timeout: 10_000 }).toBe(1000);
  });

  test("the face that was clicked gets a box, and it flashes once", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    await openFacesTab(page);

    // Nothing is drawn at the start. Both faces are more than the window away from second zero,
    // and drawing them anyway is what "the boxes are just overlapped and make no sense" was.
    await expect(page.getByTestId("face-box")).toHaveCount(0);

    await page.getByTestId("asset-face-tile").nth(1).click();

    const box = page.getByTestId("face-box");
    await expect(box).toHaveCount(1, { timeout: 5_000 });
    await expect(box).toHaveAttribute("data-face-id", DETECTION_B);
    // Lit up on arrival, and dark again a quarter of a second later. A highlight that never
    // decays is just a second selection colour.
    await expect(box).toHaveAttribute("data-flashing", "true", { timeout: 2_000 });
    await expect(box).toHaveAttribute("data-flashing", "false", { timeout: 2_000 });
  });

  // ── Transcript ──────────────────────────────────────────

  test("a whisper transcript renders, though it carries segments rather than sections", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    // Nothing read `transcriptJson.segments`, so every machine transcript on the deployment drew
    // an empty panel while its text sat in the database.
    await expect(page.getByText("Jim Menard, Director of Photography.")).toBeVisible({ timeout: 10_000 });
    // Two chapters: the 125s line is more than a minute past the first, so it starts its own.
    await expect(page.getByTestId("transcript-search")).toBeVisible();
  });

  // ── Tagging ─────────────────────────────────────────────

  test("typing in the tag field suggests existing tag names", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const input = page.getByTestId("tag-input");
    await expect(input).toBeVisible({ timeout: 10_000 });
    await input.fill("inter");

    await expect(page.getByRole("option", { name: "interview" })).toBeVisible({ timeout: 5_000 });
    // Already on the asset, so offering it would be a no-op that looks like an action.
    await expect(page.getByRole("option", { name: "approved" })).toHaveCount(0);
  });

  test("choosing a suggestion tags the asset with that exact name", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const input = page.getByTestId("tag-input");
    await expect(input).toBeVisible({ timeout: 10_000 });
    await input.fill("estab");
    await page.getByRole("option", { name: "establishing-shot" }).click();

    // The point of the suggestion list: the stored spelling, not the typed prefix.
    await expect.poll(() => rec.tagPosts.length, { timeout: 10_000 }).toBe(1);
    expect(rec.tagPosts[0].name).toBe("establishing-shot");
  });

  test("a word that is not in the list is still tagged on Enter", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const input = page.getByTestId("tag-input");
    await expect(input).toBeVisible({ timeout: 10_000 });
    await input.fill("stargate");
    await input.press("Enter");

    // freeSolo: the list is a spelling aid, not a controlled vocabulary. Losing the ability to
    // coin a tag would be a worse regression than having no suggestions at all.
    await expect.poll(() => rec.tagPosts.length, { timeout: 10_000 }).toBe(1);
    expect(rec.tagPosts[0].name).toBe("stargate");
  });
});
