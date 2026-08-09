import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Workflow "object detection" review mode — no running Loom backend required.
 *
 * The point of these tests is that a verdict leaves the browser. Before the review columns existed,
 * `handleConfirmObject` wrote React state and nothing else, so a decision was lost on reload. The
 * store below keeps `reviewStatus` per detection and serves it back from the list route, which is
 * what makes the reload assertion meaningful rather than a re-render check.
 *
 * Routes exercised:
 *   GET  /api/v1/assets/:uuid/detections
 *   POST /api/v1/assets/:uuid/detections/review-bulk
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const DETECTION_DOG = "dddddddd-dddd-dddd-dddd-dddddddddddd";
const DETECTION_CAT = "cccccccc-cccc-cccc-cccc-cccccccccccc";

interface StoredDetection {
  uuid: string;
  type: string;
  label: string;
  frameNumber: number;
  bboxX: number;
  bboxY: number;
  bboxWidth: number;
  bboxHeight: number;
  confidence: number;
  assetUuid: string;
  reviewStatus: "PENDING" | "CONFIRMED" | "REJECTED";
  correctedLabel?: string;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function detection(uuid: string, label: string, x: number): StoredDetection {
  return {
    uuid,
    type: "objectdetection",
    label,
    frameNumber: 0,
    bboxX: x,
    bboxY: 0.2,
    bboxWidth: 0.2,
    bboxHeight: 0.2,
    confidence: 0.9,
    assetUuid: ASSET_A,
    reviewStatus: "PENDING",
  };
}

async function installMocks(page: Page, opts: { failReviewWrites?: boolean } = {}) {
  // Survives the reload in the persistence test: a fresh page gets the same store, standing in for
  // the server having kept the verdict.
  const detections: StoredDetection[] = [
    detection(DETECTION_DOG, "dog", 0.1),
    detection(DETECTION_CAT, "cat", 0.5),
  ];

  // Catch-all first (lowest priority) — empty collections for the many list endpoints the workflow
  // view fans out to.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, {
      data: [{ uuid: ASSET_A, file: { filename: "workflow-a.jpg", mimeType: "image/jpeg", size: 1024 }, status: { creator: { uuid: ME_UUID } } }],
      _metainfo: { totalCount: 1 },
    })
  );

  // Registered before the plain detections route so the more specific path wins.
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/review-bulk$/, route => {
    if (opts.failReviewWrites) {
      return json(route, { message: "nope" }, 500);
    }
    const body = JSON.parse(route.request().postData() || "{}");
    const reviews: { uuid: string; status: StoredDetection["reviewStatus"]; correctedLabel?: string }[] = body.reviews ?? [];
    let created = 0;
    let failed = 0;
    for (const review of reviews) {
      const stored = detections.find(d => d.uuid === review.uuid);
      if (!stored) {
        failed++;
        continue;
      }
      stored.reviewStatus = review.status;
      if (review.correctedLabel) stored.correctedLabel = review.correctedLabel;
      created++;
    }
    return json(route, { detections: [], total: reviews.length, created, failed });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections$/, route => json(route, { data: detections }));

  // Single asset by id (registered after the detection routes so those win).
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("?")[0]);
    return json(route, { uuid, file: { filename: "workflow-a.jpg", mimeType: "image/jpeg", size: 1024 }, status: { creator: { uuid: ME_UUID } } });
  });
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openObjectReview(page: Page) {
  await page.getByRole("button", { name: "Workflow" }).click();
  await page.getByTestId("workflow-mode-objectdetection").click();
  await expect(page.getByText(/Detected Objects \(2\)/)).toBeVisible({ timeout: 10_000 });
}

test.describe("Workflow object review – mocked e2e", () => {
  /**
   * The label is read from the `label` column, not from `meta`.
   *
   * It used to be read as `meta.label`, which the detecting node never writes — so every box in the
   * review list rendered as the literal string "objectdetection" and a reviewer had nothing to
   * decide about.
   */
  test("the detected class is shown, not the detection type", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openObjectReview(page);

    await expect(page.getByText("dog", { exact: true })).toBeVisible();
    await expect(page.getByText("cat", { exact: true })).toBeVisible();
    await expect(page.getByText("objectdetection", { exact: true })).toHaveCount(0);
  });

  test("confirm a detection, reload, and the verdict persists", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openObjectReview(page);

    const review = page.waitForRequest(
      req => req.method() === "POST" && /\/assets\/[^/]+\/detections\/review-bulk$/.test(req.url())
    );
    await page.keyboard.press("y");

    // Through the bulk route even for one decision: keyboard review outruns per-item calls, so the
    // client coalesces whatever arrived in the window into a single request.
    expect(JSON.parse((await review).postData() || "{}")).toMatchObject({
      reviews: [{ uuid: DETECTION_DOG, status: "CONFIRMED" }],
    });
    await expect(page.getByText("Confirmed", { exact: true })).toBeVisible({ timeout: 10_000 });

    // Auth is in-memory, so a reload logs us out. Logging back in and still finding the verdict
    // proves it was read back from the store rather than kept in React state.
    await page.reload();
    await login(page);
    await openObjectReview(page);

    await expect(page.getByText("Confirmed", { exact: true })).toBeVisible({ timeout: 10_000 });
  });

  /**
   * A run of keystrokes becomes one request.
   *
   * This is the reason the bulk route exists: at a keystroke per decision, one request each either
   * lags behind the reviewer or drops decisions.
   */
  test("a run of decisions is sent as a single batch", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openObjectReview(page);

    const requests: string[] = [];
    page.on("request", req => {
      if (req.method() === "POST" && /detections\/review-bulk$/.test(req.url())) {
        requests.push(req.postData() || "");
      }
    });

    const flushed = page.waitForRequest(
      req => req.method() === "POST" && /detections\/review-bulk$/.test(req.url())
    );

    await page.keyboard.press("y");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("n");

    // Wait for the batch to actually go out: the chips appear optimistically, well before the
    // debounce window closes, so asserting on them would count requests that had not been sent yet.
    await flushed;
    // ...and then a little longer than the window, so a second request would have been seen.
    await page.waitForTimeout(1_000);

    expect(requests).toHaveLength(1);
    expect(JSON.parse(requests[0])).toMatchObject({
      reviews: [
        { uuid: DETECTION_DOG, status: "CONFIRMED" },
        { uuid: DETECTION_CAT, status: "REJECTED" },
      ],
    });
  });

  test("a failed write is rolled back rather than left on screen", async ({ page }) => {
    await installMocks(page, { failReviewWrites: true });
    await page.goto("/");
    await login(page);
    await openObjectReview(page);

    await page.keyboard.press("y");

    // The chip must go away again. Leaving it up would tell the reviewer the decision was recorded
    // when the server never heard about it.
    await expect(page.getByText(/could not save your decision/i)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Confirmed", { exact: true })).toHaveCount(0);
  });
});
