import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for region tagging from the tag strip's `region-mode-toggle`.
 *
 * Until now only the backend spec drew a region, so nothing pinned the request body, the hint
 * text, or what disarming the toggle does to a captured region. Two surfaces:
 *
 *  - an **image** asset, where the armed toggle turns a drag on the picture into a box that
 *    travels as `area.{startX,startY,width,height}` in permille of the picture;
 *  - a **video** asset, where the same toggle turns a drag on the timeline into a range that
 *    travels as `area.{from,to}` in milliseconds — and does not seek.
 *
 * Routes exercised:
 *   GET  /api/v1/assets/:uuid          (re-read after each tag)
 *   POST /api/v1/assets/:uuid/tags
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const IMAGE_UUID = "22222222-2222-2222-2222-222222222222";
const VIDEO_UUID = "44444444-4444-4444-4444-444444444444";
/** Seconds, so a fraction of the bar converts to an expected timecode by multiplication. */
const DURATION = 100;

/** A 1x1 JPEG, so the media pane lays out a real picture for the drag rather than a broken one. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

interface StoredTag {
  uuid: string;
  name: string;
  collection: string;
  placementUuid: string;
  area?: Record<string, number>;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page, kind: "image" | "video") {
  const tags: StoredTag[] = [];
  const posts: Record<string, unknown>[] = [];
  const uuid = kind === "image" ? IMAGE_UUID : VIDEO_UUID;

  const asset = () => ({
    uuid,
    file: kind === "image"
      ? { filename: "harbour.jpg", mimeType: "image/jpeg", size: 1024 }
      : { filename: "harbour.mp4", mimeType: "video/mp4", size: 52_000_000 },
    // Milliseconds on the wire, like asset_video_comp.media_duration.
    ...(kind === "video" ? { videoComponents: [{ duration: DURATION * 1000, width: 1920, height: 1080 }] } : {}),
    tags,
    status: { creator: { uuid: ME_UUID }, created: new Date(0).toISOString() },
  });

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));
  await page.route(/\/api\/v1\/assets\/[^/]+\/binary\/data/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/tags$/, route => {
    if (route.request().method() !== "POST") return json(route, { data: tags });
    const body = route.request().postDataJSON() as { name: string; collection: string; area?: Record<string, number> };
    posts.push(body);
    const n = tags.length + 1;
    tags.push({ uuid: `tag-${n}`, name: body.name, collection: body.collection, placementUuid: `placement-${n}`, area: body.area });
    return json(route, { uuid: `tag-${n}`, name: body.name, collection: body.collection }, 201);
  });

  return { posts, uuid };
}

async function openAsset(page: Page, uuid: string) {
  await page.goto(`/ui/assets/${uuid}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("asset-name")).toBeVisible({ timeout: 10_000 });
}

/** Rubber-band a box over the picture, in fractions of the picture. */
async function drawRegion(page: Page, x0: number, y0: number, x1: number, y1: number) {
  const picture = page.getByTestId("zoomable-image-picture");
  await expect(picture).toBeVisible({ timeout: 10_000 });
  const box = await picture.boundingBox();
  if (!box) throw new Error("zoomable-image-picture has no bounding box");
  await page.mouse.move(box.x + box.width * x0, box.y + box.height * y0);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * x1, box.y + box.height * y1, { steps: 10 });
  await page.mouse.up();
}

/** Within a few permille: the mouse path rounds to whole pixels. */
function expectNear(actual: unknown, expected: number, tolerance: number) {
  expect(typeof actual).toBe("number");
  expect(Math.abs((actual as number) - expected)).toBeLessThan(tolerance);
}

