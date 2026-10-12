import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the asset detail Faces tab grouping faces by cluster.
 *
 * The panel grouped by `cluster.faceIds`, which was always empty because nobody fetched a
 * cluster's members, so the summary read "0 clusters" whatever `/assets/:uuid/clusters` returned.
 * The unclustered list was keyed on `face.clusterId` instead, which the backend never writes. The
 * screen now fetches `/clusters/:uuid/members` and groups by real membership. Pinned here:
 *
 *  - faces are grouped under the cluster whose members name them, and the count is right;
 *  - a cluster whose members cannot be loaded does not swallow its faces - they show as
 *    unclustered, so every detected face is on screen exactly once;
 *  - a speaker cluster on the same asset is not asked for members and never becomes a face group.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const FACE_A = "aaaaaaaa-0000-0000-0000-00000000000a";
const FACE_B = "aaaaaaaa-0000-0000-0000-00000000000b";
const FACE_C = "aaaaaaaa-0000-0000-0000-00000000000c";
const FACE_D = "aaaaaaaa-0000-0000-0000-00000000000d";
const CLUSTER_ADA = "cccccccc-0000-0000-0000-000000000001";
const CLUSTER_BROKEN = "cccccccc-0000-0000-0000-000000000002";
const CLUSTER_SPEAKER = "cccccccc-0000-0000-0000-000000000003";
const PERSON_ADA = "dddddddd-0000-0000-0000-000000000001";

const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "group-photo.jpg", mimeType: "image/jpeg", size: 120_000 },
    tags: [],
    annotations: [],
    status: { creator: { uuid: ME_UUID }, created: "2026-09-19T22:50:42Z" },
  };
}

function face(uuid: string, x: number) {
  return { uuid, assetUuid: ASSET_UUID, type: "face", confidence: 0.9, bboxX: x, bboxY: 0.1, bboxWidth: 0.1, bboxHeight: 0.1 };
}

function cluster(uuid: string, type: string, extra: Record<string, unknown> = {}) {
  return { uuid, type, name: null, reviewStatus: "PENDING", assetUuid: ASSET_UUID, memberCount: 2, ...extra };
}

async function installMocks(page: Page, memberRequests: string[]) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/assets(\?|$)/, route => json(route, { data: [asset()], _metainfo: { totalCount: 1 } }));
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => json(route, asset()));
  await page.route(/\/api\/v1\/assets\/[^/]+\/binary/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections(\?|$)/, route =>
    json(route, { data: [face(FACE_A, 0.1), face(FACE_B, 0.3), face(FACE_C, 0.5), face(FACE_D, 0.7)] }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));

  await page.route(/\/api\/v1\/assets\/[^/]+\/clusters(\?|$)/, route =>
    json(route, {
      data: [
        cluster(CLUSTER_ADA, "face", { name: "Ada group", personUuid: PERSON_ADA, reviewStatus: "CONFIRMED" }),
        cluster(CLUSTER_BROKEN, "face"),
        cluster(CLUSTER_SPEAKER, "speaker"),
      ],
      _metainfo: { totalCount: 3 },
    }));

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => {
    const uuid = route.request().url().split("/clusters/")[1].split("/members")[0];
    memberRequests.push(uuid);
    if (uuid === CLUSTER_ADA) {
      return json(route, {
        total: 2,
        members: [
          { embeddingUuid: "e1", detectionUuid: FACE_A, assetUuid: ASSET_UUID },
          { embeddingUuid: "e2", detectionUuid: FACE_B, assetUuid: ASSET_UUID },
        ],
      });
    }
    // FACE_C's cluster: its members cannot be loaded.
    return json(route, { message: "boom" }, 500);
  });

  await page.route(/\/api\/v1\/persons(\?|$)/, route =>
    json(route, { data: [{ uuid: PERSON_ADA, alias: "Ada Lovelace", firstname: "Ada", lastname: "Lovelace" }], _metainfo: { totalCount: 1 } }));
}

async function openFaces(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Assets", exact: true }).first().click();
  const link = page.getByText("group-photo.jpg").first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await page.getByRole("tab", { name: /faces/i }).click();
  await expect(page.getByTestId("asset-face-tile").first()).toBeVisible({ timeout: 10_000 });
}

test.describe("Asset detail face clusters – mocked e2e", () => {
  test("faces are grouped by the cluster members the server returns, and none are lost", async ({ page }) => {
    const memberRequests: string[] = [];
    await installMocks(page, memberRequests);
    await openFaces(page);

    const groups = page.getByTestId("asset-face-cluster");
    await expect(groups).toHaveCount(1);
    const ada = page.locator(`[data-testid="asset-face-cluster"][data-cluster-id="${CLUSTER_ADA}"]`);
    await expect(ada).toContainText("Ada Lovelace");
    await expect(ada.getByTestId("asset-face-tile")).toHaveCount(2);
    await expect(ada.locator(`[data-face-id="${FACE_A}"]`)).toHaveCount(1);
    await expect(ada.locator(`[data-face-id="${FACE_B}"]`)).toHaveCount(1);

    // The broken cluster's face and the face nobody claims are still on screen, as unclustered.
    const rest = page.getByTestId("asset-face-unclustered");
    await expect(rest.getByTestId("asset-face-tile")).toHaveCount(2);
    await expect(rest).toContainText("Unclustered (2)");

    // Every detected face is shown exactly once.
    await expect(page.getByTestId("asset-face-tile")).toHaveCount(4);
    for (const id of [FACE_A, FACE_B, FACE_C, FACE_D]) {
      await expect(page.locator(`[data-testid="asset-face-tile"][data-face-id="${id}"]`)).toHaveCount(1);
    }

    // The summary counts real groups, not "0 clusters".
    await expect(page.getByText("4 faces detected", { exact: true })).toBeVisible();
    await expect(page.getByText("1 clusters", { exact: true })).toBeVisible();
    await expect(page.getByText("1 identified", { exact: true })).toBeVisible();

    // A speaker cluster's members are speech windows, not faces: never asked for here.
    expect(memberRequests).not.toContain(CLUSTER_SPEAKER);
    expect(memberRequests).toEqual(expect.arrayContaining([CLUSTER_ADA, CLUSTER_BROKEN]));
  });
});
