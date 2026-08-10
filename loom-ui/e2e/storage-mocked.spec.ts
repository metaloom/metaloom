import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the storage admin screen (`/admin/storage`).
 *
 * No running Loom backend: the REST API is intercepted via `page.route`. What is under test is the
 * screen's honesty rather than its layout, because the ways a capacity dashboard can mislead are
 * specific and each one has a real consequence:
 *
 *  - painting a backend green when it never reported capacity, which tells an operator a bucket is
 *    fine when nobody asked it anything;
 *  - showing one byte column, which either overstates a deduplicated install several times over or
 *    hides why the two numbers differ;
 *  - blanking itself when a refresh fails, which is a worse answer than a slightly stale one when
 *    the question is "am I about to run out of disk".
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const BUCKET_UUID = "6c1f7b1e-0d0a-4b3a-9f7c-2f1d3c4b5a60";
const SCRATCH_UUID = "7d2e8c3f-1b4a-4c5d-8e9f-0a1b2c3d4e5f";

const GIB = 1024 * 1024 * 1024;

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const THRESHOLDS = { minFreeSpaceBytes: GIB, warnFreeSpaceBytes: 5 * GIB, maxUploadSizeBytes: -1 };

const LOCAL_BACKEND = {
  poolUuid: null,
  poolName: "Default storage",
  kind: "filesystem",
  description: "filesystem:/uploads",
  freeBytes: 45 * GIB,
  totalBytes: 200 * GIB,
  watermark: "OK",
  objects: 1842,
  bytes: 150 * GIB,
  error: null,
};

/**
 * An object store: no capacity, so no watermark that means anything.
 *
 * `freeBytes`, `totalBytes` and `error` are *omitted* rather than sent as null, because that is what
 * the server actually puts on the wire - it drops null fields entirely. Mocking them as explicit
 * nulls would hide the case where a client checks `=== null` and lets undefined through into
 * arithmetic.
 */
const BUCKET_BACKEND = {
  poolUuid: BUCKET_UUID,
  poolName: "Archive S3",
  kind: "s3",
  description: "s3:metaloom-archive-prod",
  watermark: "UNKNOWN",
  objects: 9431,
  bytes: 2048 * GIB,
};

const FULL_BACKEND = {
  poolUuid: SCRATCH_UUID,
  poolName: "Scratch",
  kind: "filesystem",
  description: "filesystem:/scratch",
  freeBytes: Math.round(0.4 * GIB),
  totalBytes: 50 * GIB,
  watermark: "CRITICAL",
  objects: 12,
  bytes: 49 * GIB,
  error: null,
};

function category(name: string, elements: number, logicalBytes: number, distinctObjects: number, distinctBytes: number) {
  return { category: name, elements, logicalBytes, distinctObjects, distinctBytes };
}

const CATEGORIES = [
  category("ASSET_BINARY", 1204, 150 * GIB, 1198, 149 * GIB),
  // Heavily deduplicated: the claimed total is four times what is actually on disk, which is the
  // case a single-column report would misrepresent.
  category("FACE_CROP", 8842, 4 * GIB, 2210, GIB),
  category("PERSON_IMAGE", 112, 9_437_184, 104, 8_650_752),
  category("PERSON_AVATAR", 37, 3_145_728, 37, 3_145_728),
  category("USER_AVATAR", 9, 524_288, 9, 524_288),
  // Empty on purpose: the row must still be there.
  category("ASSET_THUMBNAIL", 0, 0, 0, 0),
  category("EMBEDDING_ATTACHMENT", 0, 0, 0, 0),
];

function report(backends: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    timestamp: "2026-08-10T09:14:22Z",
    thresholds: THRESHOLDS,
    categories: CATEGORIES,
    backends,
    objects: 11_285,
    distinctBytes: 151 * GIB,
    orphanObjects: 148,
    orphanBytes: 6_291_456,
    ...overrides,
  };
}

const HEALTHY = report([LOCAL_BACKEND, BUCKET_BACKEND]);
const DEGRADED = report([LOCAL_BACKEND, FULL_BACKEND, BUCKET_BACKEND]);

