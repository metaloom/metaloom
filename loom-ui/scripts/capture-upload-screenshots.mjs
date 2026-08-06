// Capture the upload screen for the website documentation (docs/ui/).
//
// Like scripts/capture-debug-screenshots.mjs — and unlike scripts/capture-ui-screenshots.mjs — this
// one needs **no demo container and no Postgres**. It drives the real Loom UI against a fully
// intercepted API, the way the mocked specs under e2e/ do.
//
// That is not a shortcut here either. "Three uploads in progress" is a transient state: against a
// live stack you would have to push files big enough to still be in flight when the shutter opens,
// and the percentages, the byte counts and which of the three is furthest along would differ on
// every run — so the picture and the prose beside it could not be kept in step. Everything on
// screen here is fixed, and re-running this script reproduces it.
//
// What is *not* faked is the part being photographed: the real UploadView, the real module-level
// upload queue, the real `uploadAssetWithProgress` transport with its real concurrency cap of three.
// Only the network underneath it is played by this script — the upload requests are parked at a
// chosen fraction of their bytes and never answered, which is exactly the state a slow link
// produces and the only one that holds still.
//
// Prerequisites: none beyond `npm install` in loom-ui/. A Vite dev server is started automatically
// if one is not already listening.
//
// Usage (from loom-ui/):
//   node scripts/capture-upload-screenshots.mjs
//
// Env overrides:
//   VITE_PORT  (default 3000)
//   OUT_DIR    (default ../website/content/english/docs/ui)

import { chromium } from "playwright";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";
import os from "os";
import { ensureDevServer } from "./lib/devserver.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.VITE_PORT ?? 3000);
const BASE = `http://localhost:${PORT}`;
const OUT = process.env.OUT_DIR
  ? path.resolve(process.env.OUT_DIR)
  : path.resolve(ROOT, "../website/content/english/docs/ui");

fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const LIBRARIES = [
  { uuid: "lib-1", name: "Campaign Media", poolUuid: "pool-1", storageType: "filesystem" },
  { uuid: "lib-2", name: "Raw Footage", poolUuid: "pool-2", storageType: "s3" },
];

const POOLS = [
  { uuid: "pool-1", name: "Primary storage", fsPath: "/srv/loom/primary" },
  { uuid: "pool-2", name: "Archive (S3)", s3Bucket: "metaloom-archive" },
];

/**
 * The batch in the picture.
 *
 * Three files, because three is the concurrency cap: this is what a batch looks like at full tilt,
 * not an arbitrary number of rows. The sizes are real bytes on disk (the file input needs real
 * files), so the byte counts under each bar are the ones the formatters actually derive — and the
 * three fractions are deliberately far apart, since three bars at the same length would say nothing
 * about per-file progress.
 */
const BATCH = [
  { name: "beach-sunset-4k.jpg", size: 6_200_000, fraction: 0.74 },
  { name: "harbour-drone-pass.mp4", size: 12_400_000, fraction: 0.41 },
  { name: "interview-take-2.mov", size: 3_400_000, fraction: 0.17 },
];

/** Write the batch to a temp dir. Paths, not buffers: nothing has to cross the CDP connection. */
function materialize() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "loom-upload-shots-"));
  return BATCH.map(file => {
    const target = path.join(dir, file.name);
    fs.writeFileSync(target, Buffer.alloc(file.size));
    return target;
  });
}

async function mockBackend(page) {
  const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  // Catch-all first; the specific routes registered after it take precedence.
  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/libraries", route => route.fulfill(json({ data: LIBRARIES })));
  // Answering this at all is what puts the storage-pool selector on screen: a caller without
  // READ_ASSET_POOL gets a 403 and never sees it. The screenshot is of the operator's view.
  await page.route("**/api/v1/pools", route => route.fulfill(json({ data: POOLS })));
}

/**
 * Park the upload requests part-way through their bytes.
 *
 * The transport under test is an `XMLHttpRequest`, because `fetch` reports no upload progress, and
 * progress is the whole subject of these pictures. Route interception cannot help: it decides what
 * comes *back*, while the bar is drawn from `upload.onprogress` on the way out.
 *
 * So the browser's XHR is subclassed rather than replaced. Requests to any other URL still go
 * through the native implementation untouched; an upload is answered by dispatching one real
 * `ProgressEvent` at the planned fraction on the native upload object — the same event the network
 * stack would raise — and then never completing. `uploadAssetWithProgress` and the queue above it
 * run exactly as they do in production.
 */
