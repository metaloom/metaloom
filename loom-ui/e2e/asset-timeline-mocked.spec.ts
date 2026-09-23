import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the asset-detail video timeline — no running Loom backend required.
 *
 * `VideoTimeline` is the seek surface under every video in the product and had no owning spec,
 * so nothing guarded either half of its contract: that a marker lands where its timecode says,
 * and that clicking the bar moves the playhead there.
 *
 * Two things about the markers are worth stating up front, because they decide what this file
 * can seed:
 *
 *  - The marker sources are the asset's **annotations** and its **temporal tags**, both of which
 *    ride along on `GET /api/v1/assets/:uuid` (`annotations[]` / `tags[]`) and carry their
 *    timecodes in `area.from` / `area.to` as **milliseconds**. The timeline works in seconds.
 *  - Comments are a declared marker source in the component, but `commentResponseToComment`
 *    drops the timestamps, so a REST comment can never produce one. Seeding comments here would
 *    assert nothing; that gap belongs to the comment mapper, not to the timeline.
 *
 * There is no `<video>` element to read `currentTime` back from, and that is a property of this
 * fixture rather than of the screen: `AssetVideoPlayer` needs a media token, and the catch-all
 * mock answers `/media-token` with an empty collection, so the player renders its placeholder. A
 * seek is therefore observable through the playhead and the time readout — which is what a user
 * sees anyway. `asset-player-mocked.spec.ts` is the file that mocks the media routes and asserts
 * the player itself.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

/** Seconds, so the arithmetic below reads like the timeline does. */
/** Seconds, which is what the timeline and every assertion below count in. */
const DURATION = 120;

/**
 * The same duration on the wire, where it is milliseconds.
 *
 * `asset_video_comp.media_duration` is a millisecond column and the REST layer passes it through
 * unchanged; `toAsset` divides on the way in. Serving seconds here would mock an API that does not
 * exist and would hide a unit bug rather than catch one.
 */
const DURATION_MS = DURATION * 1000;
const ANNOTATION_RANGE_START = 30;
const ANNOTATION_RANGE_END = 45;
const ANNOTATION_POINT = 90;
const TAG_POINT = 60;
/** A region tag: a tag with a *span*, which is the thing a reviewer drags. */
const REGION_TAG_START = 70;
const REGION_TAG_END = 100;

const ANNOTATION_RANGE_UUID = "aaaaaaaa-0000-0000-0000-000000000001";
const ANNOTATION_POINT_UUID = "aaaaaaaa-0000-0000-0000-000000000002";
const TEMPORAL_TAG_UUID = "bbbbbbbb-0000-0000-0000-000000000001";
const PLAIN_TAG_UUID = "bbbbbbbb-0000-0000-0000-000000000002";
const REGION_TAG_UUID = "bbbbbbbb-0000-0000-0000-000000000003";
/** A placement has an identity of its own: a tag may sit on one asset several times. */
const REGION_PLACEMENT_UUID = "cccccccc-0000-0000-0000-000000000001";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/**
 * A video asset with two annotations and two tags, only one of which is temporal.
 *
 * `duration` comes off `videoComponents[0]`, in milliseconds — the file's mime type alone decides
 * that the asset *is* a video, but a video with no duration divides every marker position by zero.
 */
function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "mock-clip.mp4", mimeType: "video/mp4", size: 52_000_000 },
    videoComponents: [{ duration: DURATION_MS, width: 1920, height: 1080 }],
    tags: [
      {
        uuid: TEMPORAL_TAG_UUID,
        name: "chorus",
        collection: "music",
        area: { from: TAG_POINT * 1000 },
      },
      // No area at all: a plain label on the whole asset, and never a marker.
      { uuid: PLAIN_TAG_UUID, name: "approved", collection: "review" },
      {
        uuid: REGION_TAG_UUID,
        name: "solo",
        collection: "music",
        placementUuid: REGION_PLACEMENT_UUID,
        area: { from: REGION_TAG_START * 1000, to: REGION_TAG_END * 1000 },
      },
    ],
    annotations: [
      {
        uuid: ANNOTATION_RANGE_UUID,
        type: "note",
        title: "Colour shift",
        description: "Grade drifts warm across the cut",
        area: { from: ANNOTATION_RANGE_START * 1000, to: ANNOTATION_RANGE_END * 1000 },
        status: { creator: { uuid: ME_UUID }, created: "2026-07-01T09:00:00Z" },
      },
      {
        uuid: ANNOTATION_POINT_UUID,
        type: "note",
        title: "Audio pop",
        area: { from: ANNOTATION_POINT * 1000 },
        status: { creator: { uuid: ME_UUID }, created: "2026-07-01T09:05:00Z" },
      },
    ],
    status: { creator: { uuid: ME_UUID }, created: "2026-07-01T08:00:00Z" },
  };
}

