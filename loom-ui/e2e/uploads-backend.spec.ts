import { test, expect, Page } from "@playwright/test";
import { deflateSync } from "node:zlib";
import { createHash } from "node:crypto";

/**
 * End-to-end tests that move **real bytes** through the upload screen.
 *
 * `uploads-mocked.spec.ts` proves what the UI puts on the wire, but it validates that against a mock
 * written by the same hand as the code. This file is the other half: a generated PNG goes in through
 * `POST /assets/upload`, and the asset is read back from the server by its SHA-512.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – set to `/api/v1`, so previews are same-origin (LOOM_UI.md §5). An `<img>`
 *                        cannot carry an Authorization header, so the thumbnail authenticates with
 *                        the HttpOnly login cookie. Locally the default absolute base happens to
 *                        work too — `localhost:3000` and `localhost:8092` are the same *site*, and
 *                        `SameSite=Strict` is site-scoped, not port-scoped — but a real cross-site
 *                        API host yields 401s and placeholder icons, which is the case this
 *                        configuration is here to keep the suite honest about.
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * See `spec/loom/ui/LOOM_UI_UPLOAD.md` §8.4.
 */

// ── A generated PNG ───────────────────────────────────────────────────

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf: Buffer): number {
  let c = -1;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "latin1"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

/**
 * A real, browser-decodable PNG whose pixels derive from `seed`.
 *
 * The bytes must be new on every run: the upload endpoint keys assets by SHA-512, so a fixed fixture
 * would come back as a *duplicate* the second time this suite is executed and the "new upload" test
 * would fail for a reason that has nothing to do with the UI.
 */
function generatePng(seed: number, side = 48): { bytes: Buffer; sha512: string } {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(side, 0);
  ihdr.writeUInt32BE(side, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type 2: truecolour RGB, no palette
  // bytes 10-12 stay zero: deflate compression, adaptive filtering, no interlace

  const stride = 1 + side * 3;
  const raw = Buffer.alloc(side * stride);
  for (let y = 0; y < side; y++) {
    const row = y * stride;
    raw[row] = 0; // per-scanline filter type: none
    for (let x = 0; x < side; x++) {
      const p = row + 1 + x * 3;
      raw[p] = (x * 5 + seed) & 0xff;
      raw[p + 1] = (y * 5 + (seed >>> 8)) & 0xff;
      raw[p + 2] = (seed >>> 16) & 0xff;
    }
  }

  const bytes = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", deflateSync(raw)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
  return { bytes, sha512: createHash("sha512").update(bytes).digest("hex") };
}

// ── Server helpers, run inside the page so they share its origin ──────

/** Log in against the REST API and return a bearer token for direct server reads. */
async function apiToken(page: Page): Promise<string> {
  const token = await page.evaluate(async () => {
    const res = await fetch("/api/v1/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "admin", password: "finger" }),
    });
    return res.ok ? ((await res.json()).token as string) : "";
  });
  expect(token, "REST login failed — is the backend up on VITE_PROXY_TARGET?").toBeTruthy();
  return token;
}

/** `GET /assets/sha512/:hash` — the content-addressed lookup, or null while nothing is stored. */
async function assetByHash(page: Page, token: string, sha512: string) {
  return page.evaluate(async ({ token, sha512 }) => {
    const res = await fetch(`/api/v1/assets/sha512/${sha512}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    return res.ok ? await res.json() : null;
  }, { token, sha512 });
}

async function deleteAsset(page: Page, token: string, uuid: string) {
  await page.evaluate(async ({ token, uuid }) => {
    await fetch(`/api/v1/assets/${uuid}`, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
  }, { token, uuid });
}

// ── UI helpers ────────────────────────────────────────────────────────

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/**
 * A demo library whose bytes land on the server's local disk.
 *
 * The selector defaults to the first library, which in the demo data is *Archive Footage* — and that
 * one resolves to an S3 pool the demo container has no credentials for, so an upload there is a 500
 * about the environment rather than anything the UI did. Choosing the library explicitly also
 * exercises `upload-library-select`, which the default never does.
 */
const LIBRARY = "Campaign Media";

async function gotoUploads(page: Page) {
  await page.getByTestId("sidebar-item-/uploads").click();
  await expect(page.getByTestId("upload-view")).toBeVisible({ timeout: 10_000 });
  // The selector is populated asynchronously, and an upload without a library is a silent no-op.
  await expect(page.getByTestId("upload-effective-pool")).toBeVisible({ timeout: 10_000 });

  await page.getByTestId("upload-library-select").click();
  await page.getByRole("option", { name: LIBRARY }).click();
  await expect(page.getByTestId("upload-effective-pool")).toContainText(/stores to/i);
}

test.describe("Uploads – full backend e2e", () => {
  test("a generated image uploaded through the screen comes back from the server", async ({ page }) => {
    const seed = Date.now() & 0xffffff;
    const { bytes, sha512 } = generatePng(seed);
    const fileName = `e2e-upload-${seed}.png`;

    await login(page);
    const token = await apiToken(page);

    // Nothing may exist under this hash yet, or the test would be asserting someone else's asset.
    expect(await assetByHash(page, token, sha512)).toBeNull();

    await gotoUploads(page);
    await page.getByTestId("upload-file-input").setInputFiles([{ name: fileName, mimeType: "image/png", buffer: bytes }]);
    await expect(page.getByTestId(`upload-row-${fileName}`))
      .toHaveAttribute("data-status", "done", { timeout: 30_000 });

    // The screen says "done" when the last byte was *sent*; the server still has to hash and store.
    await expect.poll(() => assetByHash(page, token, sha512), { timeout: 30_000 }).not.toBeNull();
    const asset = await assetByHash(page, token, sha512);

    expect(asset.file.filename).toBe(fileName);
    expect(asset.file.size).toBe(bytes.length);
    expect(asset.hashes.sha512).toBe(sha512);
    expect(asset.file.mimeType).toBe("image/png");

    // The stored bytes must be the bytes that went in, not a re-encode.
    const previewSha = await page.evaluate(async ({ token, uuid }) => {
      const res = await fetch(`/api/v1/assets/${uuid}/binary/data`, { headers: { Authorization: `Bearer ${token}` } });
      if (!res.ok) return `status ${res.status}`;
      const digest = await crypto.subtle.digest("SHA-512", await res.arrayBuffer());
      return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
    }, { token, uuid: asset.uuid as string });
    expect(previewSha).toBe(sha512);

    // The grid preview *is* the stored binary (there is no thumbnail service), fetched by an <img>
    // that can only authenticate with the HttpOnly login cookie — the §7.2 preview path.
    await page.getByTestId("sidebar-item-/assets").click();
    const thumbnail = page.locator(`img[alt="${fileName}"]`);
    await expect(thumbnail).toBeVisible({ timeout: 15_000 });
    await thumbnail.scrollIntoViewIfNeeded();
    // A MediaPlaceholder renders an icon and no <img> at all; a broken <img> has naturalWidth 0.
    await expect.poll(() => thumbnail.evaluate((img: HTMLImageElement) => img.naturalWidth), { timeout: 15_000 })
      .toBe(48);

    await deleteAsset(page, token, asset.uuid);
  });

  test("uploading the same bytes twice reports a duplicate, not an error", async ({ page }) => {
    const seed = (Date.now() + 1) & 0xffffff;
    const { bytes, sha512 } = generatePng(seed);

    await login(page);
    const token = await apiToken(page);
    await gotoUploads(page);

    // Two different names, one identical payload: the endpoint keys on content, not filename.
    const first = `e2e-dupe-a-${seed}.png`;
    const second = `e2e-dupe-b-${seed}.png`;

    await page.getByTestId("upload-file-input").setInputFiles([{ name: first, mimeType: "image/png", buffer: bytes }]);
    await expect(page.getByTestId(`upload-row-${first}`))
      .toHaveAttribute("data-status", "done", { timeout: 30_000 });

    await page.getByTestId("upload-file-input").setInputFiles([{ name: second, mimeType: "image/png", buffer: bytes }]);
    // HTTP 200 rather than 201: the bytes were linked to the existing asset. That is a success.
    await expect(page.getByTestId(`upload-row-${second}`))
      .toHaveAttribute("data-status", "duplicate", { timeout: 30_000 });
    await expect(page.getByTestId(`upload-row-${second}`)).toContainText(/already in loom/i);

    // One asset, not two — and it kept the name it was created with.
    const asset = await assetByHash(page, token, sha512);
    expect(asset).not.toBeNull();
    expect(asset.file.filename).toBe(first);

    await deleteAsset(page, token, asset.uuid);
  });
});
