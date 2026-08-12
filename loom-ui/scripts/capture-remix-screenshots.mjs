// Capture the remix screens for the website documentation.
//
// Like scripts/capture-share-screenshots.mjs and unlike scripts/capture-ui-screenshots.mjs, this
// one needs **no demo container and no Postgres**: it drives the real Loom UI against a fully
// intercepted API, the way the mocked specs under e2e/ do.
//
// Mocked rather than live because the pictures have to be reproducible. A live capture would show
// whatever the demo container happened to seed, with generated uuids in the URL bar and a member
// order that depends on insertion timing — so the prose beside a figure could not describe what the
// reader is looking at. What is *not* faked is the part being photographed: the real AssetBrowser,
// RemixCard and RemixDialog, mounted through the real router, reading the real api/remixes.ts
// client. Only the network underneath is played by this script.
//
// Usage (from loom-ui/):
//   node scripts/capture-remix-screenshots.mjs
//
// Env overrides:
//   VITE_PORT  (default 3000)
//   OUT_DIR    (default ../website/content/english/docs/loom/remixes)

import { chromium } from "playwright";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";
import { ensureDevServer } from "./lib/devserver.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.VITE_PORT ?? 3000);
const BASE = `http://localhost:${PORT}`;
const OUT = process.env.OUT_DIR
  ? path.resolve(process.env.OUT_DIR)
  : path.resolve(ROOT, "../website/content/english/docs/loom/remixes");

fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const REMIX_UUID = "c0000000-0000-0000-0000-000000000001";
const SHA512 = "a".repeat(128);

// The demo container's own media. The grid tiles are real previews — an <img> for a picture and a
// muted <video> seeking one frame for a clip — so a fixture with no bytes behind it photographs a
// wall of placeholder icons instead of a remix.
const DEMO_CONTENT = path.resolve(ROOT, "../demo-content");
const file = rel => fs.readFileSync(path.join(DEMO_CONTENT, rel));

const ASSETS = [
  { uuid: "a0000000-0000-0000-0000-000000000001", filename: "team-meeting.mp4", mimeType: "video/mp4", size: 5_895_293,
    bytes: file("videos/video-01-work-meeting-around-table.mp4") },
  { uuid: "a0000000-0000-0000-0000-000000000002", filename: "team-meeting-cut.mp4", mimeType: "video/mp4", size: 2_154_678,
    bytes: file("videos/video-01-work-meeting-around-table-cut.mp4") },
  { uuid: "a0000000-0000-0000-0000-000000000003", filename: "team-meeting-still.jpg", mimeType: "image/jpeg", size: 138_972,
    bytes: file("videos/video-01-work-meeting-around-table-still.jpg") },
  { uuid: "a0000000-0000-0000-0000-000000000004", filename: "city-traffic.mp4", mimeType: "video/mp4", size: 11_342_857,
    bytes: file("videos/video-02-busy-street-traffic.mp4") },
  { uuid: "a0000000-0000-0000-0000-000000000005", filename: "street-crossing.jpg", mimeType: "image/jpeg", size: 536_000,
    bytes: file("images/image-01-people-crossing-street.jpg") },
  { uuid: "a0000000-0000-0000-0000-000000000006", filename: "coworkers-laptop.jpg", mimeType: "image/jpeg", size: 532_000,
    bytes: file("images/image-02-coworkers-laptop-table.jpg") },
];

const assetResponse = a => ({
  uuid: a.uuid,
  file: { filename: a.filename, mimeType: a.mimeType, size: a.size, origin: "upload" },
  hashes: { sha512: SHA512 },
  tags: [],
  status: { created: "2026-08-11T10:00:00Z" },
});

const REMIXES = [
  {
    uuid: REMIX_UUID,
    name: "Team meeting — cuts",
    description: "The original meeting footage, the ten-second cut made from it, and a still pulled out of that cut.",
    sourceAssetUuid: ASSETS[0].uuid,
    memberCount: 3,
    status: { created: "2026-08-11T10:00:00Z", edited: "2026-08-11T10:00:00Z" },
  },
  {
    uuid: "c0000000-0000-0000-0000-000000000002",
    name: "City traffic — versions",
    description: "The graded and ungraded versions of the traffic plate.",
    sourceAssetUuid: ASSETS[3].uuid,
    memberCount: 2,
    status: { created: "2026-08-11T10:00:00Z", edited: "2026-08-11T10:00:00Z" },
  },
];

const MEMBERS = [
  { uuid: "b0000000-0000-0000-0000-000000000001", assetUuid: ASSETS[0].uuid, role: "SOURCE", ordinal: 0, filename: ASSETS[0].filename, mimeType: ASSETS[0].mimeType, size: ASSETS[0].size },
  { uuid: "b0000000-0000-0000-0000-000000000002", assetUuid: ASSETS[1].uuid, role: "DERIVED", ordinal: 1, filename: ASSETS[1].filename, mimeType: ASSETS[1].mimeType, size: ASSETS[1].size },
  { uuid: "b0000000-0000-0000-0000-000000000003", assetUuid: ASSETS[2].uuid, role: "DERIVED", ordinal: 2, filename: ASSETS[2].filename, mimeType: ASSETS[2].mimeType, size: ASSETS[2].size },
];

const json = (body, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });

/**
 * The baseline mocks.
 *
 * Registration order is load-bearing: Playwright matches the most recently registered handler
 * first, so the catch-all goes in before everything it must not shadow, and the membership routes
 * before the bare `/remixes/:uuid` that would otherwise swallow them.
 */
