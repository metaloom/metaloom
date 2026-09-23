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

/**
 * Where a stream asked to start at `t` really starts, for the mocked server.
 *
 * Keyframes every five seconds, landing on a fraction rather than a round number, because that is
 * what a real broadcast rip does — 599.599, not 600 — and an integer grid would let a player that
 * truncates to whole seconds pass.
 */
export function mockKeyframeFor(t: number): number {
  if (!(t > 0)) return 0;
  return Math.max(0, Math.floor(t / 5) * 5 - 0.401);
}

interface Recorder {
  streamRequests: string[];
  /** Every `/stream-start` URL the player asked before switching source. */
  seekPointRequests: string[];
  tagPosts: { name: string }[];
  /** Every `/search/results` URL the transcript search box produced. */
  searchRequests: string[];
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
  // Registered after the stream route on purpose — the pattern above matches this path too, and
  // Playwright gives the last registration first refusal.
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream-start/, route => {
    const url = route.request().url();
    rec.seekPointRequests.push(url);
    const requested = Number(new URL(url).searchParams.get("t") ?? "0");
    return json(route, { requested, start: mockKeyframeFor(requested) });
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

  // Search: available, lexical only, and one transcript window that answers "pegasus".
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "postgres", available: true, capabilities: ["LEXICAL", "HIGHLIGHT"], documentCount: 9, dirtyCount: 0 })
  );
  await page.route(/\/api\/v1\/search\/results/, route => {
    rec.searchRequests.push(route.request().url());
    return json(route, {
      data: [{
        type: "transcript",
        uuid: "66666666-6666-6666-6666-666666666666",
        assetUuid: ASSET_UUID,
        score: 0.8,
        title: "episode.mkv",
        subtitle: "00:02:05 · en ggml-large-v3-turbo.bin",
        timeFromMs: 125_000,
        highlights: ["So here we have the <b>Pegasus</b>."],
      }],
      _metainfo: { totalHits: 1, totalExact: true, perPage: 25, offset: 0, tookMs: 3, provider: "postgres", capabilities: [], warnings: [] },
    });
  });

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => json(route, asset()));
}

function recorder(): Recorder {
  return { streamRequests: [], seekPointRequests: [], tagPosts: [], searchRequests: [] };
}

/**
 * Sign in, if the session did not already survive.
 *
 * Idempotent on purpose: the JWT lives in sessionStorage, so a `page.reload()` comes back already
 * authenticated and waiting for a login form that will never appear is a 30 second timeout.
 */
