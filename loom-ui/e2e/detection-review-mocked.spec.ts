import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the detection review actions — no running Loom backend required.
 *
 * These are the human-in-the-loop controls that decide whether a machine's boxes are worth keeping,
 * and until now not one of their testids was referenced by a spec. Two screens carry them:
 *
 *  - **Asset detail** (`asset-detections`) — `detection-bulk-toggle`, `detection-bulk-save`,
 *    `detection-confirm`, `detection-redraw`. Bulk mode here is a *drawing* mode: it stages several
 *    boxes on the image and commits them in one request. It is not a row-selection checkbox, and the
 *    saved payload carries geometry, not the uuids of already-stored rows.
 *  - **Detection → Objects** (`ObjectDetectionManagement`) — `objectdetection-confirm` /
 *    `objectdetection-reject`.
 *
 * Routes exercised:
 *   GET    /api/v1/assets/:uuid/detections
 *   POST   /api/v1/assets/:uuid/detections            (single create — asserted *not* to be used in bulk mode)
 *   POST   /api/v1/assets/:uuid/detections/bulk
 *   POST   /api/v1/assets/:uuid/detections/:uuid
 *   DELETE /api/v1/assets/:uuid/detections/:uuid
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const DETECTION_DOG = "dddddddd-dddd-dddd-dddd-dddddddddddd";
const DETECTION_CAT = "cccccccc-cccc-cccc-cccc-cccccccccccc";

/** A 1x1 JPEG, so the media pane has real bytes to lay out rather than a broken image. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

interface StoredDetection {
  uuid: string;
  type: string;
  frameNumber: number;
  bboxX: number;
  bboxY: number;
  bboxWidth: number;
  bboxHeight: number;
  confidence: number;
  assetUuid: string;
  meta?: Record<string, unknown>;
}

/** What the browser actually sent, so the tests can assert it rather than infer it from the DOM. */
interface Recorder {
  /** Bodies of every POST to …/detections/bulk. */
  bulkSaves: { detections: unknown[] }[];
  /** Bodies of every POST to …/detections (the single-create route). */
  singleCreates: unknown[];
  /** Bodies of every POST to …/detections/:uuid, keyed by the detection uuid. */
  updates: { uuid: string; body: Record<string, unknown> }[];
  /** Uuids of every DELETE …/detections/:uuid. */
  deletes: string[];
}

