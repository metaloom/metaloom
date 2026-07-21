import { test, expect, Page } from "@playwright/test";

/**
 * Mocked test for the asset-detail "Location(s)" section.
 *
 * No running Loom backend is required: REST calls are intercepted with
 * `page.route`. The asset GET returns an embedded `locations` array (as built
 * server-side by AssetLocationModelBuilder) and the test asserts the pool /
 * path / state render and that the pool link routes to the Asset Pools view.
 */

const SHA512 = "a".repeat(128);
const ASSET_UUID = "11111111-1111-1111-1111-111111111111";
const POOL_UUID = "a84e3c0b-2f17-4d67-b159-3c6a8e9d1f42";
const LOCATION_PATH = "/pool/location-demo.mp4";

function assetWithLocation() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "location-demo.mp4", mimeType: "video/mp4", size: 2048, origin: "upload" },
    hashes: { sha512: SHA512 },
    tags: [],
    status: { created: new Date(0).toISOString() },
    locations: [
      {
        uuid: "22222222-2222-2222-2222-222222222222",
        assetUuid: ASSET_UUID,
        poolUuid: POOL_UUID,
        mimeType: "video/mp4",
        state: "PRESENT",
        license: "CC-BY-4.0",
        filesystem: { path: LOCATION_PATH, filekey: { inode: 42, stDev: 12 } },
      },
    ],
  };
}

async function mockRest(page: Page) {
  // Catch-all first (lowest priority): empty lists for anything unmatched.
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/libraries", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [{ uuid: "lib-1", name: "Main Library" }] }) })
  );

  // Asset list (exact path, no trailing segments).
  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [assetWithLocation()] }) })
  );

  // Single asset load by uuid (detail view).
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(assetWithLocation()) })
  );
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Assets" }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

test.describe("Asset detail – storage location", () => {
  test("renders pool / path / state for an asset that has a location", async ({ page }) => {
    await mockRest(page);
    await login(page);

    // Open the asset detail by clicking the tile.
    await page.getByText("location-demo.mp4").first().click();

    const locations = page.getByTestId("asset-locations");
    await expect(locations).toBeVisible({ timeout: 10_000 });

    // Exactly one location card, carrying path + pool + state.
    await expect(page.getByTestId("asset-location")).toHaveCount(1);
    await expect(locations.getByText(LOCATION_PATH)).toBeVisible();
    await expect(locations.getByTestId("asset-location-state")).toHaveText("PRESENT");

    const poolLink = page.getByTestId("asset-location-pool-link");
    await expect(poolLink).toContainText(POOL_UUID);

    // The pool link routes to the Asset Pools view.
    await poolLink.click();
    await expect(page).toHaveURL(/\/asset-pools$/);
  });

  test("shows an empty hint for an asset without a location", async ({ page }) => {
    await page.route("**/api/v1/**", route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
    );
    await page.route("**/api/v1/login", route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
    );
    await page.route("**/api/v1/libraries", route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [{ uuid: "lib-1", name: "Main Library" }] }) })
    );
    const noLoc = { ...assetWithLocation(), locations: [] };
    await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [noLoc] }) })
    );
    await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(noLoc) })
    );

    await login(page);
    await page.getByText("location-demo.mp4").first().click();

    const locations = page.getByTestId("asset-locations");
    await expect(locations).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("asset-location")).toHaveCount(0);
  });
});
