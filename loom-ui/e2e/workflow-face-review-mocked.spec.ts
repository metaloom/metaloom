import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Workflow "faces" mode — the pane a reviewer works a whole library through,
 * deciding whether each proposed cluster really is one person and naming them.
 *
 * Two things this is here to protect, both of which made the pane useless without failing anything:
 *
 *  - **The faces are visible.** Each member rendered as `<Avatar src={f.thumbnailUrl}/>` and
 *    `DetectedFace.thumbnailUrl` is hardcoded to `""` where detections are mapped, so every card was
 *    a row of blank grey silhouettes. A crop needs an `Authorization` header and therefore cannot be
 *    a plain `src` at all — it goes through `FaceCrop`, which is what `ClustersPanel` already used.
 *    Judging the *coherence* of a cluster is the entire job of this screen, and there was nothing on
 *    it to judge.
 *  - **An unnamed proposal still has a title.** `cluster.name` is nullable by design since `V2.79`
 *    (a machine proposal has no name until a human gives it one). The mapping fell back to `""`, so
 *    the card header was blank rather than "Unnamed cluster".
 *
 * Route-matching gotcha, same as the dedup spec: list clients append query strings, so collection
 * matchers need `(\?|$)` or they fall through to the catch-all.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
/** An asset with no faces at all, first in the generic queue - the demo seed's role. */
const BARE_ASSET_UUID = "77777777-7777-7777-7777-777777777777";
const CLUSTER_UUID = "33333333-3333-3333-3333-333333333333";
const DETECTION_A = "55555555-5555-5555-5555-555555555555";
const DETECTION_B = "66666666-6666-6666-6666-666666666666";

/** A 1x1 JPEG, so the crop route returns something a browser will actually decode. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

interface Recorder {
  /** Crop URLs the pane asked this origin for. Empty means the reviewer saw no faces. */
  cropRequests: string[];
  /** Off-origin image requests. Must stay empty: a face crop is biometric data. */
  offOriginImages: string[];
  confirms: { clusterUuid: string; alias?: string }[];
  /** Every stream URL the player asked for. A seek past the buffer is a *new* one, carrying `t`. */
  streamRequests: string[];
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** Frame rate the probe reports, so a frame number has a time. */
const FPS = 24;
/** Duration the probe reports, in seconds - a 43-minute episode. */
const DURATION = 2580;
/** Frames the two detections sit on: 5s and 1000s apart, so no window holds both. */
const FRAME_A = 5 * FPS;
const FRAME_B = 1000 * FPS;

function detection(uuid: string, x: number, frameNumber: number) {
  return {
    uuid,
    assetUuid: ASSET_UUID,
    type: "face",
    confidence: 0.95,
    frameNumber,
    bboxX: x, bboxY: 0.2, bboxWidth: 0.1, bboxHeight: 0.15,
  };
}