async function parkUploads(page) {
  await page.evaluate(plan => {
    const Native = window.XMLHttpRequest;
    class ParkedUploadXHR extends Native {
      open(method, url, ...rest) {
        this.__loomUrl = String(url);
        return super.open(method, url, ...rest);
      }
      send(body) {
        if (!this.__loomUrl?.endsWith("/assets/upload")) return super.send(body);
        this.__loomParked = true;
        const file = body.get("file");
        const fraction = plan[file.name] ?? 0.5;
        // One tick later: the queue marks the item "uploading" and repaints synchronously around
        // this call, and a bar that is already part-filled on its first paint has no start state.
        setTimeout(() => {
          this.upload.dispatchEvent(new ProgressEvent("progress", {
            lengthComputable: true,
            loaded: Math.round(file.size * fraction),
            total: file.size,
          }));
        }, 120);
      }
      abort() {
        if (!this.__loomParked) super.abort();
      }
    }
    window.XMLHttpRequest = ParkedUploadXHR;
  }, Object.fromEntries(BATCH.map(f => [f.name, f.fraction])));
}

// ---------------------------------------------------------------------------
// Capture
// ---------------------------------------------------------------------------

async function main() {
  const vite = await ensureDevServer(ROOT, PORT);
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    // Shorter than the 1000px the other docs/ui captures use: this screen is a form, a drop zone and
    // a three-row list, and the rest of a 1000px window is empty canvas that survives into the
    // documentation as a third of the figure showing nothing.
    viewport: { width: 1600, height: 760 },
    deviceScaleFactor: 2,
    // The progress bars carry a shimmer and the sidebar bar animates its value; freeze both so a
    // re-run does not read as a change.
    reducedMotion: "reduce",
  });
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
    } catch (e) { /* ignore */ }
  });

  const page = await context.newPage();
  const results = [];

  const shot = async (name, { settle = 700, clip } = {}) => {
    await sleep(settle);
    await page.screenshot({ path: path.join(OUT, name), clip });
    results.push(`  ✓ ${name}`);
    console.log(`captured ${name}`);
  };

  await mockBackend(page);

  // ---- Login and open the upload screen ----
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.getByTestId("sidebar-item-/uploads").click({ timeout: 20_000 });
  await page.getByTestId("upload-view").waitFor({ timeout: 20_000 });
  await sleep(600);

  // ---- Three files in flight ----
  await parkUploads(page);
  await page.getByTestId("upload-file-input").setInputFiles(materialize());
  for (const file of BATCH) {
    await page.getByTestId(`upload-row-${file.name}`)
      .and(page.locator('[data-status="uploading"]'))
      .waitFor({ timeout: 20_000 });
  }
  // Wait for the bars to actually carry a value: the rows exist the moment the batch is enqueued,
  // and a shot taken then is three indeterminate bars, which is not what the page describes.
  await page.getByTestId("upload-totals").waitFor({ timeout: 10_000 });
  await page.waitForFunction(
    () => document.querySelector('[data-testid="upload-queue-heading"]')?.textContent?.includes("%"),
    null,
    { timeout: 10_000 }
  );
  await shot("uploads.png", { settle: 1200 });

  // ---- The same three, still running, from another screen ----
  // Leaving the screen is the claim worth photographing: the queue lives outside the React tree, so
  // the nav entry keeps the badge and the bar while the batch drains. Cropped to the CONTENT
  // section of the sidebar — the whole window would be mostly the (deliberately empty) asset
  // browser, and the ambient signal is a strip 240px wide.
  await page.getByTestId("sidebar-item-/assets").click();
  await page.getByTestId("sidebar-upload-progress").waitFor({ timeout: 10_000 });
  const strip = await page.evaluate(() => {
    const ids = ["/library", "/assets", "/uploads", "/collections", "/tasks"];
    const boxes = ids
      .map(id => document.querySelector(`[data-testid="sidebar-item-${id}"]`))
      .concat(document.querySelector('[data-testid="sidebar-upload-progress"]'))
      .filter(Boolean)
      .map(el => el.getBoundingClientRect());
    // Pad tightly. The entries above and below this range are only a few pixels away, and a crop
    // that catches the top of one of them reads as a rendering fault rather than as a cropped list.
    const top = Math.min(...boxes.map(b => b.top)) - 8;
    const bottom = Math.max(...boxes.map(b => b.bottom)) + 8;
    const right = Math.max(...boxes.map(b => b.right)) + 12;
    return { x: 0, y: Math.max(0, top), width: right, height: bottom - top };
  });
  await shot("uploads-sidebar.png", { settle: 900, clip: strip });

  console.log("\n--- Screenshot summary ---\n" + results.join("\n"));
  console.log(`\nOutput dir: ${OUT}`);

  await browser.close();
  if (vite) vite.kill();
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