function recorder(): Recorder {
  return { bulkSaves: [], singleCreates: [], updates: [], deletes: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function detection(uuid: string, label: string, x: number): StoredDetection {
  return {
    uuid,
    type: "objectdetection",
    frameNumber: 0,
    bboxX: x,
    bboxY: 0.2,
    bboxWidth: 0.15,
    bboxHeight: 0.15,
    confidence: 0.9,
    assetUuid: ASSET_UUID,
    meta: { label },
  };
}

interface MockOptions {
  seed?: StoredDetection[];
  /** Make …/detections/bulk answer 500, to test that a failed save keeps the staging. */
  failBulkSave?: boolean;
}

/**
 * Routes are matched most-recently-registered first, so the catch-all goes in first and the more
 * specific paths follow. …/detections/bulk is registered *after* …/detections/:uuid because the
 * latter's pattern would otherwise swallow it.
 */
async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  const detections: StoredDetection[] = [...(opts.seed ?? [])];
  let seq = 0;

  const asset = () => ({
    uuid: ASSET_UUID,
    file: { filename: "review-me.jpg", mimeType: "image/jpeg", size: 1024 },
    status: { creator: { uuid: ME_UUID } },
  });

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));
  await page.route(/\/api\/v1\/assets\/[^/]+\/binary\/data/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );

  // List + single create.
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      rec.singleCreates.push(body);
      const created: StoredDetection = {
        uuid: `created-${++seq}`,
        type: body.type ?? "objectdetection",
        frameNumber: body.frameNumber ?? 0,
        bboxX: body.bboxX ?? 0,
        bboxY: body.bboxY ?? 0,
        bboxWidth: body.bboxWidth ?? 0,
        bboxHeight: body.bboxHeight ?? 0,
        confidence: body.confidence ?? 1,
        assetUuid: ASSET_UUID,
        meta: body.meta,
      };
      detections.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: detections });
  });

  // Update by uuid (POST) and delete by uuid (DELETE).
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/detections/")[1].split("?")[0]);
    const stored = detections.find(d => d.uuid === uuid);
    if (route.request().method() === "DELETE") {
      rec.deletes.push(uuid);
      const idx = detections.findIndex(d => d.uuid === uuid);
      if (idx >= 0) detections.splice(idx, 1);
      return route.fulfill({ status: 200, body: "" });
    }
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.updates.push({ uuid, body });
    if (!stored) return json(route, { message: "no such detection" }, 404);
    Object.assign(stored, body);
    return json(route, stored);
  });

  // Registered last so it wins over the by-uuid pattern above.
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/bulk$/, route => {
    const body = JSON.parse(route.request().postData() || "{}");
    rec.bulkSaves.push(body);
    if (opts.failBulkSave) return json(route, { message: "nope" }, 500);
    const created = (body.detections ?? []).map((d: Record<string, number | string>) => {
      const stored: StoredDetection = {
        uuid: `bulk-${++seq}`,
        type: (d.type as string) ?? "objectdetection",
        frameNumber: (d.frameNumber as number) ?? 0,
        bboxX: (d.bboxX as number) ?? 0,
        bboxY: (d.bboxY as number) ?? 0,
        bboxWidth: (d.bboxWidth as number) ?? 0,
        bboxHeight: (d.bboxHeight as number) ?? 0,
        confidence: (d.confidence as number) ?? 1,
        assetUuid: ASSET_UUID,
      };
      detections.push(stored);
      return stored;
    });
    return json(route, { detections: created, total: created.length, created: created.length, failed: 0 });
  });
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/** Open the asset and turn on detection mode, which is what renders the overlay and the row list. */
async function openDetectionPanel(page: Page) {
  await page.getByRole("button", { name: "Assets" }).first().click();
  const link = page.getByText("review-me.jpg").first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  await expect(page.getByTestId("asset-detections")).toBeVisible({ timeout: 10_000 });
  await page.getByTestId("detection-mode-toggle").click();
}

/** Rubber-band a box over the image, in fractions of the image container. */
async function drawBox(page: Page, x0: number, y0: number, x1: number, y1: number) {
  const image = page.getByTestId("zoomable-image");
  await expect(image).toBeVisible();
  const box = await image.boundingBox();
  if (!box) throw new Error("zoomable-image has no bounding box");
  await page.mouse.move(box.x + box.width * x0, box.y + box.height * y0);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * x1, box.y + box.height * y1, { steps: 10 });
  await page.mouse.up();
}

