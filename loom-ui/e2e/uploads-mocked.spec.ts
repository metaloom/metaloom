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
 * @param opts.statusFor per-request status picked from the multipart body, so one batch can mix
 *                       success and failure (the multipart part carries `filename="…"`)
 */
async function mockRest(
  page: Page,
  opts: { pools?: boolean; hold?: boolean; status?: number; statusFor?: (body: string) => number } = {}
): Promise<Captured> {
  const captured: Captured = { uploads: [] };
  const gate = new Promise<void>((resolve) => { captured.release = resolve; });

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route(/\/api\/v1\/libraries(\?|$)/, route =>
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

  await page.route(/\/api\/v1\/pools(\?|$)/, route => {
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
    const body = route.request().postData() ?? "";
    captured.uploads.push({
      contentType: route.request().headers()["content-type"] ?? "",
      body,
    });
    if (opts.hold) await gate;
    const status = opts.statusFor ? opts.statusFor(body) : opts.status ?? 201;
    try {
      await route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(status >= 400 ? { message: "Not enough space" } : assetResponse()),
      });
    } catch {
      // A cancelled upload aborts the request while this handler is parked on the gate; answering a
      // request that no longer exists is the expected outcome there, not a test failure.
    }
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

function fileOf(name: string, bytes = 11) {
  return { name, mimeType: "image/jpeg", buffer: Buffer.alloc(bytes, "x") };
}

/**
 * Build a `DataTransfer` inside the page.
 *
 * Playwright has no file-drag API, and `setInputFiles` drives the `<input type=file>` branch of the
 * component — a different code path from `UploadView`'s `onDrop`, which reads `e.dataTransfer.files`.
 * A synthetic `DataTransfer` handed to `dispatchEvent` is the only way to reach it.
 *
 * `files` become real `File` objects; `uris` become `text/uri-list` items, which is how a drop can
 * carry something that is *not* a file.
 */
async function buildDataTransfer(page: Page, spec: { files?: { name: string; type: string }[]; uris?: string[] }) {
  return page.evaluateHandle(({ files, uris }) => {
    const dt = new DataTransfer();
    for (const f of files ?? []) {
      dt.items.add(new File(["hello-bytes"], f.name, { type: f.type }));
    }
    for (const uri of uris ?? []) {
      dt.items.add(uri, "text/uri-list");
    }
    return dt;
  }, spec);
}

/** Background + border of the dropzone, which is the only rendering of the `dragging` state. */
function dropzoneStyle(page: Page) {
  return page.getByTestId("upload-dropzone").evaluate(el => {
    const style = getComputedStyle(el);
    return `${style.backgroundColor}|${style.borderColor}`;
  });
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

  test("accepts files dropped on the dropzone, highlighting it while the drag is over it", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    const zone = page.getByTestId("upload-dropzone");
    const idle = await dropzoneStyle(page);

    const dataTransfer = await buildDataTransfer(page, {
      files: [{ name: "dropped-one.jpg", type: "image/jpeg" }, { name: "dropped-two.jpg", type: "image/jpeg" }],
    });

    // `onDragOver` sets `dragging`, which is the dropzone's primary affordance — the user has to be
    // told the drop will land somewhere before they let go.
    await zone.dispatchEvent("dragover", { dataTransfer });
    await expect.poll(() => dropzoneStyle(page), { timeout: 5_000 }).not.toBe(idle);

    await zone.dispatchEvent("drop", { dataTransfer });
    // The highlight must clear on drop, not linger until the next pointer move.
    await expect.poll(() => dropzoneStyle(page), { timeout: 5_000 }).toBe(idle);

    // Same contract as the file-input path: one multipart request per dropped file.
    await expect(page.getByTestId("upload-row-dropped-one.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-dropped-two.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });

    expect(captured.uploads).toHaveLength(2);
    for (const upload of captured.uploads) {
      expect(upload.contentType).toContain("multipart/form-data");
      expect(upload.body).toContain("lib-1");
    }
  });

  test("ignores a drop that carries no files, and keeps working afterwards", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    const zone = page.getByTestId("upload-dropzone");

    // Folder upload is deliberately unbuilt (LOOM_UI_UPLOAD.md §1.2): `onDrop` reads
    // `dataTransfer.files` and knows nothing about directory entries. A synthetic DataTransfer
    // cannot carry a real filesystem directory, so what is pinned here is the observable
    // consequence — a drop whose `files` list is empty enqueues nothing and does not throw. If
    // folder support is ever half-added, this is the case that has to change with it.
    const dataTransfer = await buildDataTransfer(page, { uris: ["file:///home/user/holiday-photos"] });
    await zone.dispatchEvent("dragover", { dataTransfer });
    await zone.dispatchEvent("drop", { dataTransfer });

    await expect(page.getByTestId("upload-empty")).toBeVisible();
    expect(captured.uploads).toHaveLength(0);

    // "No crash" means the screen is still usable, not merely that nothing was queued.
    await page.getByTestId("upload-file-input").setInputFiles([fileOf("after-folder.jpg")]);
    await expect(page.getByTestId("upload-row-after-folder.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
  });

  test("sends a custom origin as the origin form field", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-origin-input").locator("input").fill("field-import");
    await page.getByTestId("upload-file-input").setInputFiles([fileOf("sourced.jpg")]);

    await expect(page.getByTestId("upload-row-sourced.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
    expect(captured.uploads[0].body).toContain('name="origin"');
    expect(captured.uploads[0].body).toContain("field-import");
  });

  test("omits the origin field when it is left blank", async ({ page }) => {
    const captured = await mockRest(page);
    await gotoUploads(page);

    await page.getByTestId("upload-origin-input").locator("input").fill("");
    await page.getByTestId("upload-file-input").setInputFiles([fileOf("unsourced.jpg")]);

    await expect(page.getByTestId("upload-row-unsourced.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
    // Absent, not empty — the server supplies its own default of "upload".
    expect(captured.uploads[0].body).not.toContain('name="origin"');
  });

  test("the queue heading and totals track the batch, weighted by size", async ({ page }) => {
    const captured = await mockRest(page, { hold: true });
    await gotoUploads(page);

    // Three small files saturate MAX_CONCURRENT; the big one stays queued. Its bytes still count in
    // the denominator — that is what "weighted by size" means. Counting items instead would read
    // three-quarters sent while three-quarters of the bytes have not left yet.
    await page.getByTestId("upload-file-input").setInputFiles([
      fileOf("s1.jpg", 1000), fileOf("s2.jpg", 1000), fileOf("s3.jpg", 1000), fileOf("big.jpg", 9000),
    ]);

    const heading = page.getByTestId("upload-queue-heading");
    await expect(heading).toContainText("0 of 4 done", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-big.jpg")).toHaveAttribute("data-status", "queued");

    const percent = Number(/\((\d+)%\)/.exec((await heading.textContent()) ?? "")?.[1] ?? "-1");
    expect(percent).toBeGreaterThanOrEqual(0);
    expect(percent).toBeLessThan(50);

    captured.release!();

    await expect(heading).toHaveText("4 file(s) in this session", { timeout: 10_000 });
    await expect(page.getByTestId("upload-totals"))
      .toHaveText("4 uploaded · 0 already known · 0 failed · 12 KB total");
  });

  test("cancel all ends every in-flight upload as cancelled, not failed", async ({ page }) => {
    const captured = await mockRest(page, { hold: true });
    await gotoUploads(page);

    const names = ["c1.jpg", "c2.jpg", "c3.jpg"];
    await page.getByTestId("upload-file-input").setInputFiles(names.map(n => fileOf(n)));
    for (const name of names) {
      await expect(page.getByTestId(`upload-row-${name}`)).toHaveAttribute("data-status", "uploading", { timeout: 10_000 });
    }

    await page.getByTestId("upload-cancel-all").click();

    for (const name of names) {
      // A cancelled XHR can raise both `onabort` and `onerror`; none of these may read as a failure.
      await expect(page.getByTestId(`upload-row-${name}`)).toHaveAttribute("data-status", "cancelled", { timeout: 10_000 });
    }
    await expect(page.getByTestId("upload-totals")).toContainText("0 failed");
    await expect(page.getByRole("alert")).toContainText(/cancelled/i);

    captured.release!();
  });

  test("retry failed re-sends the failures and nothing else", async ({ page }) => {
    let failing = true;
    const captured = await mockRest(page, {
      statusFor: body => (failing && body.includes('filename="fail-') ? 507 : 201),
    });
    await gotoUploads(page);

    await page.getByTestId("upload-file-input").setInputFiles([
      fileOf("fail-a.jpg"), fileOf("fail-b.jpg"), fileOf("ok.jpg"),
    ]);

    await expect(page.getByTestId("upload-row-fail-a.jpg")).toHaveAttribute("data-status", "error", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-fail-b.jpg")).toHaveAttribute("data-status", "error", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-ok.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
    expect(captured.uploads).toHaveLength(3);

    failing = false;
    await page.getByTestId("upload-retry-failed").click();

    await expect(page.getByTestId("upload-row-fail-a.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });
    await expect(page.getByTestId("upload-row-fail-b.jpg")).toHaveAttribute("data-status", "done", { timeout: 10_000 });

    // Exactly two more requests: a bulk retry must not re-upload bytes the server already took.
    expect(captured.uploads).toHaveLength(5);
    expect(captured.uploads.filter(u => u.body.includes('filename="ok.jpg"'))).toHaveLength(1);
    await expect(page.getByTestId("upload-totals")).toContainText("3 uploaded");
    await expect(page.getByTestId("upload-totals")).toContainText("0 failed");
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