/** What a placement move sent, so a drag can be checked against the wire rather than the pixels. */
interface PlacementRecorder { moves: { path: string; body: unknown }[] }

async function installMocks(page: Page, rec?: PlacementRecorder) {
  // Catch-all first (lowest priority) — empty collections for the many list endpoints
  // AssetDetail fans out to (reactions, comments, tasks, transcripts, detections, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/tag-placements\/[^/]+$/, route => {
    rec?.moves.push({
      path: new URL(route.request().url()).pathname,
      body: route.request().postDataJSON(),
    });
    return json(route, { uuid: REGION_TAG_UUID, name: "solo", collection: "music" });
  });
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));
}

async function loginAndOpenAssetDetail(page: Page, rec?: PlacementRecorder) {
  await installMocks(page, rec);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Assets", exact: true }).first().click();
  const assetLink = page.getByText("mock-clip.mp4").first();
  await expect(assetLink).toBeVisible({ timeout: 10_000 });
  await assetLink.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  await expect(page.getByTestId("video-timeline-bar")).toBeVisible({ timeout: 10_000 });
}

/**
 * Where an element sits on the bar, as a fraction of the bar's width — the same 0..1 the
 * component computes positions from, recovered from what actually rendered.
 */
async function centreFraction(page: Page, testId: string, markerId?: string): Promise<number> {
  const bar = await page.getByTestId("video-timeline-bar").boundingBox();
  const selector = markerId
    ? `[data-testid="${testId}"][data-marker-id="${markerId}"]`
    : `[data-testid="${testId}"]`;
  const box = await page.locator(selector).boundingBox();
  if (!bar || !box) throw new Error(`no bounding box for ${testId} ${markerId ?? ""}`);
  return (box.x + box.width / 2 - bar.x) / bar.width;
}

/** "1:05" → 65. The readout is the only rendered form of the current time. */
function readoutSeconds(text: string): number {
  const parts = text.split(":").map(Number);
  return parts.reduce((acc, part) => acc * 60 + part, 0);
}

