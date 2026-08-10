import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the person detail view at `/persons/:id` — no running Loom backend required.
 *
 * A person owns their pictures. They are uploaded to the person, or copied there from a face crop,
 * and they reference no asset at all — which is what lets somebody keep a picture of their face when
 * the video they were found in is deleted. This spec covers the screen where that happens: the
 * gallery, the upload, the crop picker, and choosing which picture is the avatar.
 *
 * It replaces nothing: before this the person "avatar" was `primaryImageUuid`, a pointer at an
 * *asset*, so for a person discovered in a video it resolved to the whole video file. There was no
 * gallery at all — `person_image` had existed with no writer since V2.26.
 *
 * Routes exercised:
 *   GET      /api/v1/persons/:uuid
 *   GET      /api/v1/persons/:uuid/clusters
 *   GET      /api/v1/persons/:uuid/images
 *   POST     /api/v1/persons/:uuid/images                   (multipart upload)
 *   POST     /api/v1/persons/:uuid/images/from-detection
 *   DELETE   /api/v1/persons/:uuid/images/:imageUuid
 *   GET      /api/v1/persons/:uuid/images/:imageUuid/data
 *   POST     /api/v1/persons/:uuid/avatar
 *   GET      /api/v1/clusters/:uuid/members
 *   GET      /api/v1/assets/:uuid/detections/:detectionUuid/crop
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_UUID = "33333333-3333-3333-3333-333333333333";
const PERSON_UUID = "44444444-4444-4444-4444-444444444444";
const DETECTION_UUID = "66666666-6666-6666-6666-666666666666";

/** A 1x1 JPEG, so both a face crop and a person image resolve to something the browser decodes. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

interface StoredImage {
  uuid: string;
  filename: string;
  mimeType: string;
  size: number;
  url: string;
  avatar: boolean;
}

interface Recorder {
  uploads: { contentType: string; body: string }[];
  imports: Record<string, unknown>[];
  avatars: Record<string, unknown>[];
  deletes: string[];
}

function recorder(): Recorder {
  return { uploads: [], imports: [], avatars: [], deletes: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function imageUrl(uuid: string) {
  return `/api/v1/persons/${PERSON_UUID}/images/${uuid}/data`;
}

interface MockOptions {
  images?: StoredImage[];
  /** A cluster confirmed to this person, so the crop picker has something to offer. */
  withConfirmedCluster?: boolean;
}

