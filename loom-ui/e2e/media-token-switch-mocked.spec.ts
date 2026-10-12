import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for `useMediaToken` across an asset switch.
 *
 * The workflow queue hands the same preview instance the next asset without a `key`. The hook kept
 * the previous asset's token in state until the new one was minted, so the very first poster URL
 * for the next asset went out as `/assets/B/poster?mt=<A's token>` - a credential scoped to a
 * different asset, i.e. a 401 and a broken tile. Each asset here gets its own token, and B's mint
 * is held back so the stale window is wide open if the hook ever reopens it.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const ASSET_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

/** A 1x1 JPEG, so the poster route returns something a browser will actually decode. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function videoAsset(uuid: string, filename: string) {
  return {
    uuid,
    file: { filename, mimeType: "video/mp4", size: 1_000_000 },
    status: { creator: { uuid: ME_UUID } },
  };
}

function tokenFor(assetUuid: string): string {
  return `token-for-${assetUuid}`;
}

async function installMocks(page: Page, posterRequests: string[]) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, {
      data: [videoAsset(ASSET_A, "first-take.mp4"), videoAsset(ASSET_B, "second-take.mp4")],
      _metainfo: { totalCount: 2 },
    })
  );
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("?")[0]);
    return json(route, videoAsset(uuid, uuid === ASSET_A ? "first-take.mp4" : "second-take.mp4"));
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/media-token$/, async route => {
    const uuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("/media-token")[0]);
    if (uuid === ASSET_B) {
      // Held back: the stale-token render, if there is one, has all this time to send its request.
      await new Promise(resolve => setTimeout(resolve, 800));
    }
    return json(route, { token: tokenFor(uuid), expiresIn: 600 });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/poster/, route => {
    posterRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG });
  });
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

function posterRequestsFor(requests: string[], assetUuid: string): URL[] {
  return requests.map(u => new URL(u)).filter(u => u.pathname.includes(`/assets/${assetUuid}/poster`));
}

test.describe("Media token across an asset switch – mocked e2e", () => {
  test("the first poster request of the next asset carries that asset's token", async ({ page }) => {
    const posterRequests: string[] = [];
    await installMocks(page, posterRequests);
    await page.goto("/");
    await login(page);

    await page.getByRole("button", { name: "Workflow", exact: true }).click();
    await expect(page.getByText("first-take.mp4").first()).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => posterRequestsFor(posterRequests, ASSET_A).length, { timeout: 10_000 }).toBeGreaterThan(0);
    expect(posterRequestsFor(posterRequests, ASSET_A)[0].searchParams.get("mt")).toBe(tokenFor(ASSET_A));

    await page.keyboard.press("ArrowRight");
    await expect(page.getByText("second-take.mp4").first()).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => posterRequestsFor(posterRequests, ASSET_B).length, { timeout: 10_000 }).toBeGreaterThan(0);

    const forB = posterRequestsFor(posterRequests, ASSET_B);
    // The first one is the one that used to be wrong; every one must be right.
    expect(forB[0].searchParams.get("mt")).toBe(tokenFor(ASSET_B));
    expect(forB.map(u => u.searchParams.get("mt"))).toEqual(forB.map(() => tokenFor(ASSET_B)));
  });
});
