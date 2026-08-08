import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the face review loop on Detection → Faces — no running Loom backend required.
 *
 * What this is here to protect, in order of how quietly each one broke before:
 *
 *  - **Membership is real.** Cards used to hardcode `faceIds: []`, so every group read "0 faces"
 *    however many were in it. The card count comes from `memberCount` on the list route and the
 *    thumbnails come from `GET /clusters/:uuid/members`.
 *  - **Confirming persists.** Assignment used to mutate local state and nothing else, so it vanished
 *    on reload. It must POST to `/clusters/:uuid/confirm`.
 *  - **Crops come from this deployment.** They were fetched from `https://i.pravatar.cc`, which both
 *    leaked detection uuids to a third party and showed a stranger. No *image* may leave the origin.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_UUID = "33333333-3333-3333-3333-333333333333";
const PERSON_UUID = "44444444-4444-4444-4444-444444444444";
const DETECTION_A = "55555555-5555-5555-5555-555555555555";
const DETECTION_B = "66666666-6666-6666-6666-666666666666";

/** A 1x1 JPEG, so the crop route returns something a browser will actually decode. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface Confirmed {
  personUuid?: string;
  alias?: string;
}

/** Records what the UI actually sent to /confirm, so the test can assert it rather than infer it. */
interface Recorder {
  confirms: { clusterUuid: string; body: Confirmed }[];
  cropRequests: string[];
  /**
   * Off-origin *image* requests. Must stay empty.
   *
   * Scoped to images rather than to every request: the app loads a webfont stylesheet from Google,
   * which is a separate concern with its own trade-off and not what this test is about. What must
   * never happen is a picture of somebody's face being fetched from, or their detection uuid handed
   * to, an outside host.
   */
  offOriginImages: string[];
}

async function installMocks(page: Page, recorder: Recorder, opts: { memberCount?: number } = {}) {
  const memberCount = opts.memberCount ?? 2;
  let reviewStatus = "PENDING";
  let personUuid: string | undefined;

  // An off-origin image is a bug, not a fixture: recorded and aborted so a reintroduced third-party
  // avatar host fails this test instead of silently working. Non-image requests are let through —
  // the app's webfont is a separate trade-off and not what this test speaks to.
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, route => {
    if (route.request().resourceType() === "image") {
      recorder.offOriginImages.push(route.request().url());
      return route.abort();
    }
    return route.continue();
  });

  // Catch-all first (lowest priority) — empty collections for the endpoints the view fans out to.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/persons(\?|$)/, route =>
    json(route, {
      data: [{ uuid: PERSON_UUID, alias: "Anna Meyer", firstname: "Anna", lastname: "Meyer" }],
      _metainfo: { totalCount: 1 },
    })
  );
  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => json(route, { data: [] }));

  // The review queue.
  await page.route(/\/api\/v1\/clusters(\?|$)/, route =>
    json(route, {
      data: [
        {
          uuid: CLUSTER_UUID,
          name: "",
          type: "face",
          reviewStatus,
          personUuid,
          assetUuid: ASSET_UUID,
          clusterIndex: 0,
          score: 0.93,
          memberCount,
          nodeKind: "facedetect",
          status: { creator: { uuid: ME_UUID } },
        },
      ],
      _metainfo: { totalCount: 1 },
    })
  );

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route =>
    json(route, {
      total: 2,
      members: [
        { embeddingUuid: "e1", detectionUuid: DETECTION_A, assetUuid: ASSET_UUID, confidence: 0.97, origin: "AUTO" },
        { embeddingUuid: "e2", detectionUuid: DETECTION_B, assetUuid: ASSET_UUID, confidence: 0.91, origin: "AUTO" },
      ],
    })
  );

  await page.route(/\/api\/v1\/clusters\/[^/]+\/confirm$/, route => {
    const clusterUuid = route.request().url().split("/clusters/")[1].split("/confirm")[0];
    const body = JSON.parse(route.request().postData() || "{}") as Confirmed;
    recorder.confirms.push({ clusterUuid, body });
    reviewStatus = "CONFIRMED";
    personUuid = body.personUuid ?? PERSON_UUID;
    return json(route, {
      uuid: CLUSTER_UUID,
      name: "",
      type: "face",
      reviewStatus,
      personUuid,
      assetUuid: ASSET_UUID,
      memberCount,
      status: { creator: { uuid: ME_UUID } },
    });
  });

  // The face crops, served from this origin.
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route => {
    recorder.cropRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG });
  });
}

async function openFaces(page: Page, recorder: Recorder, opts: { memberCount?: number } = {}) {
  await installMocks(page, recorder, opts);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByText(/faces/i).first()).toBeVisible({ timeout: 10_000 });
}

function recorder(): Recorder {
  return { confirms: [], cropRequests: [], offOriginImages: [] };
}

test.describe("Face cluster review – mocked e2e", () => {
  test("a pending group shows its real member count", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec, { memberCount: 2 });

    // "0 faces" is what the hardcoded-empty membership produced for every group, whatever was in it.
    await expect(page.getByText(/2 faces/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test("member crops are fetched from this deployment, never a third party", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);

    await expect.poll(() => rec.cropRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    expect(rec.cropRequests.some(u => u.includes(DETECTION_A))).toBe(true);
    // The load-bearing assertion: no face picture was fetched from anywhere but here. A reintroduced
    // i.pravatar.cc (or any other avatar host) fails on this line.
    expect(rec.offOriginImages).toEqual([]);
  });

  test("confirming a group posts to the confirm route", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);

    // The link icon on an unassigned card opens the assign dialog.
    await page.locator("svg[data-testid='LinkOutlinedIcon']").first().click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.getByRole("combobox").click();
    await page.getByRole("option", { name: /anna meyer/i }).click();
    await dialog.getByRole("button", { name: /assign|confirm|save/i }).last().click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0].clusterUuid).toBe(CLUSTER_UUID);
    expect(rec.confirms[0].body.personUuid).toBe(PERSON_UUID);
  });
});