test.describe("Asset timeline – mocked e2e", () => {
  test("one marker per timed annotation and temporal tag, positioned by timecode", async ({ page }) => {
    await loginAndOpenAssetDetail(page);

    // Two of the three are points, and only those draw a dot. The one carrying an end time is
    // drawn as a band instead: the dot used to be drawn as well, sitting exactly on top of the
    // band's 8px start handle, which is why the left edge of a region could never be grabbed.
    // The plain tag has no area and must not appear at all — the timeline is a time axis, and a
    // whole-asset label has no place on it.
    await expect(page.getByTestId("video-timeline-marker")).toHaveCount(2);

    for (const [uuid, time] of [
      [ANNOTATION_POINT_UUID, ANNOTATION_POINT],
      [TEMPORAL_TAG_UUID, TAG_POINT],
    ] as const) {
      expect(await centreFraction(page, "video-timeline-marker", uuid)).toBeCloseTo(time / DURATION, 2);
    }

    // Two things carry an end time — an annotation and a region tag — and each draws a band.
    await expect(page.getByTestId("video-timeline-range")).toHaveCount(2);
    const range = page.locator(`[data-testid=video-timeline-range][data-marker-id="${ANNOTATION_RANGE_UUID}"]`);
    await expect(range).toHaveCount(1);

    // Two handles per band, and they are the thing that makes a region editable at all.
    await expect(page.getByTestId("video-timeline-range-handle")).toHaveCount(4);

    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    const rangeBox = (await range.boundingBox())!;
    expect((rangeBox.x - bar.x) / bar.width).toBeCloseTo(ANNOTATION_RANGE_START / DURATION, 2);
    expect(rangeBox.width / bar.width).toBeCloseTo((ANNOTATION_RANGE_END - ANNOTATION_RANGE_START) / DURATION, 2);
  });

  test("clicking the bar seeks to that timecode", async ({ page }) => {
    await loginAndOpenAssetDetail(page);

    const readout = page.getByTestId("video-timeline-current-time");
    await expect(readout).toHaveText("0:00");
    expect(await centreFraction(page, "video-timeline-playhead")).toBeCloseTo(0, 2);

    // 10% along — deliberately clear of all three markers, which stop the click before it
    // reaches the bar (clicking a marker jumps to its tab, it does not seek).
    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    const offsetX = Math.round(bar.width * 0.1);
    await page.getByTestId("video-timeline-bar").click({ position: { x: offsetX, y: 14 } });

    // The pointer lands on a whole device pixel, so the seek is only as precise as the bar is
    // wide — assert the second the user sees, within the rounding the readout already does.
    const expected = (offsetX / bar.width) * DURATION;
    await expect.poll(async () => readoutSeconds((await readout.textContent()) ?? "0:00"))
      .toBeCloseTo(expected, 0);

    // The playhead is driven by the same state, so it must have moved with the readout. It
    // slides there over a 50ms transition, which is why this polls rather than reads once.
    await expect.poll(() => centreFraction(page, "video-timeline-playhead"))
      .toBeCloseTo(offsetX / bar.width, 2);
  });

  test("a region tag's end handle can be grabbed, and the drag is persisted as a placement move", async ({ page }) => {
    const rec: PlacementRecorder = { moves: [] };
    await loginAndOpenAssetDetail(page, rec);

    const handle = page.locator(
      `[data-testid=video-timeline-range-handle][data-marker-id="${REGION_TAG_UUID}"][data-edge=end]`);
    await expect(handle).toBeVisible({ timeout: 10_000 });

    // Grab it and drag it a fifth of the bar to the right. Three separate things used to stop
    // this: the band's own `z-index` trapped its handles underneath the transcript tiles that
    // cover the lower half of the bar, the marker dot for the same tag sat exactly on the start
    // handle, and the drag handler in the asset view only knew about annotations, so a tag's
    // handle sprang back on release having changed nothing.
    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    const grip = (await handle.boundingBox())!;
    await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
    await page.mouse.down();
    await page.mouse.move(grip.x + grip.width / 2 + bar.width * 0.1, grip.y + grip.height / 2, { steps: 8 });
    await page.mouse.up();

    await expect.poll(() => rec.moves.length, { timeout: 5_000 }).toBe(1);
    // The *placement* uuid, not the tag's: a tag may sit on this asset several times, and
    // re-attaching under a new placement would replace who attached it and when.
    expect(rec.moves[0].path).toContain(REGION_PLACEMENT_UUID);

    const area = (rec.moves[0].body as { area: { from?: number; to?: number } }).area;
    // Milliseconds on the wire, and the end moved right by roughly a tenth of the duration.
    expect(area.to).toBeGreaterThan(REGION_TAG_END * 1000);
    expect(area.to! / 1000).toBeCloseTo(REGION_TAG_END + DURATION * 0.1, -1);
    // The other edge travels unchanged rather than being left out or reset.
    expect(area.from).toBe(REGION_TAG_START * 1000);
  });

  test("clicking past the last marker seeks near the end rather than clamping to zero", async ({ page }) => {
    await loginAndOpenAssetDetail(page);

    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    const offsetX = Math.round(bar.width * 0.95);
    await page.getByTestId("video-timeline-bar").click({ position: { x: offsetX, y: 14 } });

    const readout = page.getByTestId("video-timeline-current-time");
    await expect.poll(async () => readoutSeconds((await readout.textContent()) ?? "0:00"))
      .toBeCloseTo((offsetX / bar.width) * DURATION, 0);

    // Never past the end: the seek is clamped to the asset duration, not to the bar's geometry.
    expect(readoutSeconds((await readout.textContent())!)).toBeLessThanOrEqual(DURATION);
  });
});