test.describe("Region tags from the tag strip (mocked)", () => {
  test("image: the armed toggle captures a drawn box and the tag POST carries it in permille", async ({ page }) => {
    const { posts, uuid } = await installMocks(page, "image");
    await openAsset(page, uuid);

    const strip = page.getByTestId("asset-tags");
    await page.getByTestId("region-mode-toggle").click();
    await expect(strip.getByText("Draw a box on the image…", { exact: true })).toBeVisible();

    await drawRegion(page, 0.2, 0.3, 0.6, 0.7);
    await expect(strip.getByText("Region captured — type a name and press Enter", { exact: true })).toBeVisible({ timeout: 5_000 });
    // The pending box is drawn over the picture while it waits for a name.
    await expect(page.getByTestId("image-region")).toHaveCount(1);

    const input = page.getByTestId("tag-input");
    await input.fill("lighthouse");
    await input.press("Enter");

    await expect.poll(() => posts.length, { timeout: 5_000 }).toBe(1);
    expect(posts[0]).toMatchObject({ name: "lighthouse", collection: "default" });
    const area = posts[0].area as Record<string, number>;
    expectNear(area.startX, 200, 25);
    expectNear(area.startY, 300, 25);
    expectNear(area.width, 400, 25);
    expectNear(area.height, 400, 25);
    // A spatial region carries no timecodes.
    expect(area.from).toBeUndefined();

    // Re-read from the server: a region chip and its box on the picture.
    await expect(page.getByTestId("region-tag-chip").filter({ hasText: "lighthouse" })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("image-region")).toHaveCount(1);
    // Tagging disarms the mode.
    await expect(strip.getByText("Draw a box on the image…", { exact: true })).toHaveCount(0);
  });

  test("image: disarming the toggle discards the captured box, so the next tag is a plain one", async ({ page }) => {
    const { posts, uuid } = await installMocks(page, "image");
    await openAsset(page, uuid);

    const toggle = page.getByTestId("region-mode-toggle");
    await toggle.click();
    await drawRegion(page, 0.1, 0.1, 0.5, 0.5);
    await expect(page.getByTestId("image-region")).toHaveCount(1, { timeout: 5_000 });

    // Cancel: the box goes, the hint goes.
    await toggle.click();
    await expect(page.getByTestId("image-region")).toHaveCount(0);
    await expect(page.getByTestId("asset-tags").getByText(/Region captured|Draw a box/)).toHaveCount(0);

    // Disarmed, a drag on the picture draws nothing.
    await drawRegion(page, 0.1, 0.1, 0.5, 0.5);
    await expect(page.getByTestId("image-region")).toHaveCount(0);

    const input = page.getByTestId("tag-input");
    await input.fill("seaside");
    await input.press("Enter");

    await expect.poll(() => posts.length, { timeout: 5_000 }).toBe(1);
    expect(posts[0]).toEqual({ name: "seaside", collection: "default" });
    await expect(page.getByTestId("tag-chip").filter({ hasText: "seaside" })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("region-tag-chip")).toHaveCount(0);
  });

  test("video: the armed toggle turns a drag on the timeline into a range, in ms, without seeking", async ({ page }) => {
    const { posts, uuid } = await installMocks(page, "video");
    await openAsset(page, uuid);

    const strip = page.getByTestId("asset-tags");
    const bar = page.getByTestId("video-timeline-bar");
    await expect(bar).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("region-mode-toggle").click();
    await expect(strip.getByText("Drag a range on the timeline…", { exact: true })).toBeVisible();

    const box = await bar.boundingBox();
    if (!box) throw new Error("video-timeline-bar has no bounding box");
    const y = box.y + box.height / 2;
    await page.mouse.move(box.x + box.width * 0.25, y);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.6, y, { steps: 8 });
    // The in-progress band follows the pointer before anything is committed.
    await expect(page.getByTestId("video-timeline-range-selection")).toBeVisible();
    await page.mouse.up();
    await expect(page.getByTestId("video-timeline-range-selection")).toHaveCount(0);

    await expect(strip.getByText("Region captured — type a name and press Enter", { exact: true })).toBeVisible({ timeout: 5_000 });
    // A drag in range mode selects; it must not also move the playhead to where it ended.
    await expect(page.getByTestId("video-timeline-current-time")).toHaveText("0:00");

    const input = page.getByTestId("tag-input");
    await input.fill("sunset");
    await input.press("Enter");

    await expect.poll(() => posts.length, { timeout: 5_000 }).toBe(1);
    const area = posts[0].area as Record<string, number>;
    // 25% → 60% of a 100 s clip, in milliseconds; a pixel of mouse rounding is ~0.1 s.
    expectNear(area.from, 25_000, 1_000);
    expectNear(area.to, 60_000, 1_000);
    expect(area.startX).toBeUndefined();

    // The new range is drawn on the bar once the asset is re-read.
    await expect(page.getByTestId("video-timeline-range")).toHaveCount(1, { timeout: 5_000 });
    await expect(page.getByTestId("region-tag-chip").filter({ hasText: "sunset" })).toBeVisible();
  });

  test("video: unarmed, the same drag seeks instead of selecting a range", async ({ page }) => {
    const { posts, uuid } = await installMocks(page, "video");
    await openAsset(page, uuid);

    const bar = page.getByTestId("video-timeline-bar");
    await expect(bar).toBeVisible({ timeout: 10_000 });
    const box = await bar.boundingBox();
    if (!box) throw new Error("video-timeline-bar has no bounding box");

    await page.mouse.click(box.x + box.width * 0.5, box.y + box.height / 2);
    await expect(page.getByTestId("video-timeline-range-selection")).toHaveCount(0);
    await expect(page.getByTestId("video-timeline-current-time")).toHaveText("0:50", { timeout: 5_000 });

    const input = page.getByTestId("tag-input");
    await input.fill("plain");
    await input.press("Enter");
    await expect.poll(() => posts.length, { timeout: 5_000 }).toBe(1);
    expect(posts[0].area).toBeUndefined();
  });
});