async function installMocks(page: Page, recorder: Recorder, opts: { clusterName?: string | null; probe?: null } = {}) {
  const clusterName = opts.clusterName === undefined ? null : opts.clusterName;
  let reviewStatus = "PENDING";

  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, route => {
    if (route.request().resourceType() === "image") {
      recorder.offOriginImages.push(route.request().url());
      return route.abort();
    }
    return route.continue();
  });

  // Catch-all first (lowest priority).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  const assetRecord = (uuid: string, filename: string, mimeType: string) => ({
    uuid,
    file: { filename, mimeType, size: 4_500_000_000 },
    status: { creator: { uuid: ME_UUID } },
  });

  // The generic queue, in creation order: the cluster-free asset first. This mirrors the seeded
  // deployment, where index 0 is an image with object detections and no faces.
  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, {
      data: [
        assetRecord(BARE_ASSET_UUID, "street-crossing.jpg", "image/jpeg"),
        assetRecord(ASSET_UUID, "episode.mkv", "video/x-matroska"),
      ],
      _metainfo: { totalCount: 2 },
    })
  );

  // The face queue resolves each cluster's asset by uuid.
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => {
    const uuid = route.request().url().match(/assets\/([^/?]+)/)![1];
    return json(route, uuid === BARE_ASSET_UUID
      ? assetRecord(BARE_ASSET_UUID, "street-crossing.jpg", "image/jpeg")
      : assetRecord(ASSET_UUID, "episode.mkv", "video/x-matroska"));
  });

  // The cluster list is what the face queue is built from: only ASSET_UUID has one.
  await page.route(/\/api\/v1\/clusters(\?|$)/, route =>
    json(route, {
      data: [{
        uuid: CLUSTER_UUID, name: clusterName, type: "face", reviewStatus,
        assetUuid: ASSET_UUID, clusterIndex: 0, score: 0.93, memberCount: 2, nodeKind: "facedetect",
        status: { creator: { uuid: ME_UUID } },
      }],
      _metainfo: { totalCount: 1 },
    })
  );

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections(\?|$)/, route =>
    json(route, { data: [detection(DETECTION_A, 0.1, FRAME_A), detection(DETECTION_B, 0.5, FRAME_B)] })
  );

  await page.route(/\/api\/v1\/assets\/([^/]+)\/clusters(\?|$)/, route => {
    if (route.request().url().includes(BARE_ASSET_UUID)) {
      return json(route, { data: [], _metainfo: { totalCount: 0 } });
    }
    return json(route, {
      data: [{
        uuid: CLUSTER_UUID,
        name: clusterName,
        type: "face",
        reviewStatus,
        assetUuid: ASSET_UUID,
        clusterIndex: 0,
        score: 0.93,
        memberCount: 2,
        nodeKind: "facedetect",
        status: { creator: { uuid: ME_UUID } },
      }],
      _metainfo: { totalCount: 1 },
    });
  });

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
    const body = JSON.parse(route.request().postData() || "{}") as { alias?: string };
    recorder.confirms.push({ clusterUuid, alias: body.alias });
    reviewStatus = "CONFIRMED";
    return json(route, {
      uuid: CLUSTER_UUID, name: body.alias ?? clusterName, type: "face",
      reviewStatus, assetUuid: ASSET_UUID, memberCount: 2,
      status: { creator: { uuid: ME_UUID } },
    });
  });

  // The probe. Without it nothing can place a detection on a timeline: the detection carries a
  // frame number, and `asset_video_comp` - which would carry the frame rate - has no producer.
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-info$/, route =>
    json(route, opts.probe === null
      ? {}
      : { duration: DURATION, frameRate: FPS, width: 1920, height: 1080, videoCodec: "h264", audioCodec: "ac3", streamable: true })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-token$/, route =>
    json(route, { token: "fake-media-token", expiresIn: 600 })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+\/poster/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );
  // Not a real MP4: the element will fail to decode it, which is fine. What is under test is
  // *which URL was requested*, because a seek outside the buffer is a fresh request carrying `t`.
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream/, route => {
    recorder.streamRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "video/mp4", body: Buffer.from("") });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route => {
    recorder.cropRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG });
  });
}

function recorder(): Recorder {
  return { cropRequests: [], offOriginImages: [], confirms: [], streamRequests: [] };
}

async function openFaceMode(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Workflow" }).click();
  await page.getByTestId("workflow-mode-facedetection").click();
  await expect(page.getByTestId("workflow-cluster").first()).toBeVisible({ timeout: 10_000 });
}