test.describe("Detection review on asset detail – mocked e2e", () => {
  /**
   * The reason the bulk route exists.
   *
   * Bulk mode stages the boxes locally and commits the run in a single request; drawing three boxes
   * without it would be three POSTs to the single-create route. The assertion that
   * `singleCreates` stayed empty is the load-bearing half.
   */
  test("bulk mode stages several boxes and saves them in one request", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await page.goto("/");
    await login(page);
    await openDetectionPanel(page);

    await page.getByTestId("detection-bulk-toggle").click();

    await drawBox(page, 0.10, 0.10, 0.30, 0.40);
    await drawBox(page, 0.40, 0.10, 0.60, 0.40);
    await drawBox(page, 0.70, 0.10, 0.90, 0.40);

    // The button only appears once something is staged, and it counts what is waiting.
    const save = page.getByTestId("detection-bulk-save");
    await expect(save).toHaveText(/Save all \(3\)/, { timeout: 10_000 });

    await save.click();

    await expect.poll(() => rec.bulkSaves.length, { timeout: 10_000 }).toBe(1);
    expect(rec.bulkSaves[0].detections).toHaveLength(3);
    // Three boxes, one request — not three.
    expect(rec.singleCreates).toEqual([]);

    // The staging is cleared and the rows come back from the server, not from local state.
    await expect(save).toHaveCount(0);
    await expect(page.getByTestId("detection-row")).toHaveCount(3, { timeout: 10_000 });
  });

  /**
   * A failed save must not eat the reviewer's work.
   *
   * The boxes only exist in the browser until the bulk write lands, so clearing the staging on a
   * rejected request would throw away everything that was drawn with nothing stored in exchange.
   */
  test("a failed bulk save leaves the staged boxes intact so the reviewer can retry", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { failBulkSave: true });
    await page.goto("/");
    await login(page);
    await openDetectionPanel(page);

    await page.getByTestId("detection-bulk-toggle").click();
    await drawBox(page, 0.10, 0.10, 0.30, 0.40);
    await drawBox(page, 0.40, 0.10, 0.60, 0.40);

    const save = page.getByTestId("detection-bulk-save");
    await expect(save).toHaveText(/Save all \(2\)/, { timeout: 10_000 });
    await save.click();

    await expect(page.getByText(/failed to create detection/i)).toBeVisible({ timeout: 10_000 });

    // Still two staged, still offering to save them, and nothing was stored.
    await expect(save).toHaveText(/Save all \(2\)/);
    await expect(page.getByTestId("detection-row")).toHaveCount(0);

    // And the retry goes out with the same two boxes rather than an empty batch.
    await save.click();
    await expect.poll(() => rec.bulkSaves.length, { timeout: 10_000 }).toBe(2);
    expect(rec.bulkSaves[1].detections).toHaveLength(2);
  });

  test("confirming a detection persists and the row records the verdict", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { seed: [detection(DETECTION_DOG, "dog", 0.1)] });
    await page.goto("/");
    await login(page);
    await openDetectionPanel(page);

    const row = page.getByTestId("detection-row");
    await expect(row).toHaveCount(1, { timeout: 10_000 });
    await expect(row).toHaveAttribute("data-confirmed", "false");

    await row.getByTestId("detection-confirm").click();

    await expect.poll(() => rec.updates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.updates[0].uuid).toBe(DETECTION_DOG);
    // The producer's own meta is carried through rather than replaced — the label must survive the
    // reviewer agreeing with it.
    expect(rec.updates[0].body.meta).toMatchObject({ label: "dog", confirmed: true });
    await expect(row).toHaveAttribute("data-confirmed", "true");

    // Auth is in-memory, so a reload logs us out. Signing back in and still finding the verdict is
    // what proves it was read back from the store rather than kept in React state.
    await page.reload();
    await login(page);
    await openDetectionPanel(page);

    await expect(page.getByTestId("detection-row")).toHaveAttribute("data-confirmed", "true", { timeout: 10_000 });
  });

  /**
   * Redraw retargets the next box onto an existing detection instead of creating one.
   *
   * The failure this guards is the box being stored as a *new* detection, which leaves the reviewer
   * with two overlapping rows and the wrong one still in the data.
   */
  test("redrawing a detection updates its coordinates instead of creating a new one", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { seed: [detection(DETECTION_DOG, "dog", 0.1)] });
    await page.goto("/");
    await login(page);
    await openDetectionPanel(page);

    const row = page.getByTestId("detection-row");
    await expect(row).toHaveCount(1, { timeout: 10_000 });

    await row.getByTestId("detection-redraw").click();
    await drawBox(page, 0.50, 0.30, 0.80, 0.70);

    await expect.poll(() => rec.updates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.updates[0].uuid).toBe(DETECTION_DOG);
    // ZoomableImage reports the region normalized to the container, so the numbers are the fractions
    // that were dragged. A couple of percent of slack covers rounding at the container edges.
    expect(rec.updates[0].body.bboxX as number).toBeCloseTo(0.50, 1);
    expect(rec.updates[0].body.bboxY as number).toBeCloseTo(0.30, 1);
    expect(rec.updates[0].body.bboxWidth as number).toBeCloseTo(0.30, 1);
    expect(rec.updates[0].body.bboxHeight as number).toBeCloseTo(0.40, 1);
    expect(rec.singleCreates).toEqual([]);
    await expect(row).toHaveCount(1);

    // The new geometry survives the round-trip: the overlay is drawn from the stored bbox.
    await page.reload();
    await login(page);
    await openDetectionPanel(page);

    const image = page.getByTestId("zoomable-image");
    const region = page.getByTestId("image-region");
    await expect(region).toHaveCount(1, { timeout: 10_000 });
    const imageBox = await image.boundingBox();
    const regionBox = await region.boundingBox();
    if (!imageBox || !regionBox) throw new Error("overlay geometry unavailable");
    expect((regionBox.x - imageBox.x) / imageBox.width).toBeCloseTo(0.50, 1);
    expect((regionBox.y - imageBox.y) / imageBox.height).toBeCloseTo(0.30, 1);
  });
});

