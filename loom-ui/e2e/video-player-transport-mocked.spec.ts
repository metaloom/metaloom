import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for AssetVideoPlayer's own transport and for the shared AssetThumbnail, in the two
 * places they are driven hardest: the asset detail view, and the workflow review queue - which hands
 * the *same* component instance one asset after another, without a `key`.
 *
 * The stream is never a real MP4 here. The element fails to decode it, which is fine: every
 * assertion is about which URL the player asked for and which offset it believes it is at, because
 * that is where the defects live.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_A = "22222222-2222-2222-2222-222222222222";
const ASSET_B = "33333333-3333-3333-3333-333333333333";
const DURATION = 2580;

const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** Keyframes every five seconds, on a fraction - see asset-player-mocked.spec.ts. */
function keyframeFor(t: number): number {
  if (!(t > 0)) return 0;
  return Math.max(0, Math.floor(t / 5) * 5 - 0.401);
}

function assetRecord(uuid: string, filename: string, mimeType: string) {
  return {
    uuid,
    file: { filename, mimeType, size: 4_500_000_000 },
    videoComponents: [],
    tags: [],
    annotations: [],
    status: { creator: { uuid: ME_UUID }, created: "2026-09-19T22:50:42Z" },
  };
}

interface Recorder {
  streamRequests: string[];
  seekPointRequests: string[];
  /** When set, a /stream-start for exactly this `t` is parked and then answered 500. */
  failSlowlyAt: number | null;
  releaseSlow: () => void;
}

async function installMocks(page: Page, assets: ReturnType<typeof assetRecord>[]): Promise<Recorder> {
  let parked: (() => void) | null = null;
  const rec: Recorder = {
    streamRequests: [],
    seekPointRequests: [],
    failSlowlyAt: null,
    releaseSlow: () => { parked?.(); parked = null; },
  };
  const byUuid = (url: string) => assets.find(a => url.includes(a.uuid)) ?? assets[0];

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/assets(\?|$)/, route => json(route, { data: assets, _metainfo: { totalCount: assets.length } }));
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => json(route, byUuid(route.request().url())));
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-info$/, route => json(route, {
    duration: DURATION, frameRate: 24, width: 1920, height: 1080, videoCodec: "h264", audioCodec: "ac3", streamable: true,
  }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-token$/, route => json(route, { token: "mt", expiresIn: 600 }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/poster/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream/, route => {
    rec.streamRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "video/mp4", body: Buffer.from("") });
  });
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream-start/, async route => {
    const url = route.request().url();
    rec.seekPointRequests.push(url);
    const requested = Number(new URL(url).searchParams.get("t") ?? "0");
    if (rec.failSlowlyAt !== null && requested === rec.failSlowlyAt) {
      await new Promise<void>(resolve => { parked = resolve; });
      return json(route, { message: "probe failed" }, 500);
    }
    return json(route, { requested, start: keyframeFor(requested) });
  });
  return rec;
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/** Asset detail for ASSET_A, with the player mounted and its first request out. */
async function openDetail(page: Page, rec: Recorder) {
  await page.goto(`/ui/assets/${ASSET_A}`);
  await login(page);
  await expect(page.getByTestId("asset-video")).toBeVisible({ timeout: 10_000 });
  await expect.poll(() => rec.streamRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);
}

const lastT = (urls: string[]) => new URL(urls.at(-1) ?? "http://x/").searchParams.get("t");

test.describe("Video player transport – mocked", () => {
  test("+10s and -10s seek by ten seconds, and -10s never goes below zero", async ({ page }) => {
    const rec = await installMocks(page, [assetRecord(ASSET_A, "episode.mkv", "video/x-matroska")]);
    await openDetail(page, rec);

    await page.getByTestId("asset-video-forward10").click();
    await expect.poll(() => lastT(rec.streamRequests), { timeout: 10_000 }).toBe("10");
    await expect(page.getByTestId("asset-video")).toHaveAttribute("data-stream-offset", String(keyframeFor(10)));
    await expect(page.getByTestId("asset-video-time")).toHaveText("0:10 / 43:00");

    await page.getByTestId("asset-video-back10").click();
    await page.getByTestId("asset-video-back10").click();
    // Back to the top of the file: an untimed request, and a clock at zero rather than -10.
    await expect(page.getByTestId("asset-video")).toHaveAttribute("data-stream-offset", "0", { timeout: 10_000 });
    expect(lastT(rec.streamRequests)).toBeNull();
    await expect(page.getByTestId("asset-video-time")).toHaveText("0:00 / 43:00");
  });

  test("mute remembers the level, and the level survives a reload", async ({ page }) => {
    await page.addInitScript(() => {
      if (window.localStorage.getItem("loom.player.volume") === null) window.localStorage.setItem("loom.player.volume", "0.3");
    });
    const rec = await installMocks(page, [assetRecord(ASSET_A, "episode.mkv", "video/x-matroska")]);
    await openDetail(page, rec);

    const volume = page.getByTestId("asset-video-volume");
    await expect(volume).toHaveAttribute("data-volume", "30");
    await page.getByTestId("asset-video-mute").click();
    await expect(volume).toHaveAttribute("data-volume", "0");
    // The element itself is muted, not just the slider drawn at zero.
    await expect(page.getByTestId("asset-video")).toHaveJSProperty("muted", true);
    await page.getByTestId("asset-video-mute").click();
    await expect(volume).toHaveAttribute("data-volume", "30");

    await volume.getByRole("slider").focus();
    await page.keyboard.press("ArrowRight");
    await expect(volume).toHaveAttribute("data-volume", "31");
    expect(await page.evaluate(() => window.localStorage.getItem("loom.player.volume"))).toBe("0.31");

    await page.reload();
    await expect(page.getByTestId("asset-video-volume")).toHaveAttribute("data-volume", "31", { timeout: 10_000 });
  });

  test("a slow seek-point lookup that fails cannot drag the player back past a newer seek", async ({ page }) => {
    const rec = await installMocks(page, [assetRecord(ASSET_A, "episode.mkv", "video/x-matroska")]);
    await openDetail(page, rec);

    // First seek: its lookup hangs, then fails.
    rec.failSlowlyAt = 10;
    await page.getByTestId("asset-video-forward10").click();
    await expect.poll(() => rec.seekPointRequests.length, { timeout: 10_000 }).toBe(1);

    // Second seek overtakes it and lands.
    await page.getByTestId("asset-video-forward10").click();
    await expect(page.getByTestId("asset-video")).toHaveAttribute("data-stream-offset", String(keyframeFor(20)), { timeout: 10_000 });

    // Now the first lookup's failure arrives. Its fallback used to apply regardless of order.
    rec.releaseSlow();
    await expect.poll(() => rec.seekPointRequests.length).toBe(2);
    await page.waitForTimeout(500);
    await expect(page.getByTestId("asset-video")).toHaveAttribute("data-stream-offset", String(keyframeFor(20)));
    expect(lastT(rec.streamRequests)).toBe("20");
  });
});