test.describe("Workflow face review – mocked e2e", () => {
  test("a cluster card shows the member crops, fetched from this deployment", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);

    // One crop per member. A blank Avatar renders nothing and asks for nothing, which is exactly how
    // this shipped: the pane looked fine and the reviewer had nothing to look at.
    const card = page.getByTestId("workflow-cluster").first();
    await expect(card.getByTestId("face-crop")).toHaveCount(2, { timeout: 10_000 });

    await expect.poll(() => rec.cropRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);
    expect(rec.cropRequests.some(u => u.includes(DETECTION_A))).toBe(true);
    expect(rec.cropRequests.some(u => u.includes(DETECTION_B))).toBe(true);
    expect(rec.offOriginImages).toEqual([]);
  });

  test("an unnamed machine proposal is titled rather than blank", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusterName: null });
    await page.goto("/");
    await openFaceMode(page);

    await expect(page.getByTestId("workflow-cluster").first()).toContainText("Unnamed cluster");
  });

  test("a named cluster keeps its own name", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusterName: "Anna Meyer" });
    await page.goto("/");
    await openFaceMode(page);

    const card = page.getByTestId("workflow-cluster").first();
    await expect(card).toContainText("Anna Meyer");
    await expect(card).not.toContainText("Unnamed cluster");
  });

  test("typing a person and confirming posts the alias to the confirm route", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);

    await page.getByTestId("workflow-cluster").first().click();
    await page.getByTestId("workflow-person-input").fill("Anna Meyer");
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: /confirm/i }).first().click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0].clusterUuid).toBe(CLUSTER_UUID);
    // The name is what turns a cluster into a row in the person table; dropping it would confirm the
    // cluster into an anonymous person.
    expect(rec.confirms[0].alias).toBe("Anna Meyer");
  });
  /**
   * Face mode used to walk the same queue as the other four modes - `listAssets(...).slice(0, 20)`
   * in creation order - and reset to index 0 on every mode switch. In a seeded deployment index 0
   * is an image with object detections and no faces, so the pane opened empty every time while the
   * person autocomplete showed the seeded demo names. It read as dummy data; it was an asset with
   * nothing on it.
   */
  test("face mode opens on an asset that actually has clusters", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);

    // The cluster-free asset is first in the generic queue and must not be the landing asset.
    await expect(page.getByText("episode.mkv")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("street-crossing.jpg")).toHaveCount(0);
    await expect(page.getByTestId("workflow-cluster")).toHaveCount(1);
  });

  // ── The video, the timeline, and the link between the two ──────────────

  test("the video and a full-length timeline are on screen, one tick per detection", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);

    // There was no player here at all: the pane showed one poster frame with every bounding box in
    // the episode drawn on it, which is why the boxes "made no sense".
    await expect(page.getByTestId("workflow-face-video")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("workflow-face-timeline")).toBeVisible();

    const markers = page.getByTestId("video-timeline-marker");
    await expect(markers).toHaveCount(2);

    // Position is the assertion, not just presence: a marker placed by frame number rather than by
    // time would sit at 24x the offset and be off the end of the bar.
    const bar = (await page.getByTestId("video-timeline-bar").boundingBox())!;
    const first = (await markers.first().boundingBox())!;
    const fraction = (first.x + first.width / 2 - bar.x) / bar.width;
    expect(fraction).toBeCloseTo(FRAME_A / FPS / DURATION, 2);
  });

  test("only the faces near the playhead have boxes drawn", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);
    await expect(page.getByTestId("workflow-face-video")).toBeVisible({ timeout: 10_000 });

    // The complaint was "the bounding boxes are just overlapped and make no sense": every
    // detection in a 43-minute episode drawn on one frame at once. These two are 995 seconds
    // apart, so at the start of the file neither belongs on screen.
    await expect(page.getByTestId("workflow-face-box")).toHaveCount(0);
  });

  test("clicking a crop seeks the player to the frame it was cut from", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);
    await expect(page.getByTestId("workflow-face-video")).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => rec.streamRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    // The second crop, the one a thousand seconds in: a long jump is the case that cannot be
    // served from the buffer, which is what makes this a test of the re-request rather than of a
    // currentTime write.
    await page.getByTestId("workflow-cluster").first().getByTestId("face-crop").nth(1).click();

    // A piped fragmented MP4 has no index, so seeking is a new request at a new offset. The
    // clicked crop is at frame 24000, so second 1000 — and the request asks for 999, because a
    // click lands a quarter of a second early so the bounding box can light up as the face comes
    // round rather than on a moment already gone. See FACE_FLASH_MS.
    await expect.poll(() => rec.streamRequests.some(u => u.includes("t=999")), { timeout: 10_000 }).toBe(true);

    // And the box for that face is now drawn, which is what makes the seek legible.
    await expect(page.getByTestId("workflow-face-box")).toHaveCount(1);
    await expect(page.getByTestId("workflow-face-box")).toHaveAttribute("data-face-id", DETECTION_B);

    // The flash: armed by the click, it lands once the lead-in has elapsed and is gone again a
    // quarter of a second later. Both halves matter — a highlight that never decays is just a
    // second selection colour.
    await expect(page.getByTestId("workflow-face-box")).toHaveAttribute("data-flashing", "true", { timeout: 2_000 });
    await expect(page.getByTestId("workflow-face-box")).toHaveAttribute("data-flashing", "false", { timeout: 2_000 });
  });

  test("hovering a crop highlights its moment on the timeline", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await openFaceMode(page);
    await expect(page.getByTestId("workflow-face-timeline")).toBeVisible({ timeout: 10_000 });

    const marker = page.locator(`[data-testid="video-timeline-marker"][data-marker-id="${DETECTION_A}"]`);
    await expect(marker).toHaveAttribute("data-marker-hovered", "false");

    await page.getByTestId("workflow-cluster").first().getByTestId("face-crop").first().hover();

    // The two panes were unrelated: a strip of crops and a still frame, with nothing saying which
    // crop came from where. This is the mapping.
    await expect(marker).toHaveAttribute("data-marker-hovered", "true", { timeout: 5_000 });
  });

  test("without a probe the pane says so rather than drawing an empty timeline", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { probe: null });
    await page.goto("/");
    await openFaceMode(page);

    // A zero-length timeline would put every detection at position zero and invite the reviewer to
    // conclude the detections are wrong, rather than that nothing measured the file.
    await expect(page.getByTestId("workflow-face-timeline")).toHaveCount(0);
    await expect(page.getByTestId("workflow-face-no-duration")).toBeVisible({ timeout: 10_000 });
  });
});
