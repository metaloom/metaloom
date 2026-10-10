import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the failure paths of Detection → Objects — no running Loom backend.
 *
 * `detection-review-mocked.spec.ts` covers an accepted confirm and reject. Here:
 *  - a rejected confirm or reject says so and leaves the row undecided, offering the buttons again;
 *  - the screen shows a loading state while the list is in flight, not the empty state;
 *  - a failed load renders an inline failure with a retry, not "No object detections found" —
 *    the screen used to swallow the error and claim the instance had no detections at all.
 *
 * The confirm/reject semantics themselves (meta write vs delete) are deliberately not pinned here.
 *
 * Routes exercised:
 *   GET    /api/v1/assets
 *   GET    /api/v1/assets/:uuid/detections
 *   POST   /api/v1/assets/:uuid/detections/:uuid
 *   DELETE /api/v1/assets/:uuid/detections/:uuid
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const DET_DOG = "d0d0d0d0-0000-0000-0000-000000000001";

interface Recorder {
  /**
   * Set just before the Objects tab is opened. Other screens on the way there (the default route)
   * list assets as well; only the reads this panel makes are counted and failed.
   */
  armed: boolean;
  assetListReads: number;
  updates: { url: string; body: Record<string, unknown> }[];
  deletes: string[];
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface MockOptions {
  failAssetListTimes?: number;
  /** Resolves the asset list only once this promise does. */
  holdAssetList?: Promise<void>;
  failUpdate?: boolean;
  failDelete?: boolean;
}

async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  let failuresLeft = opts.failAssetListTimes ?? 0;
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/assets(\?|$)/, async route => {
    if (!rec.armed) return json(route, { data: [], _metainfo: { totalCount: 0 } });
    rec.assetListReads++;
    if (opts.holdAssetList) await opts.holdAssetList;
    if (failuresLeft > 0) {
      failuresLeft--;
      return json(route, { message: "asset index is rebuilding" }, 503);
    }
    return json(route, { data: [{ uuid: ASSET_UUID, file: { filename: "street.mp4" } }], _metainfo: { totalCount: 1 } });
  });
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections$/, route => json(route, {
    data: [{
      uuid: DET_DOG, type: "objectdetection", label: "dog", frameNumber: 7,
      bboxX: 0.1, bboxY: 0.1, bboxWidth: 0.2, bboxHeight: 0.2, confidence: 0.92, assetUuid: ASSET_UUID,
    }],
  }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+$/, route => {
    const req = route.request();
    if (req.method() === "DELETE") {
      rec.deletes.push(req.url());
      if (opts.failDelete) return json(route, { message: "detection is locked" }, 409);
      return route.fulfill({ status: 204, body: "" });
    }
    rec.updates.push({ url: req.url(), body: JSON.parse(req.postData() || "{}") });
    if (opts.failUpdate) return json(route, { message: "detection is locked" }, 409);
    return json(route, {});
  });
}

async function openObjects(page: Page, rec: Recorder) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByTestId("facedetection-switcher")).toBeVisible({ timeout: 10_000 });
  rec.armed = true;
  await page.getByRole("tab", { name: "Objects", exact: true }).click();
}

function toast(page: Page, text: string) {
  return page.locator("[role=alert]").filter({ hasText: text });
}

const DETECTION_URL = new RegExp(`/api/v1/assets/${ASSET_UUID}/detections/${DET_DOG}$`);

test.describe("Object detection failures – mocked e2e", () => {
  test("a rejected confirm says so and leaves the row undecided", async ({ page }) => {
    const rec: Recorder = { armed: false, assetListReads: 0, updates: [], deletes: [] };
    await installMocks(page, rec, { failUpdate: true });
    await openObjects(page, rec);

    await page.getByTestId("objectdetection-confirm").click({ timeout: 10_000 });
    await expect.poll(() => rec.updates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.updates[0].url).toMatch(DETECTION_URL);
    await expect(toast(page, "Failed to confirm detection")).toBeVisible();
    await expect(page.getByTestId("objectdetection-decision")).toHaveCount(0);
    await expect(page.getByTestId("objectdetection-confirm")).toBeVisible();
    await expect(page.getByTestId("objectdetection-reject")).toBeVisible();
  });

  test("a rejected reject says so and keeps the row", async ({ page }) => {
    const rec: Recorder = { armed: false, assetListReads: 0, updates: [], deletes: [] };
    await installMocks(page, rec, { failDelete: true });
    await openObjects(page, rec);

    await page.getByTestId("objectdetection-reject").click({ timeout: 10_000 });
    await expect.poll(() => rec.deletes.length, { timeout: 10_000 }).toBe(1);
    expect(rec.deletes[0]).toMatch(DETECTION_URL);
    await expect(toast(page, "Failed to delete detection")).toBeVisible();
    await expect(page.getByTestId("objectdetection-row")).toHaveCount(1);
    await expect(page.getByTestId("objectdetection-reject")).toBeVisible();
    await expect(page.getByText("1 detections · 1 labels", { exact: true })).toBeVisible();
  });

  test("a slow list shows a loading state, not the empty state", async ({ page }) => {
    const rec: Recorder = { armed: false, assetListReads: 0, updates: [], deletes: [] };
    let release!: () => void;
    await installMocks(page, rec, { holdAssetList: new Promise<void>(r => { release = r; }) });
    await openObjects(page, rec);

    await expect.poll(() => rec.assetListReads, { timeout: 10_000 }).toBe(1);
    await expect(page.getByTestId("objectdetection-loading")).toBeVisible();
    await expect(page.getByTestId("objectdetection-empty")).toHaveCount(0);

    release();
    await expect(page.getByTestId("objectdetection-row")).toHaveCount(1, { timeout: 10_000 });
    await expect(page.getByTestId("objectdetection-loading")).toHaveCount(0);
  });

  test("a failed load says it failed, and Try again loads the detections", async ({ page }) => {
    const rec: Recorder = { armed: false, assetListReads: 0, updates: [], deletes: [] };
    await installMocks(page, rec, { failAssetListTimes: 1 });
    await openObjects(page, rec);

    const failure = page.getByTestId("objectdetection-load-failure");
    await expect(failure).toBeVisible({ timeout: 10_000 });
    await expect(failure).toContainText("asset index is rebuilding");
    // The defect: this used to read "No object detections found".
    await expect(page.getByTestId("objectdetection-empty")).toHaveCount(0);

    await page.getByTestId("objectdetection-load-failure-retry").click();
    await expect.poll(() => rec.assetListReads, { timeout: 10_000 }).toBe(2);
    await expect(failure).toHaveCount(0);
    await expect(page.getByTestId("objectdetection-row")).toHaveCount(1);
  });
});
