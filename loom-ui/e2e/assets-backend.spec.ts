import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for Asset List and Asset Detail views.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 */

/** Login and navigate to the assets view using sidebar (client-side navigation preserves auth state). */
async function loginAndGoToAssets(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  // Wait for login to complete (login form disappears, sidebar becomes visible)
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // Navigate via sidebar link — avoids full page reload which would lose in-memory auth state
  await page.getByRole("button", { name: "Assets" }).first().click();
  // Wait for the heading in the main content area
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

/** Log in and open the detail view for the given demo asset (fresh backend read of its tags). */
async function loginAndOpenAsset(page: Page, filename: string) {
  await loginAndGoToAssets(page);
  const assetLink = page.getByText(filename).first();
  await expect(assetLink).toBeVisible({ timeout: 10_000 });
  await assetLink.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  // The editable tag row is the source of truth for persisted tags.
  await expect(page.getByTestId("asset-tags")).toBeVisible({ timeout: 5_000 });
}

test.describe("Assets – full backend e2e", () => {
  test("asset list loads and displays assets from the API", async ({ page }) => {
    await loginAndGoToAssets(page);

    // Wait for data to load — expect a non-zero asset count
    // By testid, not by text: a remix card in the band above the grid also reads "<n> assets", and
    // the demo's remix has three members.
    await expect(page.getByTestId("assets-count")).toHaveText(/^[1-9]\d* assets$/, { timeout: 10_000 });

    // Verify that at least one asset name from the demo data is rendered
    const assetNames = [
      "street-crossing.jpg",
      "misty-forest-path.jpg",
      "curved-architecture.jpg",
      "team-meeting.mp4",
      "ambient-rain.mp3",
      "space-brief.pdf",
    ];

    let foundAny = false;
    for (const name of assetNames) {
      const loc = page.getByText(name);
      if (await loc.isVisible().catch(() => false)) {
        foundAny = true;
        break;
      }
    }
    expect(foundAny).toBe(true);
  });

  test("clicking an asset navigates to asset detail", async ({ page }) => {
    await loginAndGoToAssets(page);

    const assetLink = page.getByText("street-crossing.jpg").first();
    await expect(assetLink).toBeVisible({ timeout: 10_000 });
    await assetLink.click();

    // URL should contain /assets/ followed by a UUID
    await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });

    // The name is an editable field in the header, not a label — `getByText` never matches it.
    await expect(page.getByTestId("asset-name")).toHaveValue("street-crossing.jpg", { timeout: 5_000 });
  });

  test("asset detail view shows file metadata", async ({ page }) => {
    await loginAndGoToAssets(page);

    const assetLink = page.getByText("street-crossing.jpg").first();
    await expect(assetLink).toBeVisible({ timeout: 10_000 });
    await assetLink.click();
    await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });

    // Verify metadata is displayed — file size, mime type, or filename
    await expect(page.getByTestId("asset-name")).toHaveValue("street-crossing.jpg", { timeout: 5_000 });

    // The detail view should render (check for Back button or tabs)
    const backButton = page.locator('[data-testid="ArrowBackIcon"]');
    await expect(backButton).toBeVisible({ timeout: 5_000 });
  });

  test("asset list search asks the server, not a local array", async ({ page }) => {
    await loginAndGoToAssets(page);

    // Wait for assets to load first
    // By testid, not by text: a remix card in the band above the grid also reads "<n> assets", and
    // the demo's remix has three members.
    await expect(page.getByTestId("assets-count")).toHaveText(/^[1-9]\d* assets$/, { timeout: 10_000 });

    // The box used to filter whatever the first page happened to hold. Assert the request goes
    // out — a passing "team-meeting.mp4 is visible" alone cannot tell the two apart.
    const searchRequest = page.waitForRequest(
      req => req.url().includes("/search/assets") && req.url().includes("q=meeting"),
      { timeout: 10_000 },
    );

    const searchInput = page.getByPlaceholder("Search assets, tags…");
    await searchInput.fill("meeting");

    await searchRequest;
    await expect(page.getByText("team-meeting.mp4")).toBeVisible({ timeout: 10_000 });
  });

  test("the asset list requests a page rather than taking the server default of 25", async ({ page }) => {
    const listRequest = page.waitForRequest(
      req => /\/api\/v1\/assets\?/.test(req.url()) && req.url().includes("limit="),
      { timeout: 15_000 },
    );

    await loginAndGoToAssets(page);

    const request = await listRequest;
    expect(new URL(request.url()).searchParams.get("limit")).toBe("100");
  });

  test("the header count comes from the server total", async ({ page }) => {
    await loginAndGoToAssets(page);

    // `_metainfo.totalCount` is the collection size; the grid may hold fewer rows than that.
    const count = page.getByTestId("assets-count");
    await expect(count).toHaveText(/^[1-9]\d* assets$/, { timeout: 10_000 });
  });

  test("asset create → edit → delete via API", async ({ page }) => {
    // Load the app so relative /api/v1 fetches hit the Vite proxy → backend.
    await page.goto("/");

    // A valid 128-hex SHA-512 (SHA512.fromString requires exactly 128 hex chars). The hash is an
    // asset's content identity, so a fixed one collides with the row a previous run left behind and
    // the create fails - unique per run keeps the test repeatable against a persistent backend.
    const unique = `${Date.now().toString(16)}${Math.floor(Math.random() * 0xffffffff).toString(16)}`;
    const sha512 = (unique + "b".repeat(128)).slice(0, 128);

    const result = await page.evaluate(async (sha) => {
      // Authenticate directly against the REST API to obtain a bearer token.
      const loginRes = await fetch(`/api/v1/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;
      const headers = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };

      // Create (metadata) — mandatory: file.{filename,mimeType,origin,size} + hashes.sha512.
      const createRes = await fetch(`/api/v1/assets`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          file: { filename: "e2e-created.bin", mimeType: "application/octet-stream", origin: "e2e", size: 5 },
          hashes: { sha512: sha },
        }),
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "create failed", created };

      // Edit metadata (partial update). The filename lives under "file", the same shape the create above
      // uses and the only one the server reads - a top level "filename" is accepted by the lenient mapper
      // and then silently dropped.
      const updateRes = await fetch(`/api/v1/assets/${created.uuid}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ file: { filename: "e2e-renamed.bin" }, meta: { reviewed: true } }),
      });
      const updated = await updateRes.json();

      // Load to confirm the rename stuck.
      const loadRes = await fetch(`/api/v1/assets/${created.uuid}`, { headers });
      const loaded = await loadRes.json();

      // Delete.
      const deleteRes = await fetch(`/api/v1/assets/${created.uuid}`, { method: "DELETE", headers });

      return {
        createdUuid: created.uuid,
        updatedFilename: updated.file?.filename,
        loadedFilename: loaded.file?.filename,
        deleteStatus: deleteRes.status,
      };
    }, sha512);

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.createdUuid).toBeTruthy();
    expect(r.loadedFilename).toBe("e2e-renamed.bin");
    expect([200, 204]).toContain(r.deleteStatus);
  });

  test("asset binary upload → preview → delete via API", async ({ page }) => {
    // Load the app so relative /api/v1 fetches hit the Vite proxy → backend.
    await page.goto("/");

    const result = await page.evaluate(async () => {
      const loginRes = await fetch(`/api/v1/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;
      const jsonHeaders = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };
      const authHeader = { Authorization: `Bearer ${token}` };

      // A binary needs a library to associate its new row with on first upload.
      //
      // Not just data[0]: the library decides the storage pool, the demo binds "Archive Footage" to an S3
      // pool whose bucket does not exist, and the listing has no deterministic order - so taking the first
      // row uploaded into S3 on some runs and failed with a credentials error. This test asserts a
      // filesystem path below, so it wants a library on local storage, meaning one with no pool.
      const libsRes = await fetch(`/api/v1/libraries`, { headers: jsonHeaders });
      const libraries = ((await libsRes.json())?.data ?? []) as Array<{ uuid: string; poolUuid?: string | null }>;
      const libraryUuid = libraries.find(l => !l.poolUuid)?.uuid;
      if (!libraryUuid) return { error: "no local-storage library found", libraries };

      // A per-run unique 128-hex SHA-512 (Date.now hex, zero-padded) avoids collisions
      // with assets left behind by a previous failed run.
      const sha512 = Date.now().toString(16).padStart(128, "0");

      // The filename has to be unique for the same reason the hash does, and it is a separate
      // constraint: uploading stores an asset_location keyed on (library, path), so a fixed name lands
      // in the same library slot every run and the second upload is a duplicate.
      const filename = `e2e-binary-${Date.now().toString(16)}.bin`;

      // The bytes have to differ too. The stored path is derived from the content digest, not from the
      // filename, so re-uploading identical bytes lands on the same path and trips the same constraint
      // however the file is named.
      const payload = `hello-bytes-${Date.now().toString(16)}`;

      // Create the asset (metadata only) that will receive the binary bytes.
      const createRes = await fetch(`/api/v1/assets`, {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({
          file: { filename, mimeType: "application/octet-stream", origin: "e2e", size: payload.length },
          hashes: { sha512 },
        }),
      });
      const asset = await createRes.json();
      if (!asset.uuid) return { error: "asset create failed", asset };

      // Upload raw bytes as multipart. No Content-Type header — the browser sets the boundary.
      const bytes = new TextEncoder().encode(payload);
      const form = new FormData();
      form.append("file", new Blob([bytes], { type: "application/octet-stream" }), filename);
      form.append("libraryUuid", libraryUuid);
      const uploadRes = await fetch(`/api/v1/assets/${asset.uuid}/binary/data`, {
        method: "POST",
        headers: authHeader,
        body: form,
      });
      const uploaded = await uploadRes.json();

      // Load the binary metadata row (records where the bytes live on disk).
      const metaRes = await fetch(`/api/v1/assets/${asset.uuid}/binary`, { headers: jsonHeaders });
      const meta = await metaRes.json();

      // Preview / download the raw bytes back and confirm they match what was uploaded.
      const previewRes = await fetch(`/api/v1/assets/${asset.uuid}/binary/data`, { headers: authHeader });
      const previewText = previewRes.ok ? new TextDecoder().decode(new Uint8Array(await previewRes.arrayBuffer())) : "";

      // Delete the binary, then the asset itself, then confirm the binary is gone.
      const deleteBinaryRes = await fetch(`/api/v1/assets/${asset.uuid}/binary`, { method: "DELETE", headers: authHeader });
      const afterBinaryRes = await fetch(`/api/v1/assets/${asset.uuid}/binary/data`, { headers: authHeader });
      const deleteAssetRes = await fetch(`/api/v1/assets/${asset.uuid}`, { method: "DELETE", headers: authHeader });

      return {
        assetUuid: asset.uuid,
        payload,
        uploadStatus: uploadRes.status,
        uploadedBinaryUuid: uploaded.uuid,
        binaryPath: meta?.filesystem?.path,
        previewStatus: previewRes.status,
        previewText,
        deleteBinaryStatus: deleteBinaryRes.status,
        afterBinaryStatus: afterBinaryRes.status,
        deleteAssetStatus: deleteAssetRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.assetUuid).toBeTruthy();
    expect([200, 201]).toContain(r.uploadStatus);
    expect(r.uploadedBinaryUuid).toBeTruthy();
    expect(r.binaryPath).toBeTruthy();
    expect(r.previewStatus).toBe(200);
    expect(r.previewText).toBe(r.payload);
    expect([200, 204]).toContain(r.deleteBinaryStatus);
    // The binary bytes must be gone after the delete.
    expect(r.afterBinaryStatus).toBe(404);
    expect([200, 204]).toContain(r.deleteAssetStatus);
  });

  test("tag an asset from the detail view and verify add/remove persist across reload", async ({ page }) => {
    const TAG_NAME = "pw-asset-tag";
    const assetTags = page.getByTestId("asset-tags");
    const tagChip = assetTags.getByTestId("tag-chip").filter({ hasText: TAG_NAME });

    // Open the asset and add a tag via the editable tag input (Enter submits).
    await loginAndOpenAsset(page, "street-crossing.jpg");
    await assetTags.getByTestId("tag-input").fill(TAG_NAME);
    await assetTags.getByTestId("tag-input").press("Enter");

    // reloadTags() re-fetches the asset, so a visible chip means the POST persisted.
    await expect(tagChip).toBeVisible({ timeout: 10_000 });

    // Reload from the backend (re-login drops in-memory auth) and confirm it stuck.
    await loginAndOpenAsset(page, "street-crossing.jpg");
    await expect(tagChip).toBeVisible({ timeout: 10_000 });

    // Remove the tag via the chip's delete icon; the row refreshes from the backend.
    await tagChip.locator(".MuiChip-deleteIcon").click();
    await expect(tagChip).toBeHidden({ timeout: 10_000 });

    // Reload again and confirm the removal persisted.
    await loginAndOpenAsset(page, "street-crossing.jpg");
    await expect(tagChip).toBeHidden({ timeout: 10_000 });
  });
});