async function installMocks(page: Page) {
  // Registered first so the specific routes below win - Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
}

async function open(page: Page) {
  // The app is mounted under /ui/, and auth is in memory - deep-link first, then sign in.
  await page.goto("/ui/admin/storage");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Storage admin - mocked", () => {

  test("the summary reports what is on disk, not only what the catalogue claims", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, HEALTHY));
    await open(page);

    await expect(page.getByTestId("storage-admin")).toBeVisible({ timeout: 10_000 });
    // Attachments as the report counted them (11 285 objects / 151 GiB) plus the media row
    // (1 198 / 149 GiB). Summing every category's on-disk figure instead would double-count every
    // object that belongs to two of them.
    await expect(page.getByTestId("storage-total-objects")).toContainText("12483");
    await expect(page.getByTestId("storage-total-bytes")).toContainText("300.0 GiB");
    // Claimed 163 GiB against 300 GiB on disk... the point is only that the figure is derived from
    // the totals rather than from summing per-category savings, which would read 0 here.
    await expect(page.getByTestId("storage-total-saved")).toBeVisible();
  });

  /**
   * The failure mode the demo install actually produced: every category reported
   * logicalBytes === distinctBytes, so summing per-category savings said "0 B saved" beside two byte
   * columns that visibly disagreed. Sharing between categories is invisible to per-row arithmetic.
   */
  test("a saving that spans two kinds is still reported", async ({ page }) => {
    await installMocks(page);
    const shared = report([LOCAL_BACKEND], {
      categories: [
        category("ASSET_BINARY", 8, 800, 8, 800),
        category("PERSON_AVATAR", 3, 300, 3, 300),
        category("USER_AVATAR", 2, 200, 2, 200),
      ],
      objects: 4,
      distinctBytes: 400,
    });
    await page.route(/\/api\/v1\/storage$/, route => json(route, shared));
    await open(page);

    // 1300 claimed, 1200 on disk: one object is shared between the two avatar kinds.
    await expect(page.getByTestId("storage-total-saved")).toContainText("100 B", { timeout: 10_000 });
    // And each individual row honestly reports no saving of its own.
    await expect(page.getByTestId("storage-category-saved-USER_AVATAR")).toHaveText("—");
  });

  test("every kind of content gets a row, including the ones with nothing in them", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, HEALTHY));
    await open(page);

    await expect(page.getByTestId("storage-category-ASSET_BINARY")).toBeVisible({ timeout: 10_000 });
    // A row that vanishes when a kind is empty is indistinguishable from one the report stopped
    // counting, which is the failure this pins.
    await expect(page.getByTestId("storage-category-ASSET_THUMBNAIL")).toBeVisible();
    await expect(page.getByTestId("storage-category-EMBEDDING_ATTACHMENT")).toBeVisible();
    await expect(page.getByTestId("storage-category-USER_AVATAR")).toBeVisible();
    await expect(page.getByTestId("storage-category-PERSON_AVATAR")).toBeVisible();
  });

  test("a deduplicated kind shows both numbers and the saving between them", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, HEALTHY));
    await open(page);

    // 4 GiB claimed, 1 GiB on disk. Reporting only the first would overstate this install fourfold.
    await expect(page.getByTestId("storage-category-FACE_CROP")).toContainText("4.0 GiB", { timeout: 10_000 });
    await expect(page.getByTestId("storage-category-ondisk-FACE_CROP")).toHaveText("1.0 GiB");
    await expect(page.getByTestId("storage-category-saved-FACE_CROP")).toContainText("75%");

    // Nothing shared: an em dash rather than "0 B (0%)", which reads as a number worth comparing.
    await expect(page.getByTestId("storage-category-saved-USER_AVATAR")).toHaveText("—");
  });

  test("an object store reads as not measurable, never as healthy", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, HEALTHY));
    await open(page);

    const bucket = page.getByTestId(`storage-backend-${BUCKET_UUID}`);
    await expect(bucket).toBeVisible({ timeout: 10_000 });
    await expect(bucket).toHaveAttribute("data-watermark", "UNKNOWN");
    await expect(page.getByTestId(`storage-backend-watermark-${BUCKET_UUID}`)).toHaveText("Not measurable");
    // "Unknown", not "0 B": the difference between those two is the difference between a
    // non-question and an emergency.
    await expect(page.getByTestId(`storage-backend-free-${BUCKET_UUID}`)).toContainText("Unknown");
    // And no capacity bar at all - an empty bar would read as plenty of room.
    await expect(page.getByTestId(`storage-backend-bar-${BUCKET_UUID}`)).toHaveCount(0);
  });

  test("a full backend is flagged and sorted above the healthy ones", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, DEGRADED));
    await open(page);

    const scratch = page.getByTestId(`storage-backend-${SCRATCH_UUID}`);
    await expect(scratch).toBeVisible({ timeout: 10_000 });
    await expect(scratch).toHaveAttribute("data-watermark", "CRITICAL");
    await expect(page.getByTestId(`storage-backend-watermark-${SCRATCH_UUID}`)).toHaveText("Full");

    // Worst first: the one backend that needs attention must not be below two that do not.
    const order = await page.locator("[data-testid^='storage-backend-'][data-watermark]").evaluateAll(
      nodes => nodes.map(n => n.getAttribute("data-watermark")),
    );
    expect(order[0]).toBe("CRITICAL");
    // Unmeasurable last: it is not a problem, and burying the real warning under buckets would
    // defeat the ordering.
    expect(order[order.length - 1]).toBe("UNKNOWN");
  });

  test("unreferenced bytes are surfaced rather than quietly omitted", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, HEALTHY));
    await open(page);

    // Deleting an element removes the record but not the bytes. The screen is where that becomes
    // visible, so an install slowly filling with orphans is noticed rather than mysterious.
    await expect(page.getByTestId("storage-orphans")).toContainText("148", { timeout: 10_000 });
    await expect(page.getByTestId("storage-orphans")).toContainText("6.0 MiB");
  });

  test("a failed refresh warns but keeps the last report on screen", async ({ page }) => {
    await installMocks(page);
    let calls = 0;
    await page.route(/\/api\/v1\/storage$/, route => {
      calls += 1;
      return calls === 1 ? json(route, HEALTHY) : json(route, { message: "boom" }, 500);
    });
    await open(page);
    await expect(page.getByTestId("storage-summary")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("storage-refresh").click();

    await expect(page.getByTestId("storage-error")).toBeVisible({ timeout: 10_000 });
    // Still there: a blank panel is a worse answer than a slightly stale one.
    await expect(page.getByTestId("storage-summary")).toBeVisible();
    await expect(page.getByTestId("storage-category-ASSET_BINARY")).toBeVisible();
  });

  test("a caller without the permission is told so, not shown an empty report", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/storage$/, route => json(route, { message: "Forbidden" }, 403));
    await open(page);

    await expect(page.getByTestId("storage-forbidden")).toBeVisible({ timeout: 10_000 });
    // Zeros everywhere would read as "nothing is stored", which is a different and wrong answer.
    await expect(page.getByTestId("storage-summary")).toHaveCount(0);
  });

  test("a broken pool is reported on its own card without failing the report", async ({ page }) => {
    await installMocks(page);
    const { freeBytes, totalBytes, ...rest } = FULL_BACKEND;
    const broken = { ...rest, watermark: "UNKNOWN", description: null, error: "Bucket not reachable" };
    await page.route(/\/api\/v1\/storage$/, route => json(route, report([LOCAL_BACKEND, broken])));
    await open(page);

    await expect(page.getByTestId(`storage-backend-error-${SCRATCH_UUID}`)).toContainText("Bucket not reachable", { timeout: 10_000 });
    // The rest of the report still renders: one misconfigured pool must not take the screen down.
    await expect(page.getByTestId("storage-category-ASSET_BINARY")).toBeVisible();
    await expect(page.getByTestId("storage-backend-default")).toBeVisible();
  });
});
