import { test, expect, Page } from "@playwright/test";

/**
 * Mocked tests for the dedicated upload screen.
 *
 * No running Loom backend is required: every REST call is intercepted with `page.route`. These
 * drive the real UI and assert what the upload queue puts on the wire (multipart body, libraryUuid,
 * poolUuid) plus the behaviour that only exists because uploads are owned by a module-level store:
 * progress survives navigating away, and a completion toast is raised once the batch drains.
 *
 * See `spec/loom/ui/LOOM_UI_UPLOAD.md`.
 */

const SHA512 = "a".repeat(128);

function assetResponse() {
  return {
    uuid: "11111111-1111-1111-1111-111111111111",
    file: { filename: "photo.jpg", mimeType: "image/jpeg", size: 11, origin: "upload" },
    hashes: { sha512: SHA512 },
    tags: [],
    status: { created: new Date(0).toISOString() },
  };
}

interface Captured {
  uploads: { contentType: string; body: string }[];
  /** Resolve to let a parked upload finish, so a test can observe the in-flight state. */
  release?: () => void;
}

/**
 * Install baseline mocks.
 *
 * @param opts.pools whether GET /pools succeeds; false answers 403, the non-operator case
 * @param opts.hold  park uploads until `captured.release()` is called
 * @param opts.status status code the upload answers with (201 new, 200 already known)
 */
async function mockRest(
  page: Page,
  opts: { pools?: boolean; hold?: boolean; status?: number } = {}
): Promise<Captured> {
  const captured: Captured = { uploads: [] };
  const gate = new Promise<void>((resolve) => { captured.release = resolve; });

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/libraries", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [
          { uuid: "lib-1", name: "Main Library", poolUuid: "pool-1", storageType: "filesystem" },
          { uuid: "lib-2", name: "Second Library" },
        ],
      }),
    })
  );

  await page.route("**/api/v1/pools", route => {
    if (opts.pools === false) {
      // What a caller without READ_ASSET_POOL sees. The pool selector must simply not appear.
      return route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ message: "Invalid permissions" }) });
    }
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [
          { uuid: "pool-1", name: "Production", fsPath: "/tank/loom" },
          { uuid: "pool-2", name: "Archive S3", s3Bucket: "metaloom-archive" },
        ],
      }),
    });
  });

  await page.route("**/api/v1/assets/upload", async route => {
    captured.uploads.push({
      contentType: route.request().headers()["content-type"] ?? "",
      body: route.request().postData() ?? "",
    });
    if (opts.hold) await gate;
    route.fulfill({
      status: opts.status ?? 201,
      contentType: "application/json",
      body: JSON.stringify(assetResponse()),
    });
  });

  return captured;
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function gotoUploads(page: Page) {
  await login(page);
  await page.getByTestId("sidebar-item-/uploads").click();
  await expect(page.getByTestId("upload-view")).toBeVisible({ timeout: 10_000 });
}

function fileOf(name: string) {
  return { name, mimeType: "image/jpeg", buffer: Buffer.from("hello-bytes") };
}

