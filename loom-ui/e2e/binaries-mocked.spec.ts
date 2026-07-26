import { test, expect, Page } from "@playwright/test";

/**
 * Mocked request-mapping tests for asset binary upload / download / remove.
 *
 * No running Loom backend is required: all REST calls are intercepted with
 * `page.route`. The tests drive the real Asset Detail UI and assert the shape of
 * the outgoing binary requests — the multipart upload to `/binary/data`, the raw
 * GET download, and the DELETE of `/binary`.
 */

const ASSET_UUID = "11111111-1111-1111-1111-111111111111";
const SHA512 = "a".repeat(128);

function assetResponse() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "photo.jpg", mimeType: "image/jpeg", size: 1024, origin: "upload" },
    hashes: { sha512: SHA512 },
    tags: [],
    status: { created: new Date(0).toISOString() },
  };
}

function binaryResponse() {
  return {
    uuid: "22222222-2222-2222-2222-222222222222",
    assetUuid: ASSET_UUID,
    libraryUuid: "lib-1",
    filesystem: { path: "/storage/ab/cd/ef/photo.jpg" },
  };
}

interface Captured {
  binaryUploads: number;
  binaryUploadContentTypes: string[];
  binaryDownloads: number;
  binaryDeletes: number;
  binaryMetaCreates: number;
  binaryMetaCreateBodies: unknown[];
}

/** Install baseline + binary REST mocks. Returns a record of captured binary requests. */
async function mockRest(page: Page): Promise<Captured> {
  const captured: Captured = { binaryUploads: 0, binaryUploadContentTypes: [], binaryDownloads: 0, binaryDeletes: 0, binaryMetaCreates: 0, binaryMetaCreateBodies: [] };

  // Catch-all first (lowest priority): empty lists for anything unmatched.
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/libraries", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [{ uuid: "lib-1", name: "Main Library" }] }) })
  );

  // Asset list (exact path, no trailing segments).
  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [assetResponse()] }) })
  );

  // Load a single asset by uuid (asset detail).
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(assetResponse()) })
  );

  // Binary metadata route (GET / DELETE) — registered before /binary/data so the
  // more specific data route (added later, higher priority) still wins for uploads/downloads.
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+\/binary$/, route => {
    const req = route.request();
    if (req.method() === "DELETE") {
      captured.binaryDeletes += 1;
      return route.fulfill({ status: 204, body: "" });
    }
    // POST = register binary metadata (JSON), no bytes streamed.
    if (req.method() === "POST") {
      captured.binaryMetaCreates += 1;
      try { captured.binaryMetaCreateBodies.push(req.postDataJSON()); } catch { /* non-JSON */ }
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(binaryResponse()) });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(binaryResponse()) });
  });

  // Raw binary bytes route (POST upload / GET download).
  await page.route("**/api/v1/assets/*/binary/data", route => {
    const req = route.request();
    if (req.method() === "POST") {
      captured.binaryUploads += 1;
      captured.binaryUploadContentTypes.push(req.headers()["content-type"] ?? "");
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(binaryResponse()) });
    }
    // GET download.
    captured.binaryDownloads += 1;
    return route.fulfill({ status: 200, contentType: "application/octet-stream", body: Buffer.from("raw-bytes") });
  });

  return captured;
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Assets" }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

async function openAssetDetail(page: Page) {
  await page.getByText("photo.jpg").first().click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 10_000 });
}

test.describe("Asset binary – mocked request mapping", () => {
  test("upload sends a multipart POST to /binary/data, then remove issues a DELETE", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openAssetDetail(page);

    // Upload a binary by setting the (always-present, hidden) detail file input.
    const [uploadReq] = await Promise.all([
      page.waitForRequest(req => req.url().includes("/binary/data") && req.method() === "POST"),
      page.setInputFiles('input[type="file"]', {
        name: "clip.mp4",
        mimeType: "video/mp4",
        buffer: Buffer.from("hello-bytes"),
      }),
    ]);

    expect(uploadReq.method()).toBe("POST");
    expect(uploadReq.headers()["content-type"] ?? "").toContain("multipart/form-data");
    expect(captured.binaryUploads).toBe(1);

    // Remove the binary via the actions menu.
    await page.locator('[data-testid="MoreVertOutlinedIcon"]').click();
    await Promise.all([
      page.waitForRequest(req => /\/binary$/.test(req.url()) && req.method() === "DELETE"),
      page.getByRole("menuitem", { name: /remove binary/i }).click(),
    ]);

    expect(captured.binaryDeletes).toBe(1);
  });

  test("register existing binary sends a JSON POST to /binary (no bytes)", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openAssetDetail(page);

    // Open the actions menu and pick "Register existing binary".
    await page.locator('[data-testid="MoreVertOutlinedIcon"]').click();
    await page.getByTestId("asset-register-binary-menu-item").click();

    // Enter a filesystem path and submit.
    await page.getByTestId("asset-register-binary-path-input").fill("/storage/ab/cd/photo.jpg");
    const [createReq] = await Promise.all([
      page.waitForRequest(req => /\/binary$/.test(req.url()) && req.method() === "POST"),
      page.getByTestId("asset-register-binary-submit-button").click(),
    ]);

    expect(createReq.method()).toBe("POST");
    expect(createReq.headers()["content-type"] ?? "").toContain("application/json");
    expect(captured.binaryMetaCreates).toBe(1);
    expect(captured.binaryMetaCreateBodies[0]).toMatchObject({
      filesystem: { path: "/storage/ab/cd/photo.jpg" },
    });
  });

  test("download button issues a GET to /binary/data", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openAssetDetail(page);

    // The image preview hits the same route (it *is* the stored binary), so count from the
    // baseline rather than asserting an absolute total.
    const before = captured.binaryDownloads;
    await Promise.all([
      page.waitForRequest(req => req.url().includes("/binary/data") && req.method() === "GET"),
      page.locator('[data-testid="DownloadOutlinedIcon"]').click(),
    ]);

    expect(captured.binaryDownloads).toBe(before + 1);
  });
});
