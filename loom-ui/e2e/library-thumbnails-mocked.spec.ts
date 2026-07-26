import { test, expect, Page } from "@playwright/test";

/**
 * Mocked test for the asset previews in the library grid.
 *
 * No running Loom backend is required: all REST calls are intercepted with `page.route`, and the
 * binary endpoint serves a 1x1 PNG. Asserts that an image asset renders an `<img>` pointing at its
 * binary, while a video keeps the type placeholder — the browser cannot decode a video into an
 * `<img>`, so a preview there would only ever be a broken image.
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

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/libraries", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [{ uuid: LIB_UUID, name: "Main", meta: {}, status: { created: new Date(0).toISOString() } }],
      }),
    })
  );

  await page.route("**/api/v1/assets", route =>
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

  return binaryRequests;
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
    const binaryRequests = await mockRest(page);
    await loginAndGoToLibrary(page);

    const thumb = page.locator(`img[src*="${IMAGE_UUID}/binary/data"]`);
    await expect(thumb).toBeVisible({ timeout: 10_000 });
    await expect(thumb).toHaveAttribute("alt", "sunset.jpg");

    await expect.poll(() => binaryRequests.filter(u => u.includes(IMAGE_UUID)).length).toBeGreaterThan(0);
  });

  test("a video asset keeps the placeholder and fetches no binary", async ({ page }) => {
    const binaryRequests = await mockRest(page);
    await loginAndGoToLibrary(page);

    await expect(page.locator(`img[src*="${IMAGE_UUID}/binary/data"]`)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(`img[src*="${VIDEO_UUID}/binary/data"]`)).toHaveCount(0);
    expect(binaryRequests.filter(u => u.includes(VIDEO_UUID))).toHaveLength(0);
  });
});