async function mockRest(page) {
  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));

  // The tile previews. Same route the product uses; the bytes are the file the asset names.
  await page.route(/\/api\/v1\/assets\/[^/?]+\/binary\/data/, route => {
    const uuid = route.request().url().split("/assets/")[1].split("/")[0];
    const asset = ASSETS.find(a => a.uuid === uuid);
    return asset
      ? route.fulfill({ status: 200, contentType: asset.mimeType, body: asset.bytes })
      : route.fulfill({ status: 404, body: "" });
  });
  await page.route("**/api/v1/me", route =>
    route.fulfill(json({ uuid: "11111111-1111-1111-1111-111111111111", username: "admin", enabled: true })));
  await page.route(/\/api\/v1\/libraries(\?|$)/, route =>
    route.fulfill(json({ data: [{ uuid: "lib-1", name: "Main Library" }] })));

  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill(json({ data: ASSETS.map(assetResponse), _metainfo: { totalCount: ASSETS.length, perPage: 25 } })));

  // Search, so the "Remixes" filter picture has a working search box beside it.
  await page.route(/\/api\/v1\/search\/status(\?.*)?$/, route =>
    route.fulfill(json({ provider: "postgres", available: true, capabilities: [], documentCount: 12, dirtyCount: 0 })));
  await page.route(/\/api\/v1\/search\/results(\?.*)?$/, route =>
    route.fulfill(json({
      data: REMIXES.map(r => ({ type: "remix", uuid: r.uuid, score: 1, title: r.name, subtitle: r.description })),
      _metainfo: { totalHits: REMIXES.length, totalExact: true, perPage: 25, offset: 0, tookMs: 3, provider: "postgres", capabilities: [], warnings: [] },
    })));

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/assets(\?.*)?$/, route =>
    route.fulfill(json({ data: MEMBERS, _metainfo: { totalCount: MEMBERS.length, perPage: 25 } })));
  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+$/, route => route.fulfill(json(REMIXES[0])));
  await page.route(/\/api\/v1\/remixes(\?.*)?$/, route =>
    route.request().method() === "POST"
      ? route.fulfill(json(REMIXES[0], 201))
      : route.fulfill(json({ data: REMIXES, _metainfo: { totalCount: REMIXES.length, perPage: 25 } })));
}

/** Sign in, then land on the asset browser. Auth is in-memory, so this has to happen per page. */
async function signIn(page, deepLink = "/ui/assets") {
  await page.goto(`${BASE}${deepLink}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.getByTestId("remix-card").first().waitFor({ timeout: 15_000 });
}

// ---------------------------------------------------------------------------
// Capture
// ---------------------------------------------------------------------------

async function main() {
  const vite = await ensureDevServer(ROOT, PORT);
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1000 },
    deviceScaleFactor: 2,
    reducedMotion: "reduce",
  });
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
    } catch (e) { /* ignore */ }
  });

  const results = [];
  const shot = async (page, name, { settle = 800, clip } = {}) => {
    await sleep(settle);
    await page.screenshot({ path: path.join(OUT, name), clip });
    results.push(`  ✓ ${name}`);
    console.log(`captured ${name}`);
  };

  // --- 1. The remix band in the asset grid ----------------------------------
  {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page);
    await shot(page, "remix-grid.png");
    await page.close();
  }

  // --- 2. An opened remix ---------------------------------------------------
  {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page);
    await page.getByTestId("remix-card").first().click();
    await page.getByTestId("remix-dialog").waitFor({ timeout: 15_000 });
    await page.getByTestId("remix-member").first().waitFor({ timeout: 15_000 });
    await shot(page, "remix-open.png");
    await page.close();
  }

  // --- 3. The selection tray, with the action menu open ---------------------
  {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page);
    await page.getByRole("button", { name: "Select", exact: true }).click();
    const checkboxes = page.getByRole("checkbox");
    await checkboxes.nth(0).click();
    await checkboxes.nth(1).click();
    await checkboxes.nth(2).click();
    await page.getByTestId("bulk-actions-menu-button").click();
    await page.getByTestId("bulk-combine-remix").waitFor({ timeout: 15_000 });
    await shot(page, "remix-combine.png");
    await page.close();
  }

  // --- 4. Naming the new remix ----------------------------------------------
  {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page);
    await page.getByRole("button", { name: "Select", exact: true }).click();
    const checkboxes = page.getByRole("checkbox");
    await checkboxes.nth(0).click();
    await checkboxes.nth(1).click();
    await page.getByTestId("bulk-actions-menu-button").click();
    await page.getByTestId("bulk-combine-remix").click();
    await page.getByTestId("remix-create-dialog").waitFor({ timeout: 15_000 });
    await page.getByTestId("remix-create-name").fill("Team meeting — cuts");
    await shot(page, "remix-create.png");
    await page.close();
  }

  // --- 5. The Remixes filter ------------------------------------------------
  {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page);
    await page.getByRole("combobox").first().click();
    await page.getByTestId("assets-filter-remix").click();
    await page.getByTestId("remix-card").first().waitFor({ timeout: 15_000 });
    await shot(page, "remix-filter.png");
    await page.close();
  }

  await browser.close();
  if (vite) {
    vite.kill();
  }
  console.log(`\nWrote ${results.length} screenshots to ${OUT}:`);
  results.forEach(r => console.log(r));
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
