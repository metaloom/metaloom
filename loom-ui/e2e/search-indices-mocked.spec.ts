import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the search index admin screen (`/admin/indices`).
 *
 * No running Loom backend: the REST API is intercepted via `page.route`. What is under test is the
 * screen's honesty rather than its layout — a disabled index must read as a decision, a broken one
 * as a fault, a job's progress must be determinate only when the server supplied a total, and a
 * failed poll must not blank a screen whose whole purpose is to say what is wrong.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const BACKENDS = [
  { id: "lexical", provider: "postgres", enabled: true, available: true, documentCount: 41286, deletedCount: 0, sizeBytes: 612368384 },
  { id: "vector", provider: "lucene", enabled: true, available: true, documentCount: 128402, deletedCount: 3100, sizeBytes: 1476395008 },
  { id: "fingerprint", provider: "none", enabled: false, available: false, reason: "The fingerprint index is disabled (LOOM_SIMILARITY_ENABLED=false).", documentCount: 0, deletedCount: 0, sizeBytes: 0 },
];

const LEXICAL = {
  id: "lexical",
  kind: "LEXICAL",
  backendId: "lexical",
  label: "Lexical search documents",
  enabled: true,
  available: true,
  reason: "Maintained synchronously by database triggers.",
  documentCount: 41286,
  indexedCount: 41286,
  pendingCount: 0,
  supportedActions: ["REINDEX"],
};

const FACE = {
  id: "vector-face-inspireface-r18-512",
  kind: "VECTOR",
  backendId: "vector",
  label: "Face embeddings",
  type: "face",
  model: "inspireface-r18",
  dimensions: 512,
  enabled: true,
  available: true,
  documentCount: 128402,
  indexedCount: 127590,
  pendingCount: 812,
  supportedActions: ["REINDEX", "DELTA_SYNC", "DROP"],
};

const FINGERPRINT = {
  id: "fingerprint",
  kind: "FINGERPRINT",
  backendId: "fingerprint",
  label: "Duplicate fingerprints",
  algorithm: "metaloom-multisector-v1",
  enabled: false,
  available: false,
  reason: "The fingerprint index is disabled (LOOM_SIMILARITY_ENABLED=false).",
  documentCount: 0,
  indexedCount: 0,
  pendingCount: 0,
  supportedActions: ["REINDEX", "DELTA_SYNC", "DROP"],
};

function listBody(indices: unknown[] = [LEXICAL, FACE, FINGERPRINT]) {
  return { data: indices, backends: BACKENDS };
}

async function installMocks(page: Page) {
  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));
}