test.describe("Workflow queue reuses one player and one thumbnail – mocked", () => {
  test("the next video starts at its own beginning, not at the previous one's seek offset", async ({ page }) => {
    const assets = [assetRecord(ASSET_A, "episode-1.mkv", "video/x-matroska"), assetRecord(ASSET_B, "episode-2.mkv", "video/x-matroska")];
    const rec = await installMocks(page, assets);
    // Face mode walks the assets that have face clusters: give both one.
    await page.route(/\/api\/v1\/clusters(\?|$)/, route => json(route, {
      data: assets.map((a, i) => ({
        uuid: `c${i}`, name: null, type: "face", reviewStatus: "PENDING", assetUuid: a.uuid,
        clusterIndex: 0, score: 0.9, memberCount: 0, nodeKind: "facedetect", status: { creator: { uuid: ME_UUID } },
      })),
      _metainfo: { totalCount: 2 },
    }));

    await page.goto("/ui/");
    await login(page);
    await page.getByTestId("sidebar-item-/workflow").click();
    await page.getByTestId("workflow-mode-facedetection").click();
    await expect(page.getByText("episode-1.mkv")).toBeVisible({ timeout: 10_000 });
    const video = page.getByTestId("workflow-face-video");
    await expect(video).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("workflow-face-video-forward10").click();
    await expect(video).toHaveAttribute("data-stream-offset", String(keyframeFor(10)), { timeout: 10_000 });
    await expect.poll(() => lastT(rec.streamRequests.filter(u => u.includes(ASSET_A)))).toBe("10");

    // Next asset. The button keeps focus, and buttons do not swallow the queue's arrow keys.
    await page.keyboard.press("ArrowRight");
    await expect(page.getByText("episode-2.mkv")).toBeVisible({ timeout: 10_000 });

    await expect.poll(() => rec.streamRequests.filter(u => u.includes(ASSET_B)).length, { timeout: 10_000 }).toBeGreaterThan(0);
    // Every request for the new asset starts at its top: no `t` carried over from episode 1.
    expect(rec.streamRequests.filter(u => u.includes(ASSET_B)).map(u => new URL(u).searchParams.get("t"))).toEqual(
      rec.streamRequests.filter(u => u.includes(ASSET_B)).map(() => null));
    await expect(video).toHaveAttribute("data-stream-offset", "0");
    await expect(page.getByTestId("workflow-face-video-time")).toHaveText("0:00 / 43:00");
  });

  test("an asset whose preview failed does not leave the next one on a placeholder", async ({ page }) => {
    const assets = [assetRecord(ASSET_A, "broken.jpg", "image/jpeg"), assetRecord(ASSET_B, "fine.jpg", "image/jpeg")];
    await installMocks(page, assets);
    await page.route(/\/api\/v1\/assets\/[^/]+\/binary\/data/, route =>
      route.request().url().includes(ASSET_A)
        ? route.fulfill({ status: 404, contentType: "application/json", body: "{}" })
        : route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));

    await page.goto("/ui/");
    await login(page);
    await page.getByTestId("sidebar-item-/workflow").click();
    await expect(page.getByText("broken.jpg")).toBeVisible({ timeout: 10_000 });

    // The broken one degrades to the placeholder - its <img> is gone once the load fails.
    await expect(page.locator(`img[src*="${ASSET_A}/binary/data"]`)).toHaveCount(0, { timeout: 10_000 });

    await page.keyboard.press("ArrowRight");
    await expect(page.getByText("fine.jpg")).toBeVisible({ timeout: 10_000 });
    // Same AssetThumbnail instance, new src. A sticky "failed" flag kept the placeholder up here.
    const fine = page.locator(`img[src*="${ASSET_B}/binary/data"]`);
    await expect(fine).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => fine.evaluate(img => (img as HTMLImageElement).naturalWidth)).toBe(1);
  });
});
