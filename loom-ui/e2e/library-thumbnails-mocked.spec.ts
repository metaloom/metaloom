import { test, expect, Page } from "@playwright/test";

/**
 * Mocked test for the asset previews in the library grid.
 *
 * No running Loom backend is required: all REST calls are intercepted with `page.route`.
 *
 * An image renders an `<img>` pointing at its binary. A video renders an `<img>` pointing at a
 * server-rendered **poster frame** — and, crucially, still never fetches the binary. It used to
 * show the type placeholder instead, on the grounds that a browser cannot decode a video into an
 * `<img>`; that was true, and it left a library of videos as a wall of grey icons. The poster
 * route is a few KB of JPEG, so the tile is real without the grid pulling gigabytes.
 */

const LIB_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const IMAGE_UUID = "11111111-1111-1111-1111-111111111111";
const VIDEO_UUID = "22222222-2222-2222-2222-222222222222";

// 1x1 transparent PNG.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64"
);

function asset(uuid: string, filename: string, mimeType: string) {
  return {
    uuid,
    file: { filename, mimeType, size: 1024 },
    locations: [{ uuid: `${uuid}-loc`, libraryUuid: LIB_UUID }],
    status: { created: new Date(0).toISOString() },
  };
}

async function mockRest(page: Page) {
  const binaryRequests: string[] = [];
  const posterRequests: string[] = [];

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route(/\/api\/v1\/libraries(\?|$)/, route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [{ uuid: LIB_UUID, name: "Main", meta: {}, status: { created: new Date(0).toISOString() } }],
      }),
    })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [
          asset(IMAGE_UUID, "sunset.jpg", "image/jpeg"),
          asset(VIDEO_UUID, "beach.mp4", "video/mp4"),
        ],
      }),
    })
  );

  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+\/binary\/data$/, route => {
    binaryRequests.push(route.request().url());
    route.fulfill({ status: 200, contentType: "image/png", body: PNG });
  });

  // A video tile mints a short-lived token and then loads a poster with it.
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+\/media-token$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-mt", expiresIn: 600 }) })
  );
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+\/poster/, route => {
    posterRequests.push(route.request().url());
    route.fulfill({ status: 200, contentType: "image/jpeg", body: PNG });
  });

  return { binaryRequests, posterRequests };
}

async function loginAndGoToLibrary(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: /library|libraries|bibliothek/i }).first().click();
  await expect(page.getByRole("heading", { name: /libraries|bibliotheken/i })).toBeVisible({ timeout: 10_000 });
}

test.describe("Library asset previews – mocked", () => {
  test("an image asset renders its binary as a thumbnail", async ({ page }) => {
    const { binaryRequests } = await mockRest(page);
    await loginAndGoToLibrary(page);

    const thumb = page.locator(`img[src*="${IMAGE_UUID}/binary/data"]`);
    await expect(thumb).toBeVisible({ timeout: 10_000 });
    await expect(thumb).toHaveAttribute("alt", "sunset.jpg");

    await expect.poll(() => binaryRequests.filter(u => u.includes(IMAGE_UUID)).length).toBeGreaterThan(0);
  });

  test("a video asset renders a poster frame and still fetches no binary", async ({ page }) => {
    const { binaryRequests, posterRequests } = await mockRest(page);
    await loginAndGoToLibrary(page);

    const poster = page.locator(`img[src*="${VIDEO_UUID}/poster"]`);
    await expect(poster).toBeVisible({ timeout: 10_000 });

    // The load-bearing half of the original test, kept: the grid must not pull the video itself.
    expect(binaryRequests.filter(u => u.includes(VIDEO_UUID))).toHaveLength(0);
    await expect.poll(() => posterRequests.length).toBeGreaterThan(0);
  });

  test("the poster is requested at a tile-sized width, with a media token", async ({ page }) => {
    const { posterRequests } = await mockRest(page);
    await loginAndGoToLibrary(page);

    await expect(page.locator(`img[src*="${VIDEO_UUID}/poster"]`)).toBeVisible({ timeout: 10_000 });
    // Poll for the request rather than trusting visibility: an <img> is laid out, and therefore
    // "visible", before its bytes have been asked for.
    await expect.poll(() => posterRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);
    const url = new URL(posterRequests[0]);
    // `mt`, because an <img> cannot send an Authorization header and the session cookie is
    // Secure-flagged, so it is absent on every plain-HTTP deployment.
    expect(url.searchParams.get("mt")).toBe("fake-mt");
    // And a width, so a 180px tile does not decode a 1080p frame.
    expect(Number(url.searchParams.get("w"))).toBeGreaterThan(0);
  });
});