async function open(page: Page) {
  // The app is mounted under /ui/, and auth is in memory — deep-link first, then sign in.
  await page.goto("/ui/admin/indices");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Search indices admin – mocked", () => {

  test("groups indices under their storage backend and reports size per backend", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, listBody()));
    await open(page);

    await expect(page.getByTestId("search-indices-admin")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("search-index-row-lexical")).toBeVisible();
    await expect(page.getByTestId("search-index-row-vector-face-inspireface-r18-512")).toBeVisible();

    // Size belongs to the directory, not to a space in it: Lucene segments interleave the
    // vector spaces, so there is no per-space byte figure and the screen must not invent one.
    await expect(page.getByTestId("search-index-backend-size-vector")).toHaveText("1.4 GiB");
    await expect(page.getByTestId("search-index-backend-size-lexical")).toHaveText("584.0 MiB");
  });

  test("shows the embedding model and dimensions of a vector space", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, listBody()));
    await open(page);

    const row = page.getByTestId("search-index-row-vector-face-inspireface-r18-512");
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText("inspireface-r18");
    await expect(row).toContainText("512d");
  });

  test("reports a backlog as Behind and shows the pending count", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, listBody()));
    await open(page);

    await expect(page.getByTestId("search-index-pending-vector-face-inspireface-r18-512"))
      .toHaveText("812", { timeout: 10_000 });
    await expect(page.getByTestId("search-index-row-vector-face-inspireface-r18-512"))
      .toHaveAttribute("data-state", "Behind");
  });

  test("a never-enabled index reads as Disabled, not as broken", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, listBody()));
    await open(page);

    // Off by configuration is a decision; enabled-but-unopenable is a fault. Collapsing the two
    // would send an operator hunting for a failure that never happened.
    const row = page.getByTestId("search-index-row-fingerprint");
    await expect(row).toHaveAttribute("data-state", "Disabled", { timeout: 10_000 });
    await expect(page.getByTestId("search-index-status-fingerprint")).toHaveText("Disabled");
  });

  test("an enabled but unopenable index reads as Unavailable", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route =>
      json(route, listBody([{ ...FACE, enabled: true, available: false, reason: "The vector index is configured but could not be opened." }])));
    await open(page);

    const row = page.getByTestId("search-index-row-vector-face-inspireface-r18-512");
    await expect(row).toHaveAttribute("data-state", "Unavailable", { timeout: 10_000 });
  });

  test("only offers the actions the index advertises", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, listBody()));
    await open(page);

    await expect(page.getByTestId("search-index-action-lexical-reindex")).toBeVisible({ timeout: 10_000 });
    // The lexical index is trigger-maintained and cannot be behind, and dropping it would make
    // search answer nothing until a rebuild — so neither control exists for it.
    await expect(page.getByTestId("search-index-action-lexical-delta_sync")).toHaveCount(0);
    await expect(page.getByTestId("search-index-action-lexical-drop")).toHaveCount(0);
    await expect(page.getByTestId("search-index-action-vector-face-inspireface-r18-512-delta_sync")).toBeVisible();
  });

  test("starting a reindex posts the action and then renders determinate progress", async ({ page }) => {
    await installMocks(page);
    let started = false;
    const posted: unknown[] = [];

    await page.route(/\/api\/v1\/search-indices$/, route =>
      json(route, listBody([
        started
          ? {
            ...FACE,
            activeJob: {
              uuid: "job-1", indexId: FACE.id, action: "REINDEX", state: "RUNNING",
              processed: 32100, total: 128402, removed: 0,
            },
          }
          : FACE,
      ])));
    await page.route(/\/api\/v1\/search-indices\/[^/]+\/jobs$/, route => {
      posted.push(JSON.parse(route.request().postData() ?? "{}"));
      started = true;
      return json(route, { uuid: "job-1", indexId: FACE.id, action: "REINDEX", state: "PENDING", processed: 0, removed: 0 }, 202);
    });

    await open(page);
    await page.getByTestId("search-index-action-vector-face-inspireface-r18-512-reindex").click();

    // The action travels in the body, not the path, so one job collection serves all three.
    await expect.poll(() => posted).toEqual([{ action: "REINDEX" }]);

    const bar = page.getByTestId("search-index-job-progress-vector-face-inspireface-r18-512");
    await expect(bar).toBeVisible({ timeout: 10_000 });
    await expect(bar).toHaveAttribute("data-progress", "25");
  });

  test("renders an indeterminate bar when the server reports no total", async ({ page }) => {
    await installMocks(page);
    // The lexical rebuild is one SQL call whose length cannot be predicted. A determinate bar
    // stuck at 0% would read as a hung job, which is the opposite of what is happening.
    await page.route(/\/api\/v1\/search-indices$/, route =>
      json(route, listBody([{
        ...LEXICAL,
        activeJob: { uuid: "job-2", indexId: "lexical", action: "REINDEX", state: "RUNNING", processed: 0, total: null, removed: 0 },
      }])));
    await open(page);

    await expect(page.getByTestId("search-index-job-progress-lexical"))
      .toHaveAttribute("data-progress", "indeterminate", { timeout: 10_000 });
  });

  test("a drop asks for confirmation and says the source data survives", async ({ page }) => {
    await installMocks(page);
    const posted: unknown[] = [];
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, listBody([FACE])));
    await page.route(/\/api\/v1\/search-indices\/[^/]+\/jobs$/, route => {
      posted.push(JSON.parse(route.request().postData() ?? "{}"));
      return json(route, { uuid: "job-3", indexId: FACE.id, action: "DROP", state: "PENDING", processed: 0, removed: 0 }, 202);
    });

    await open(page);
    await page.getByTestId("search-index-action-vector-face-inspireface-r18-512-drop").click();

    await expect(page.getByTestId("search-index-drop-confirm-text")).toContainText(/not touched|rebuilt/i);
    // Nothing is posted until the dialog is confirmed.
    expect(posted).toEqual([]);

    await page.getByTestId("search-index-drop-confirm").click();
    await expect.poll(() => posted).toEqual([{ action: "DROP" }]);
  });

  test("a failed refresh warns without discarding the last good snapshot", async ({ page }) => {
    await installMocks(page);
    let calls = 0;
    await page.route(/\/api\/v1\/search-indices$/, route => {
      calls += 1;
      return calls === 1 ? json(route, listBody()) : json(route, { message: "boom" }, 500);
    });
    await open(page);

    await expect(page.getByTestId("search-index-row-lexical")).toBeVisible({ timeout: 10_000 });
    // A blank screen is a worse answer than a stale one when the question is "is anything broken".
    await expect(page.getByTestId("search-indices-error")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId("search-index-row-lexical")).toBeVisible();
  });

  test("a 403 explains the missing permission instead of rendering an empty table", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route => json(route, { message: "forbidden" }, 403));
    await open(page);

    await expect(page.getByTestId("search-indices-forbidden")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("search-index-row-lexical")).toHaveCount(0);
  });

  test("a running job offers cancel instead of the action buttons", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/search-indices$/, route =>
      json(route, listBody([{
        ...FACE,
        activeJob: { uuid: "job-4", indexId: FACE.id, action: "DELTA_SYNC", state: "RUNNING", processed: 10, total: 100, removed: 2 },
      }])));
    await page.route(/\/api\/v1\/search-indices\/[^/]+\/jobs\/[^/]+$/, route =>
      json(route, { uuid: "job-4", indexId: FACE.id, action: "DELTA_SYNC", state: "RUNNING", processed: 10, total: 100, removed: 2 }));

    await open(page);

    await expect(page.getByTestId("search-index-action-vector-face-inspireface-r18-512-cancel"))
      .toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("search-index-action-vector-face-inspireface-r18-512-reindex")).toHaveCount(0);
  });
});