test.describe("Upload view – mocked", () => {
  test("uploads several files in one go, one multipart request each", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([
      fileOf("one.jpg"), fileOf("two.jpg"), fileOf("three.jpg"),
    ]);

    await expect(page.getByTestId("upload-row-one.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-three.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });

    expect(captured.uploads).toHaveLength(3);
    for (const upload of captured.uploads) {
      expect(upload.contentType).toContain("multipart/form-data");
      expect(upload.body).toContain("lib-1");
    }
  });

  test("sends the chosen pool as poolUuid", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-pool-select").click();
    await page.getByRole("option", { name: "Archive S3" }).click();
    await page.getByTestId("upload-file-input").setInputFiles([fileOf("pooled.jpg")]);

    await expect(page.getByTestId("upload-row-pooled.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });

    expect(captured.uploads[0].body).toContain('name="poolUuid"');
    expect(captured.uploads[0].body).toContain("pool-2");
  });

  test("omits poolUuid entirely when the library decides", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("plain.jpg")]);
    await expect(page.getByTestId("upload-row-plain.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });

    // "From library" is the default, and it must not put an empty field on the wire.
    expect(captured.uploads[0].body).not.toContain('name="poolUuid"');
  });

  test("hides the pool selector when pools are not readable", async ({ page }) => {
    await mockRest(page, { pools: false });
    await gotoUploads(page);

    // Pools are an operator concept; a 403 means this caller never sees the override.
    await expect(page.getByTestId("upload-pool-select")).toHaveCount(0);
    // The library selector is still there — uploading itself is not gated on pools.
    await expect(page.getByTestId("upload-library-select")).toBeVisible();
  });

  test("shows the effective pool derived from the selected library", async ({ page }) => {
    await mockRest(page);
    await gotoUploads(page);

    await expect(page.getByTestId("upload-effective-pool")).toContainText("Production");
  });

  test("keeps uploading after navigating away and shows progress in the sidebar", async ({ page }) => {
    const captured = await mockRest(page, { hold: true });
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("slow.jpg")]);
    await expect(page.getByTestId("upload-row-slow.jpg")).toHaveAttribute("data-status", "uploading", { timeout: 10_000 });

    // Leave the upload screen entirely. The transfer lives in a module-level store, not in the view.
    await page.getByTestId("sidebar-item-/assets").click();
    await expect(page.getByTestId("upload-view")).toHaveCount(0);
    await expect(page.getByTestId("sidebar-upload-progress")).toBeVisible();

    captured.release!();

    // Coming back shows the finished item, so the queue really did outlive the unmount.
    await page.getByTestId("sidebar-item-/uploads").click();
    await expect(page.getByTestId("upload-row-slow.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
  });

  test("raises a success toast once the batch completes", async ({ page }) => {
    await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("toasty.jpg")]);

    await expect(page.getByRole("alert")).toContainText(/uploaded/i, { timeout: 10_000 });
  });

  test("marks content the server already holds as a duplicate rather than a failure", async ({ page }) => {
    // 200 instead of 201 is how the upload route reports known SHA-512 content.
    await mockRest(page, { status: 200 });
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("dupe.jpg")]);

    await expect(page.getByTestId("upload-row-dupe.jpg")).toHaveAttribute("data-status", "duplicate", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-dupe.jpg")).toContainText(/already in loom/i);
  });

  test("reports a failed upload and offers a retry", async ({ page }) => {
    await mockRest(page);
    await page.route("**/api/v1/assets/upload", route =>
      route.fulfill({ status: 507, contentType: "application/json", body: JSON.stringify({ message: "Not enough space" }) })
    );
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("toobig.jpg")]);

    await expect(page.getByTestId("upload-row-toobig.jpg")).toHaveAttribute("data-status", "error", { timeout: 10_000 });
    await expect(page.getByTestId("upload-retry-toobig.jpg")).toBeVisible();
    await expect(page.getByRole("alert")).toContainText(/failed/i);
  });

  test("cancels an in-flight upload", async ({ page }) => {
    const captured = await mockRest(page, { hold: true });
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("cancelme.jpg")]);
    await expect(page.getByTestId("upload-row-cancelme.jpg")).toHaveAttribute("data-status", "uploading", { timeout: 10_000 });

    await page.getByTestId("upload-cancel-cancelme.jpg").click();

    await expect(page.getByTestId("upload-row-cancelme.jpg")).toHaveAttribute("data-status", "cancelled", { timeout: 10_000 });
    captured.release!();
  });

  test("clears finished items from the queue", async ({ page }) => {
    await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([fileOf("gone.jpg")]);
    await expect(page.getByTestId("upload-row-gone.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });

    await page.getByTestId("upload-clear-finished").click();

    await expect(page.getByTestId("upload-row-gone.jpg")).toHaveCount(0);
    await expect(page.getByTestId("upload-empty")).toBeVisible();
  });
});
