import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Detections (object & face detections on assets).
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

async function loginAndGoToDetection(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  // Navigate to detection page
  await page.getByRole("button", { name: /detection/i }).first().click();
  await expect(page.getByText(/detection/i).first()).toBeVisible({ timeout: 10_000 });
}

test.describe("Detections – backend e2e", () => {

  test("object detection page loads detections from API", async ({ page }) => {
    await loginAndGoToDetection(page);

    // By role: the page has a tab called Objects and also lists assets whose names contain "object".
    await page.getByRole("tab", { name: /objects/i }).click();
    // The panel fetches its own page of detections, so wait for a group rather than for a label:
    // scanning while the list is still empty finds nothing and reports it as "no detections".
    await expect(page.getByTestId("objectdetection-group").first()).toBeVisible({ timeout: 15_000 });

    // Wait for detections to load. The demo seeds these labels on the street-crossing photograph,
    // the cyclist, the dog walker and the traffic clip.
    //
    // `.first()` matters: each label heads a group and can appear more than once on the page, and a
    // locator that resolves to several elements throws in strict mode — which the catch below would
    // swallow, leaving every label "not found" whatever the screen holds.
    const labels = ["car", "person", "bicycle", "dog", "bus", "traffic light"];
    let foundAny = false;
    for (const label of labels) {
      const loc = page.getByText(label, { exact: false }).first();
      if (await loc.isVisible({ timeout: 5_000 }).catch(() => false)) {
        foundAny = true;
        break;
      }
    }
    expect(foundAny).toBe(true);
  });

  test("detection API CRUD via fetch", async ({ page }) => {
    // Login to get a token
    await page.goto("/");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

    // Use page.evaluate to perform API calls directly
    const result = await page.evaluate(async () => {
      // Both arms of the ternary this replaces were "/api/v1", and the `import.meta` in its
      // condition made the whole function unserialisable — page.evaluate refused to run it at all.
      const baseUrl = "/api/v1";

      // Sign in again for a bearer token, the way the sibling backend specs do. There is nothing to
      // read out of localStorage: AuthContext keeps the token in memory and the server sets an
      // HttpOnly cookie beside it, so a `loom_auth` key has never existed.
      const loginRes = await fetch(`${baseUrl}/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;

      const headers = {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`,
      };

      // List assets to get a real asset UUID
      const assetsRes = await fetch(`/api/v1/assets`, { headers });
      const assetsBody = await assetsRes.json();
      const assetUuid = assetsBody?.data?.[0]?.uuid;
      if (!assetUuid) return { error: "No assets found" };

      // Create a detection
      const createRes = await fetch(`/api/v1/assets/${assetUuid}/detections`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          type: "objectdetection",
          frameNumber: 42,
          bboxX: 0.1,
          bboxY: 0.2,
          bboxWidth: 0.3,
          bboxHeight: 0.4,
          confidence: 0.95,
          meta: { label: "e2e-test-object" },
        }),
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "Create failed", created };

      // Load the detection
      const loadRes = await fetch(`/api/v1/assets/${assetUuid}/detections/${created.uuid}`, { headers });
      const loaded = await loadRes.json();

      // Update the detection
      const updateRes = await fetch(`/api/v1/assets/${assetUuid}/detections/${created.uuid}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ confidence: 0.99, type: "objectdetection" }),
      });
      const updated = await updateRes.json();

      // List detections
      const listRes = await fetch(`/api/v1/assets/${assetUuid}/detections`, { headers });
      const listed = await listRes.json();

      // Delete the detection
      const deleteRes = await fetch(`/api/v1/assets/${assetUuid}/detections/${created.uuid}`, {
        method: "DELETE",
        headers,
      });

      return {
        created: { uuid: created.uuid, type: created.type, confidence: created.confidence },
        loaded: { uuid: loaded.uuid, type: loaded.type },
        updated: { confidence: updated.confidence },
        listCount: listed.data?.length ?? 0,
        deleteStatus: deleteRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    expect((result as Record<string, unknown>).created).toBeDefined();
    expect((result as Record<string, Record<string, unknown>>).created.type).toBe("objectdetection");
    expect((result as Record<string, Record<string, unknown>>).loaded.uuid).toBe(
      (result as Record<string, Record<string, unknown>>).created.uuid
    );
    expect((result as Record<string, Record<string, unknown>>).updated.confidence).toBe(0.99);
    // 204, not 200: LoomRoutingContext.sendNoContent() is what every delete route answers with.
    expect((result as Record<string, unknown>).deleteStatus).toBe(204);
  });

  test("bulk create detections via API", async ({ page }) => {
    await page.goto("/");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

    const result = await page.evaluate(async () => {
      // See the note in the CRUD test above: there is no token in localStorage to read.
      const loginRes = await fetch(`/api/v1/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;

      const headers = {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`,
      };

      const assetsRes = await fetch(`/api/v1/assets`, { headers });
      const assetsBody = await assetsRes.json();
      const assetUuid = assetsBody?.data?.[0]?.uuid;
      if (!assetUuid) return { error: "No assets found" };

      const bulkRes = await fetch(`/api/v1/assets/${assetUuid}/detections/bulk`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          detections: [
            { type: "facedetection", frameNumber: 0, bboxX: 0.1, bboxY: 0.1, bboxWidth: 0.2, bboxHeight: 0.3, confidence: 0.9 },
            { type: "objectdetection", frameNumber: 30, bboxX: 0.5, bboxY: 0.5, bboxWidth: 0.1, bboxHeight: 0.1, confidence: 0.85, meta: { label: "bulk-test" } },
            { type: "facedetection", frameNumber: 60, bboxX: 0.3, bboxY: 0.2, bboxWidth: 0.15, bboxHeight: 0.25, confidence: 0.92 },
          ],
        }),
      });
      const bulkBody = await bulkRes.json();

      return {
        total: bulkBody.total,
        created: bulkBody.created,
        failed: bulkBody.failed,
        detectionCount: bulkBody.detections?.length ?? 0,
      };
    });

    expect(result).not.toHaveProperty("error");
    expect((result as Record<string, unknown>).total).toBe(3);
    expect((result as Record<string, unknown>).created).toBe(3);
    expect((result as Record<string, unknown>).failed).toBe(0);
    expect((result as Record<string, unknown>).detectionCount).toBe(3);
  });
});

/**
 * UI-driven detection CRUD on the asset detail view's central image.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Unlike the raw-fetch tests above, this drives the real UI: it draws a bounding
 * box on the image to create a detection, edits its confidence, then navigates
 * away and back (client-side re-fetch) to prove the create + edit persisted, and
 * finally deletes it — asserting the delete also survives a round-trip.
 *
 * Manually-created detections get the default label "object" (demo detections use
 * real labels like car/person), so the test isolates its own rows by that label.
 */
const DETECTION_IMAGE_ASSET = "street-crossing.jpg";

async function loginAndGoToAssets(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Assets" }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

async function openDetectionImageAsset(page: Page) {
  const link = page.getByText(DETECTION_IMAGE_ASSET).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  await expect(page.getByTestId("asset-detections")).toBeVisible({ timeout: 10_000 });
  // Enter detection mode so the overlay + row list render.
  await page.getByTestId("detection-mode-toggle").click();
}

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

test.describe("Detections – UI-driven CRUD e2e", () => {
  test("create, edit confidence and delete a detection through the object-detection screen", async ({ page }) => {
    await loginAndGoToAssets(page);
    await openDetectionImageAsset(page);

    // Manually-created detections carry the default "object" label.
    const objectRows = page.getByTestId("detection-row").filter({ hasText: "object" });
    const initialCount = await objectRows.count();

    // ── Create: draw a box on the image ─────────────────────────────────
    await drawBox(page, 0.25, 0.25, 0.6, 0.6);
    await expect(objectRows).toHaveCount(initialCount + 1, { timeout: 10_000 });

    // ── Edit: set the confidence of the newly created detection ─────────
    const newRow = objectRows.last();
    const confidence = newRow.getByTestId("detection-confidence");
    await confidence.fill("0.5");
    await confidence.press("Enter");

    // ── Reload (client-side re-fetch) and assert create + edit persisted ─
    await page.locator('[data-testid="ArrowBackIcon"]').click();
    await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
    await openDetectionImageAsset(page);

    const persistedRows = page.getByTestId("detection-row").filter({ hasText: "object" });
    await expect(persistedRows).toHaveCount(initialCount + 1, { timeout: 10_000 });
    // The edited confidence survived the round-trip.
    await expect(persistedRows.last().getByTestId("detection-confidence")).toHaveValue("0.5", { timeout: 10_000 });

    // ── Delete and assert the deletion persists across a reload ─────────
    await persistedRows.last().getByTestId("detection-delete").click();
    await expect(page.getByTestId("detection-row").filter({ hasText: "object" }))
      .toHaveCount(initialCount, { timeout: 10_000 });

    await page.locator('[data-testid="ArrowBackIcon"]').click();
    await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
    await openDetectionImageAsset(page);
    await expect(page.getByTestId("detection-row").filter({ hasText: "object" }))
      .toHaveCount(initialCount, { timeout: 10_000 });
  });
});