/** Routes are matched most-recently-registered first, so the catch-all goes in first. */
async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  const images: StoredImage[] = [...(opts.images ?? [])];
  let avatarUuid: string | null = images.find(i => i.avatar)?.uuid ?? null;
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  const person = () => ({
    uuid: PERSON_UUID,
    alias: "anna",
    firstname: "Anna",
    lastname: "Meyer",
    avatarUrl: avatarUuid ? imageUrl(avatarUuid) : undefined,
    status: { created: "2026-01-02T03:04:05Z" },
  });

  await page.route(/\/api\/v1\/persons\/[^/]+$/, route => json(route, person()));

  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route =>
    json(route, {
      data: opts.withConfirmedCluster
        ? [{ uuid: CLUSTER_UUID, name: "Anna", type: "face", reviewStatus: "CONFIRMED", personUuid: PERSON_UUID, assetUuid: ASSET_UUID, memberCount: 1 }]
        : [],
    })
  );

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route =>
    json(route, {
      members: [{ clusterUuid: CLUSTER_UUID, embeddingUuid: "e1", detectionUuid: DETECTION_UUID, assetUuid: ASSET_UUID, confidence: 0.94, origin: "AUTO" }],
      total: 1,
    })
  );

  await page.route(/\/api\/v1\/persons\/[^/]+\/avatar$/, route => {
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.avatars.push(body);
    avatarUuid = body.imageUuid ? (body.imageUuid as string) : null;
    images.forEach(i => (i.avatar = i.uuid === avatarUuid));
    return json(route, person());
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/[^/]+\/data$/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );

  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/images/")[1].split("?")[0]);
    rec.deletes.push(uuid);
    const idx = images.findIndex(i => i.uuid === uuid);
    if (idx >= 0) images.splice(idx, 1);
    if (avatarUuid === uuid) {
      // The FK is ON DELETE SET NULL: losing the picture leaves the person without an avatar rather
      // than deleting them.
      avatarUuid = null;
    }
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/images$/, route => {
    if (route.request().method() === "POST") {
      rec.uploads.push({
        contentType: route.request().headers()["content-type"] ?? "",
        body: route.request().postData() ?? "",
      });
      const created: StoredImage = {
        uuid: `uploaded-${++seq}`,
        filename: "portrait.png",
        mimeType: "image/png",
        size: 1024,
        url: imageUrl(`uploaded-${seq}`),
        avatar: false,
      };
      images.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: images, _metainfo: { totalCount: images.length } });
  });

  // Registered last so it wins over the by-uuid image route above, which would otherwise match
  // ".../images/from-detection" and answer the import as though it were a delete.
  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/from-detection$/, route => {
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.imports.push(body);
    const created: StoredImage = {
      uuid: `imported-${++seq}`,
      filename: `face-${body.detectionUuid}.jpg`,
      mimeType: "image/jpeg",
      size: 812,
      url: imageUrl(`imported-${seq}`),
      avatar: false,
    };
    images.push(created);
    return json(route, created, 201);
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/**
 * Unlike the two face panels, a person has a route of its own — the whole point of the detail view.
 *
 * Auth is in-memory, so the deep link is opened *before* signing in: a `goto` after login would
 * reload the app and log it straight back out. Landing on the route and still arriving here after
 * authenticating is itself part of the contract (see `routing-mocked.spec.ts`), as is the `/ui`
 * base path the app is mounted under.
 */
async function openPerson(page: Page) {
  await page.goto(`/ui/persons/${PERSON_UUID}`);
  await login(page);
  await expect(page.getByTestId("person-detail")).toBeVisible({ timeout: 10_000 });
}

function storedImage(uuid: string, filename: string, avatar = false): StoredImage {
  return { uuid, filename, mimeType: "image/jpeg", size: 512, url: imageUrl(uuid), avatar };
}

test.describe("Person detail – mocked e2e", () => {
  test("a person with no pictures says so rather than rendering an empty grid", async ({ page }) => {
    await installMocks(page, recorder(), { images: [] });
    await openPerson(page);

    await expect(page.getByTestId("person-images-empty")).toBeVisible();
    await expect(page.getByTestId("person-image-card")).toHaveCount(0);
    // Nothing confirmed to them yet, so there is no crop to offer either.
    await expect(page.getByTestId("person-crop-picker")).toHaveCount(0);
  });

  test("the gallery renders every picture and marks which one is the avatar", async ({ page }) => {
    await installMocks(page, recorder(), {
      images: [storedImage("img-1", "first.jpg", true), storedImage("img-2", "second.jpg")],
    });
    await openPerson(page);

    await expect(page.getByTestId("person-image-card")).toHaveCount(2);
    await expect(page.getByTestId("person-image").first()).toHaveAttribute("src", imageUrl("img-1"));
    await expect(page.getByTestId("person-image-is-avatar")).toHaveCount(1);
    await expect(page.getByTestId("person-detail-avatar").locator("img")).toHaveAttribute("src", imageUrl("img-1"));
  });

  test("uploading a picture posts it as multipart and shows it in the gallery", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { images: [] });
    await openPerson(page);

    await page.getByTestId("person-image-input").setInputFiles({
      name: "portrait.png",
      mimeType: "image/png",
      buffer: Buffer.from("portrait-bytes"),
    });

    await expect.poll(() => rec.uploads.length, { timeout: 10_000 }).toBe(1);
    expect(rec.uploads[0].contentType).toContain("multipart/form-data");
    expect(rec.uploads[0].body).toContain("portrait.png");

    await expect(page.getByTestId("person-image-card")).toHaveCount(1);
    await expect(page.getByTestId("person-images-empty")).toHaveCount(0);
  });

  test("choosing an avatar round-trips and clearing it is possible by deleting the picture", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { images: [storedImage("img-1", "first.jpg")] });
    await openPerson(page);

    // No avatar yet, so the card offers to make this one it.
    await expect(page.getByTestId("person-image-make-avatar")).toHaveCount(1);
    await page.getByTestId("person-image-make-avatar").click();

    await expect.poll(() => rec.avatars.length, { timeout: 10_000 }).toBe(1);
    expect(rec.avatars[0]).toMatchObject({ imageUuid: "img-1" });
    await expect(page.getByTestId("person-image-is-avatar")).toHaveCount(1);
    await expect(page.getByTestId("person-detail-avatar").locator("img")).toHaveAttribute("src", imageUrl("img-1"));

    // Deleting the picture a person is shown by leaves the person standing with no avatar — the FK is
    // SET NULL, not CASCADE.
    await page.getByTestId("person-image-delete").click();
    await expect.poll(() => rec.deletes, { timeout: 10_000 }).toEqual(["img-1"]);
    await expect(page.getByTestId("person-detail")).toBeVisible();
    await expect(page.getByTestId("person-images-empty")).toBeVisible();
    await expect(page.getByTestId("person-detail-avatar").locator("img")).toHaveCount(0);
  });

  test("a face from a confirmed group can be copied into the person's own pictures", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { images: [], withConfirmedCluster: true });
    await openPerson(page);

    // This is the one-click path from "discovered in a video" to a picture of their face. It happens
    // here rather than at confirmation time: attributing a face to somebody and deciding what they
    // look like are separate decisions.
    const candidate = page.getByTestId("person-crop-candidate");
    await expect(candidate).toHaveCount(1, { timeout: 10_000 });
    await candidate.click();

    await expect.poll(() => rec.imports.length, { timeout: 10_000 }).toBe(1);
    expect(rec.imports[0]).toMatchObject({ detectionUuid: DETECTION_UUID });
    await expect(page.getByTestId("person-image-card")).toHaveCount(1);
  });
});
