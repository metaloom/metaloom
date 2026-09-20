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

interface Recorder {
  streamRequests: string[];
  tagPosts: { name: string }[];
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

  // ── Tagging ────────────────────────────────────────────────────────────

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
