import { test, expect, Page } from "@playwright/test";

/**
 * Mocked request-mapping tests for asset CRUD in the browser.
 *
 * No running Loom backend is required: all REST calls are intercepted with
 * `page.route`. The tests drive the real UI (upload dialog, multi-select bulk
 * actions) and assert the shape/method of the outgoing requests the client
 * produces — in particular the multipart upload and the `{ assets: [...] }` bulk
 * update body.
 */

const SHA512 = "a".repeat(128);

function assetResponse() {
  return {
    uuid: "11111111-1111-1111-1111-111111111111",
    file: { filename: "photo.jpg", mimeType: "image/jpeg", size: 1024, origin: "upload" },
    hashes: { sha512: SHA512 },
    tags: [],
    status: { created: new Date(0).toISOString() },
  };
}

/** Install the baseline REST mocks. Returns a record of captured mutating requests. */
async function mockRest(page: Page) {
  const captured: { uploads: number; deletes: string[]; bulkUpdates: unknown[] } = {
    uploads: 0, deletes: [], bulkUpdates: [],
  };

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

  // Multipart upload.
  await page.route("**/api/v1/assets/upload", route => {
    captured.uploads += 1;
    route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(assetResponse()) });
  });

  // Bulk update.
  await page.route("**/api/v1/assets/bulk/update", route => {
    captured.bulkUpdates.push(route.request().postDataJSON());
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [], total: 1, created: 1, failed: 0 }) });
  });

  // Delete by uuid.
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, route => {
    if (route.request().method() === "DELETE") {
      captured.deletes.push(route.request().url());
      return route.fulfill({ status: 204, body: "" });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(assetResponse()) });
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

test.describe("Asset CRUD – mocked request mapping", () => {
  test("upload dialog sends a multipart POST to /assets/upload", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "Upload" }).first().click();
    // Set the hidden file input inside the dialog.
    await page.setInputFiles('input[type="file"]', {
      name: "photo.jpg",
      mimeType: "image/jpeg",
      buffer: Buffer.from("hello-bytes"),
    });
    // Library is preselected to the first library; submit via the dialog's Upload button.
    const [uploadReq] = await Promise.all([
      page.waitForRequest("**/api/v1/assets/upload"),
      page.getByRole("dialog").getByRole("button", { name: /upload/i }).click(),
    ]);

    expect(uploadReq.method()).toBe("POST");
    expect(uploadReq.headers()["content-type"] ?? "").toContain("multipart/form-data");
    expect(captured.uploads).toBeGreaterThan(0);
  });

  test("multi-select bulk delete issues DELETE per selected asset", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "Select" }).click();
    // Select the single asset tile via its checkbox.
    await page.getByRole("checkbox").first().click();
    await Promise.all([
      page.waitForRequest(req => req.url().includes("/api/v1/assets/") && req.method() === "DELETE"),
      page.getByRole("button", { name: "Delete" }).first().click(),
    ]);

    expect(captured.deletes.length).toBe(1);
  });

  test("bulk tag sends { assets: [{ hashes, update }] } to /assets/bulk/update", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "Select" }).click();
    await page.getByRole("checkbox").first().click();
    // Exact match: a substring "Tag" also matches the sidebar "Tags" nav button.
    await page.getByRole("button", { name: "Tag", exact: true }).first().click();
    await page.getByRole("dialog").getByRole("textbox").fill("reviewed");
    await Promise.all([
      page.waitForRequest("**/api/v1/assets/bulk/update"),
      page.getByRole("button", { name: "Apply" }).click(),
    ]);

    expect(captured.bulkUpdates.length).toBe(1);
    const body = captured.bulkUpdates[0] as { assets: { hashes: { sha512: string }; update: { meta: { tags: string[] } } }[] };
    expect(body.assets).toHaveLength(1);
    expect(body.assets[0].hashes.sha512).toBe(SHA512);
    expect(body.assets[0].update.meta.tags).toContain("reviewed");
  });
});
