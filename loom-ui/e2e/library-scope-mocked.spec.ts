import { test, expect, Page } from "@playwright/test";

/**
 * Mocked test for scoping the Library asset list to the selected library.
 *
 * No running Loom backend is required: all REST calls are intercepted with
 * `page.route`. Two libraries and four assets (one per library, one shared, one
 * without any location) are served; the test asserts that the grid, the header
 * stats and the sidebar counts follow `asset.locations[].libraryUuid`.
 */

const SHA512 = "a".repeat(128);

function libraryResponse(uuid: string, name: string) {
  return { uuid, name, meta: {}, status: { created: new Date(0).toISOString() } };
}

function assetResponse(uuid: string, filename: string, mimeType: string, libraryUuids: string[] | undefined) {
  return {
    uuid,
    file: { filename, mimeType, size: 1024, origin: "upload" },
    hashes: { sha512: SHA512 },
    tags: [],
    locations: libraryUuids?.map((libraryUuid, i) => ({ uuid: `${uuid}-loc-${i}`, libraryUuid })),
    status: { created: new Date(0).toISOString() },
  };
}

const LIB_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const LIB_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

async function mockRest(page: Page) {
  // Catch-all first (lowest priority): empty lists for anything unmatched.
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
      body: JSON.stringify({ data: [libraryResponse(LIB_A, "Alpha"), libraryResponse(LIB_B, "Beta")] }),
    })
  );

  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [
          assetResponse("11111111-1111-1111-1111-111111111111", "clip.mp4", "video/mp4", [LIB_A]),
          assetResponse("22222222-2222-2222-2222-222222222222", "photo.jpg", "image/jpeg", [LIB_B]),
          assetResponse("33333333-3333-3333-3333-333333333333", "shared.png", "image/png", [LIB_A, LIB_B]),
          assetResponse("44444444-4444-4444-4444-444444444444", "orphan.gif", "image/gif", undefined),
        ],
      }),
    })
  );
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

test.describe("Library asset scoping – mocked", () => {
  test("grid, header stats and sidebar counts follow the selected library", async ({ page }) => {
    await mockRest(page);
    await loginAndGoToLibrary(page);

    // First library (Alpha) is auto-selected: its own asset plus the shared one.
    await expect(page.getByText("clip.mp4")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("shared.png")).toBeVisible();
    await expect(page.getByText("photo.jpg")).toBeHidden();
    await expect(page.getByText("orphan.gif")).toBeHidden();
    await expect(page.getByText("1 videos")).toBeVisible();
    await expect(page.getByText("1 images")).toBeVisible();

    // Sidebar counts: the shared asset is counted in each library.
    const rowAlpha = page.locator(".MuiListItemButton-root").filter({ has: page.getByText("Alpha", { exact: true }) });
    const rowBeta = page.locator(".MuiListItemButton-root").filter({ has: page.getByText("Beta", { exact: true }) });
    await expect(rowAlpha.getByText("2 assets")).toBeVisible();
    await expect(rowBeta.getByText("2 assets")).toBeVisible();

    // Selecting Beta swaps the asset set and the header stats.
    await rowBeta.click();
    await expect(page.getByText("photo.jpg")).toBeVisible();
    await expect(page.getByText("shared.png")).toBeVisible();
    await expect(page.getByText("clip.mp4")).toBeHidden();
    await expect(page.getByText("orphan.gif")).toBeHidden();
    await expect(page.getByText("0 videos")).toBeVisible();
    await expect(page.getByText("2 images")).toBeVisible();
  });
});