async function login(page: Page) {
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function open(page: Page, rec: Recorder, opts: { probe?: null } = {}) {
  await installMocks(page, rec, opts);
  await page.goto("/");
  await login(page);

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

  /**
   * The defect this pins: the player used to treat the offset it *asked for* as the origin of its
   * clock, but a stream-copied video can only begin on a keyframe, so the response starts earlier
   * — up to five seconds on a broadcast rip. Every position the player then reported was that much
   * too large, and everything drawn against time inherited it: the transcript highlighted a line
   * the viewer had not reached, and clicking a phrase played something else. On a 43-minute
   * episode with 1055 utterances it reads as "the audio is out of step with the transcript".
   */
  test("the clock starts where the response starts, not where the seek asked", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    await expect(page.getByTestId("video-timeline-bar")).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => rec.streamRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    await page.getByTestId("video-timeline-bar").click({ position: { x: bar.width / 2, y: 14 } });

    // The player must ask before it switches source: the answer has to be in hand when the new
    // element is built, or there is a window in which the clock is wrong again.
    await expect.poll(() => rec.seekPointRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    const asked = Number(new URL(rec.seekPointRequests.at(-1)!).searchParams.get("t") ?? "0");
    // Not "roughly the middle" — exactly the keyframe the server named, to the fraction. A player
    // rounding this to a whole second is the same bug with a smaller error.
    await expect(page.getByTestId("asset-video"))
      .toHaveAttribute("data-stream-offset", String(mockKeyframeFor(asked)), { timeout: 10_000 });
    // And the request still carries the position that was *asked for*, not the keyframe that came
    // back. Sending the answer back looks tidier and snaps a second time: ffmpeg subtracts a seek
    // margin before it looks, so a keyframe time handed straight back lands on the one before it.
    await expect.poll(() => {
      const last = rec.streamRequests.at(-1) ?? "";
      return new URL(last, "http://x").searchParams.get("t");
    }, { timeout: 10_000 }).toBe(String(asked));
  });

  test("a seek to the top of the file needs no round trip", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    await expect(page.getByTestId("asset-video")).toBeVisible({ timeout: 10_000 });

    // Nothing to snap to at zero, and the first request must not wait on a probe to find that out.
    await expect(page.getByTestId("asset-video")).toHaveAttribute("data-stream-offset", "0");
    expect(rec.seekPointRequests).toEqual([]);
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

  test("the tab labels appear exactly when they fit, and never clipped", async ({ page }) => {
    // Pinned, because what is under test is a measurement against a pixel width.
    await page.setViewportSize({ width: 1920, height: 900 });
    const rec = recorder();
    await open(page, rec);

    const sidebar = page.getByTestId("asset-sidebar");
    await expect(sidebar).toBeVisible({ timeout: 10_000 });
    const strip = page.locator("[data-testid=asset-sidebar] .MuiTabs-scroller");

    // Whatever state the default split lands in, the invariant is the same and it is the one
    // that was broken: the strip is `variant="scrollable"`, so labels that do not fit are not
    // ellipsised — the tabs past the edge are simply not there. Six labels in a 30% sidebar
    // used to mean two panels that could not be reached.
    const fits = async () => {
      const { scrollWidth, clientWidth } = await strip.evaluate((el) => ({
        scrollWidth: el.scrollWidth, clientWidth: el.clientWidth,
      }));
      return scrollWidth <= clientWidth + 1;
    };
    await expect.poll(fits, { timeout: 5_000 }).toBe(true);

    // Widen the sidebar until the words fit, and they come back on their own — the threshold is
    // measured from what the strip needed, not read off a constant.
    const body = (await page.getByTestId("asset-video-container").boundingBox())!;
    const before = (await sidebar.boundingBox())!;
    await page.mouse.move(before.x - 3, before.y + 100);
    await page.mouse.down();
    await page.mouse.move(body.x + 200, before.y + 100, { steps: 10 });
    await page.mouse.up();

    await expect(sidebar).toHaveAttribute("data-compact", "false", { timeout: 5_000 });
    await expect(page.getByRole("tab", { name: /overview/i })).toContainText(/overview/i);
    expect(await fits()).toBe(true);

    // And all the way the other way: the labels go, the icons stay, every tab is still on the
    // strip, and the divider stops before the icons themselves would scroll out of view.
    const wide = (await sidebar.boundingBox())!;
    await page.mouse.move(wide.x - 3, wide.y + 100);
    await page.mouse.down();
    await page.mouse.move(body.x + body.width * 3, wide.y + 100, { steps: 10 });
    await page.mouse.up();

    await expect(sidebar).toHaveAttribute("data-compact", "true", { timeout: 5_000 });
    const narrow = (await sidebar.boundingBox())!;
    expect(narrow.width).toBeLessThan(wide.width);
    await expect(page.getByRole("tab", { name: /overview/i })).toBeVisible();
    expect(await fits()).toBe(true);
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

    // Frame 24000 at 23.976fps is second 1001.001, and the seek asks for 1000.751: a click lands a
    // quarter second early so the box lights up as the face comes round. Before this the panel
    // handed the frame *number* to the seek, which asked for second 24000 of a 43-minute file.
    //
    // Read off the seek-point request rather than the stream URL: the stream can only begin on a
    // keyframe, so the position the player *wants* and the offset it ends up fetching are two
    // different numbers, and this test is about the first of them.
    //
    // 1000.751 and not 1000: the seek carries the fraction now. It used to be floored on its way
    // into the stream URL, which is a quarter-second error on top of the keyframe one and pointless
    // when the server takes a fractional offset.
    await expect.poll(() => {
      const last = rec.seekPointRequests[rec.seekPointRequests.length - 1] ?? "";
      return Number(new URL(last, "http://x").searchParams.get("t") ?? "-1");
    }, { timeout: 10_000 }).toBeCloseTo(1000.751, 3);
    await expect.poll(() => {
      const last = rec.streamRequests[rec.streamRequests.length - 1] ?? "";
      return Number(new URL(last, "http://x").searchParams.get("t") ?? "-1");
    }, { timeout: 10_000 }).toBeCloseTo(1000.751, 3);
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

  test("the transcript search box asks the server for this asset's windows only", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    await page.getByTestId("transcript-search-input").fill("pegasus");

    await expect(page.getByTestId("transcript-search-hit")).toHaveCount(1, { timeout: 10_000 });
    const url = new URL(rec.searchRequests[rec.searchRequests.length - 1]);
    // `?asset=` is what makes this "search inside this episode" rather than "search everything
    // and hope the right file is near the top".
    expect(url.searchParams.get("asset")).toBe(ASSET_UUID);
    expect(url.searchParams.get("types")).toBe("transcript");
    expect(url.searchParams.get("q")).toBe("pegasus");
  });

  test("clicking a transcript hit seeks to the minute it was said in", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    await page.getByTestId("transcript-search-input").fill("pegasus");
    const hit = page.getByTestId("transcript-search-hit").first();
    await expect(hit).toBeVisible({ timeout: 10_000 });
    await expect(hit).toHaveAttribute("data-time-ms", "125000");
    await hit.click();

    // 125000ms is second 125, and that is where the player is asked to go. Before the transcript
    // index was windowed every hit reported offset 0 and this could only ever have asked for 0.
    await expect.poll(() => {
      const last = rec.seekPointRequests[rec.seekPointRequests.length - 1] ?? "";
      return Number(new URL(last, "http://x").searchParams.get("t") ?? "-1");
    }, { timeout: 10_000 }).toBe(125);
    // The stream then comes from the keyframe before it, and the player's clock is set to *that*.
    // Adding the two together as though the response began at 125 is the drift this pins.
    await expect(page.getByTestId("asset-video"))
      .toHaveAttribute("data-stream-offset", String(mockKeyframeFor(125)), { timeout: 10_000 });

    // And it takes the reader to the chapter, not only the playhead: a hit several screens down
    // a 43-minute transcript that quietly repainted a highlight below the fold read as a click
    // that had done nothing.
    const revealed = page.locator("[data-testid=transcript-section][data-revealed=true]");
    await expect(revealed).toHaveCount(1, { timeout: 5_000 });
    await expect(page.getByTestId("transcript-section").nth(1)).toHaveAttribute("data-revealed", "true");
  });

  test("the semantic mode chip is absent while the provider cannot serve it", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    // The status mock advertises LEXICAL only. A control whose only outcome is a 400 is worse
    // than no control, so it is not rendered at all.
    await expect(page.getByTestId("transcript-search-panel")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("transcript-search-mode-SEMANTIC")).toHaveCount(0);
  });

  // ── Layout: folding and resizing ────────────────────────

  test("the sections fold, and the fold survives a reload", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const metadata = page.getByTestId("asset-section").filter({ has: page.locator("[data-section-id='metadata']") });
    const section = page.locator("[data-section-id='metadata']");
    await expect(section).toBeVisible({ timeout: 10_000 });
    // Metadata ships shut: it is reference material, and it used to be the one band that scrolled
    // while the transcript below it was unreachable.
    await expect(section).toHaveAttribute("data-expanded", "false");

    await section.getByTestId("asset-section-toggle").click();
    await expect(section).toHaveAttribute("data-expanded", "true");

    await page.reload();
    await login(page);
    await expect(page.locator("[data-section-id='metadata']")).toHaveAttribute("data-expanded", "true", { timeout: 10_000 });
    expect(metadata).toBeTruthy();
  });

  test("the timeline shows transcript chapters only while the transcript is unfolded", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const tiles = page.getByTestId("video-timeline-transcript");
    await expect(tiles).toHaveAttribute("data-visible", "true", { timeout: 10_000 });
    // Two chapters: the 125s line is more than a minute past the first, so it starts its own.
    await expect(page.getByTestId("video-timeline-transcript-tile")).toHaveCount(2);

    await page.locator("[data-section-id='transcript']").getByTestId("asset-section-toggle").click();
    await expect(tiles).toHaveAttribute("data-visible", "false");
  });

  test("clicking a chapter tile opens the transcript and takes the reader to that chapter", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const section = page.locator("[data-section-id='transcript']");
    await expect(section).toHaveAttribute("data-expanded", "true", { timeout: 10_000 });

    // Shut it first, because the fold being closed is the case that used to look like a click
    // that did nothing: the tile seeked, the highlight moved, and all of it happened inside a
    // panel that was not in the document.
    await section.getByTestId("asset-section-toggle").click();
    await expect(section).toHaveAttribute("data-expanded", "false");

    // With the fold shut the tiles are faded out and ignore the pointer, so this is the reverse
    // trip: open it, click the second chapter, and the panel must both open and land on it.
    await section.getByTestId("asset-section-toggle").click();
    await expect(section).toHaveAttribute("data-expanded", "true");

    await page.getByTestId("video-timeline-transcript-tile").nth(1).click();

    // Exactly one chapter is ringed, and it is the one the tile pointed at — the second.
    const revealed = page.locator("[data-testid=transcript-section][data-revealed=true]");
    await expect(revealed).toHaveCount(1, { timeout: 5_000 });
    const all = page.getByTestId("transcript-section");
    await expect(all.nth(1)).toHaveAttribute("data-revealed", "true");

    // And the playhead moved with it: the running highlight in the transcript is driven by the
    // current time, so a reveal that did not seek would stop following as soon as play resumed.
    await expect(page.getByTestId("video-timeline-current-time")).not.toHaveText("0:00");
  });

  test("the media area can be dragged taller and remembers it", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);

    const slot = page.getByTestId("asset-media-area");
    await expect(slot).toBeVisible({ timeout: 10_000 });
    const before = (await slot.boundingBox())!.height;

    const handle = page.getByTestId("asset-media-resize");
    const grip = (await handle.boundingBox())!;
    await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
    await page.mouse.down();
    await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2 + 90, { steps: 8 });
    await page.mouse.up();

    await expect.poll(async () => (await slot.boundingBox())!.height, { timeout: 5_000 })
      .toBeGreaterThan(before + 40);

    // A preference about how you review, not about this file, so it outlives the page.
    const after = (await slot.boundingBox())!.height;
    await page.reload();
    await login(page);
    await expect.poll(async () => (await page.getByTestId("asset-media-area").boundingBox())!.height, { timeout: 10_000 })
      .toBeCloseTo(after, -1);
  });

  test("the boxes are drawn in a frame of their own, inside the media slot", async ({ page }) => {
    const rec = recorder();
    await open(page, rec);
    await openFacesTab(page);
    await page.getByTestId("asset-face-tile").nth(1).click();

    // The overlay lives in its own measured frame rather than directly in the picture box, which
    // is what lets it be the size of the *picture* — see fitContain and its unit tests for the
    // arithmetic. It cannot be exercised end to end here: the mocked stream has no decodable
    // body, so `loadedmetadata` never fires and the frame correctly falls back to filling the
    // box. What this pins is that the frame exists, is inside the slot, and holds the boxes.
    const frame = page.getByTestId("asset-video-picture");
    await expect(frame).toBeVisible({ timeout: 10_000 });
    const slot = (await page.getByTestId("asset-media-area").boundingBox())!;
    const box = (await frame.boundingBox())!;
    expect(box.width).toBeLessThanOrEqual(slot.width + 1);
    expect(box.height).toBeLessThanOrEqual(slot.height + 1);
    await expect(frame.getByTestId("face-box")).toHaveCount(1);
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
