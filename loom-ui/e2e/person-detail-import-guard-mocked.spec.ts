import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the face-crop import on the person detail view.
 *
 * The candidate is a plain element with `role="button"`, not a disableable button, so the busy
 * state it drew (dimmed, default cursor) did not stop a second click: a double-click imported the
 * same face twice. `person-detail-mocked.spec.ts` covers the single import; this pins the guard.
 *
 * Routes exercised:
 *   GET  /api/v1/persons/:uuid, /api/v1/persons/:uuid/images, /api/v1/persons/:uuid/clusters
 *   GET  /api/v1/clusters/:uuid/members
 *   POST /api/v1/persons/:uuid/images/from-detection
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_UUID = "33333333-3333-3333-3333-333333333333";
const PERSON_UUID = "44444444-4444-4444-4444-444444444444";
const DETECTION_UUID = "66666666-6666-6666-6666-666666666666";

const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page) {
  const imports: unknown[] = [];
  const images: unknown[] = [];

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/persons\/[^/]+$/, route =>
    json(route, { uuid: PERSON_UUID, alias: "anna", firstname: "Anna", lastname: "Meyer" }));
  await page.route(/\/api\/v1\/persons\/[^/]+\/images$/, route => json(route, { data: images }));
  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => json(route, {
    data: [{ uuid: CLUSTER_UUID, name: "Anna", type: "face", reviewStatus: "CONFIRMED", personUuid: PERSON_UUID, assetUuid: ASSET_UUID, memberCount: 1 }],
  }));
  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => json(route, {
    members: [{ clusterUuid: CLUSTER_UUID, embeddingUuid: "e1", detectionUuid: DETECTION_UUID, assetUuid: ASSET_UUID, confidence: 0.9, origin: "AUTO" }],
    total: 1,
  }));
  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/from-detection$/, async route => {
    imports.push(route.request().postDataJSON());
    // Slow enough that a second click lands while the first import is still busy.
    await new Promise(r => setTimeout(r, 800));
    const created = {
      uuid: `imported-${imports.length}`, filename: "face.jpg", mimeType: "image/jpeg", size: 1,
      url: `/api/v1/persons/${PERSON_UUID}/images/imported-${imports.length}/data`, avatar: false,
    };
    images.push(created);
    return json(route, created, 201).catch(() => {});
  });
  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/[^/]+\/data$/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));
  return imports;
}

async function openPerson(page: Page) {
  await page.goto(`/ui/persons/${PERSON_UUID}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByTestId("person-detail")).toBeVisible({ timeout: 10_000 });
}

test.describe("Person detail crop import guard – mocked e2e", () => {
  test("a double-click on a face imports it once", async ({ page }) => {
    const imports = await installMocks(page);
    await openPerson(page);

    const candidate = page.getByTestId("person-crop-candidate");
    await expect(candidate).toHaveCount(1, { timeout: 10_000 });
    await candidate.dblclick();

    await expect(candidate).toHaveAttribute("aria-disabled", "true");
    await expect(page.getByTestId("person-image-card")).toHaveCount(1, { timeout: 10_000 });
    await expect(candidate).toHaveAttribute("aria-disabled", "false");
    expect(imports).toEqual([{ detectionUuid: DETECTION_UUID }]);
  });

  test("a second import is accepted once the first has finished", async ({ page }) => {
    const imports = await installMocks(page);
    await openPerson(page);

    const candidate = page.getByTestId("person-crop-candidate");
    await candidate.click();
    await expect(page.getByTestId("person-image-card")).toHaveCount(1, { timeout: 10_000 });
    // Keyboard, too: Space on a role=button.
    await candidate.focus();
    await candidate.press(" ");
    await expect(page.getByTestId("person-image-card")).toHaveCount(2, { timeout: 10_000 });
    expect(imports).toHaveLength(2);
  });
});