test.describe("Object detection review screen – mocked e2e", () => {
  async function openObjectsTab(page: Page) {
    await page.getByRole("button", { name: "Detection", exact: true }).first().click();
    await page.getByRole("tab", { name: /objects/i }).click();
    await expect(page.getByTestId("objectdetection-row").first()).toBeVisible({ timeout: 10_000 });
  }

  test("confirming an object detection writes the verdict and locks the row", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      seed: [detection(DETECTION_DOG, "dog", 0.1), detection(DETECTION_CAT, "cat", 0.5)],
    });
    await page.goto("/");
    await login(page);
    await openObjectsTab(page);

    const rows = page.getByTestId("objectdetection-row");
    await expect(rows).toHaveCount(2, { timeout: 10_000 });

    const dogRow = page.getByTestId("objectdetection-group").filter({ hasText: "dog" }).getByTestId("objectdetection-row");
    await dogRow.getByTestId("objectdetection-confirm").click();

    await expect.poll(() => rec.updates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.updates[0].uuid).toBe(DETECTION_DOG);
    expect(rec.updates[0].body.meta).toMatchObject({ label: "dog", confirmed: true });

    // A decided row swaps its two buttons for a verdict chip, so the same call cannot be made twice.
    await expect(dogRow.getByTestId("objectdetection-decision")).toHaveText("confirmed");
    await expect(dogRow.getByTestId("objectdetection-confirm")).toHaveCount(0);
    await expect(dogRow.getByTestId("objectdetection-reject")).toHaveCount(0);

    // The other group is untouched — a verdict is per detection, not per screen.
    const catRow = page.getByTestId("objectdetection-group").filter({ hasText: "cat" }).getByTestId("objectdetection-row");
    await expect(catRow.getByTestId("objectdetection-confirm")).toHaveCount(1);
  });

  test("rejecting an object detection deletes it and it stays gone", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      seed: [detection(DETECTION_DOG, "dog", 0.1), detection(DETECTION_CAT, "cat", 0.5)],
    });
    await page.goto("/");
    await login(page);
    await openObjectsTab(page);

    await expect(page.getByTestId("objectdetection-row")).toHaveCount(2, { timeout: 10_000 });

    const catGroup = page.getByTestId("objectdetection-group").filter({ hasText: "cat" });
    await catGroup.getByTestId("objectdetection-reject").click();

    await expect.poll(() => rec.deletes, { timeout: 10_000 }).toEqual([DETECTION_CAT]);
    await expect(page.getByTestId("objectdetection-row")).toHaveCount(1);

    // Reject is a delete, not a local hide: the row must not come back when the screen reloads.
    await page.reload();
    await login(page);
    await openObjectsTab(page);

    await expect(page.getByTestId("objectdetection-row")).toHaveCount(1, { timeout: 10_000 });
    await expect(page.getByTestId("objectdetection-group").filter({ hasText: "cat" })).toHaveCount(0);
  });

  test("the screen reports an empty review queue rather than a blank page", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { seed: [] });
    await page.goto("/");
    await login(page);

    await page.getByRole("button", { name: "Detection", exact: true }).first().click();
    await page.getByRole("tab", { name: /objects/i }).click();

    await expect(page.getByTestId("objectdetection-empty")).toBeVisible({ timeout: 10_000 });
  });
});
