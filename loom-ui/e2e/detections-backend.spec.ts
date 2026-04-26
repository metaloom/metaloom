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

    // Switch to object detection tab if needed
    const objTab = page.getByText(/object/i).first();
    if (await objTab.isVisible().catch(() => false)) {
      await objTab.click();
    }

    // Wait for detections to load — demo data has labels like car, person, building, tree
    const labels = ["car", "person", "building", "tree"];
    let foundAny = false;
    for (const label of labels) {
      const loc = page.getByText(label, { exact: false });
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
      const baseUrl = (window as unknown as Record<string, string>).__VITE_API_BASE_URL__ ||
        import.meta?.url ? "/api/v1" : "/api/v1";

      // Get token from localStorage
      const stored = localStorage.getItem("loom_auth");
      const token = stored ? JSON.parse(stored).token : null;
      if (!token) return { error: "No auth token found" };

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
    expect((result as Record<string, unknown>).deleteStatus).toBe(200);
  });

  test("bulk create detections via API", async ({ page }) => {
    await page.goto("/");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

    const result = await page.evaluate(async () => {
      const stored = localStorage.getItem("loom_auth");
      const token = stored ? JSON.parse(stored).token : null;
      if (!token) return { error: "No auth token found" };

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
