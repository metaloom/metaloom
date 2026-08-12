// Capture the three stages of a share link for the website documentation.
//
// Like scripts/capture-upload-screenshots.mjs and scripts/capture-debug-screenshots.mjs — and unlike
// scripts/capture-ui-screenshots.mjs — this one needs **no demo container and no Postgres**. It
// drives the real Loom UI against a fully intercepted API, the way the mocked specs under e2e/ do.
//
// That is the right call here rather than a shortcut. Two of the three pictures cannot be taken
// against a live stack without spoiling them:
//
//   * The share dialog shows a freshly generated URL and password. Both are random by construction,
//     so a live capture would produce a different slug and a different password on every run, and
//     the documentation beside the picture could not quote either.
//   * The customer view is supposed to show a review already in progress — a comment, a reply, a
//     marked moment, an approval. Arranging that against a real server means opening the link in a
//     second browser and typing it by hand before every capture.
//
// What is *not* faked is the part being photographed: the real SharePage, ShareGate, ShareViewer and
// ShareDialog, mounted through the real router at the real /ui/share/:slug route, reading the real
// api/shares.ts client. Only the network underneath is played by this script. The videos are the
// demo container's own clips out of demo-content/, because a <video> handed invalid bytes errors into
// the "no preview" placeholder and the picture would silently be of the wrong thing.
//
// Prerequisites: none beyond `npm install` in loom-ui/ and the checked-in demo-content/ media. A
// Vite dev server is started automatically if one is not already listening.
//
// Usage (from loom-ui/):
//   node scripts/capture-share-screenshots.mjs
//
// Env overrides:
//   VITE_PORT  (default 3000)
//   OUT_DIR    (default ../website/content/english/docs/loom/sharing)

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
  : path.resolve(ROOT, "../website/content/english/docs/loom/sharing");

fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

// Fixed, so the prose beside the pictures can quote them. A real slug is 128 random bits.
const SLUG = "k3Rm2pQwXbN7vTsLd9aYc1";
const PASSWORD = "amber-lantern-42";
const SHARE_URL = `https://loom.example.com/ui/share/${SLUG}`;
const COLLECTION_UUID = "c0000000-0000-0000-0000-000000000001";

// The real demo clips, served as the shared bytes. The share viewer's player is a real <video>:
// handed a stand-in it either errors into the "no preview" card or paints a single flat frame, and
// the picture is then of the wrong thing. `demo-content/` is where the demo's media lives, so the
// screenshots and the container a reader downloads show the same footage.
const DEMO_CONTENT = path.resolve(ROOT, "../demo-content");
const MEETING_MP4 = fs.readFileSync(path.join(DEMO_CONTENT, "videos/video-01-work-meeting-around-table.mp4"));
const MEETING_CUT_MP4 = fs.readFileSync(path.join(DEMO_CONTENT, "videos/video-01-work-meeting-around-table-cut.mp4"));
const TRAFFIC_MP4 = fs.readFileSync(path.join(DEMO_CONTENT, "videos/video-02-busy-street-traffic.mp4"));

// Durations are milliseconds on the wire — asset_video_comp.media_duration is a millisecond column
// and the share endpoint passes it through; api/shares.ts divides on the way in. Serving seconds
// here would photograph a timecode the product does not produce.
const ASSETS = [
  {
    uuid: "a0000000-0000-0000-0000-000000000001",
    filename: "team-meeting.mp4",
    mimeType: "video/mp4",
    size: 5_895_293,
    duration: 28_267,
    width: 1920,
    height: 1080,
    title: "Boardroom, full take",
    description: "The whole take. Grade and titles still to come.",
  },
  {
    uuid: "a0000000-0000-0000-0000-000000000002",
    filename: "team-meeting-cut.mp4",
    mimeType: "video/mp4",
    size: 2_154_678,
    duration: 10_000,
    width: 1920,
    height: 1080,
    title: "Boardroom, ten-second cut",
  },
  {
    uuid: "a0000000-0000-0000-0000-000000000003",
    filename: "city-traffic.mp4",
    mimeType: "video/mp4",
    size: 11_342_857,
    duration: 13_367,
    width: 1920,
    height: 1080,
    title: "Establishing shot — the intersection",
  },
];

/** Which clip each shared asset serves, so a tile's frame is a frame of the clip it names. */
const ASSET_BYTES = {
  [ "a0000000-0000-0000-0000-000000000001" ]: MEETING_MP4,
  [ "a0000000-0000-0000-0000-000000000002" ]: MEETING_CUT_MP4,
  [ "a0000000-0000-0000-0000-000000000003" ]: TRAFFIC_MP4,
};

/**
 * A review already under way.
 *
 * An empty feedback panel and a broken one look identical in a screenshot, so the picture that is
 * supposed to show the feature working has to show it holding something.
 */
const COMMENTS = [
  {
    uuid: "cm1",
    assetUuid: ASSETS[0].uuid,
    text: "The second cut runs long — could we lose the establishing shot at the top?",
    authorName: "Maria from Acme",
    created: "2026-08-09T09:12:00Z",
  },
  {
    uuid: "cm2",
    assetUuid: ASSETS[0].uuid,
    parentUuid: "cm1",
    text: "Ignore that — the client wants the wide. Leave it as is.",
    authorName: "Maria from Acme",
    created: "2026-08-09T09:41:00Z",
  },
];

const ANNOTATIONS = [
  {
    uuid: "an1",
    assetUuid: ASSETS[0].uuid,
    kind: "TEMPORAL",
    timeFrom: 14.25,
    text: "Logo is clipped on the right here.",
    authorName: "Maria from Acme",
    created: "2026-08-09T09:15:00Z",
  },
];

const REACTIONS = [
  { uuid: "re1", type: "APPROVE", assetUuid: ASSETS[0].uuid, authorName: "Maria from Acme", created: "2026-08-09T09:44:00Z" },
];

const COLLECTIONS = [
  {
    uuid: COLLECTION_UUID,
    name: "Q3 launch film — rough cuts",
    status: { created: "2026-08-01T10:00:00Z", edited: "2026-08-08T16:20:00Z" },
  },
];

const SHARE = {
  uuid: "50000000-0000-0000-0000-000000000001",
  slug: SLUG,
  url: SHARE_URL,
  targetType: "COLLECTION",
  targetUuid: COLLECTION_UUID,
  targetName: "Q3 launch film — rough cuts",
  password: PASSWORD,
  passwordProtected: true,
  expired: false,
  allowDownload: true,
  showMetadata: true,
  allowComments: true,
  allowReactions: true,
  allowAnnotations: true,
  viewCount: 0,
  feedbackCount: 0,
  status: { created: "2026-08-11T10:00:00Z", edited: "2026-08-11T10:00:00Z" },
};

const json = (body, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });

/** The owner's side: the collections screen and the share-link routes behind its dialog. */
async function mockOwner(page) {
  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/me", route =>
    route.fulfill(json({ uuid: "11111111-1111-1111-1111-111111111111", username: "admin", enabled: true })));
  await page.route(/\/api\/v1\/collections(\?|$)/, route =>
    route.fulfill(json({ data: COLLECTIONS, _metainfo: { totalCount: 1, perPage: 25 } })));
  await page.route(/\/api\/v1\/share-links\/[^/]+$/, route => route.fulfill(json(SHARE)));
  // The password is returned exactly once, by create — which is precisely what this picture is of.
  await page.route(/\/api\/v1\/share-links$/, route =>
    route.request().method() === "POST" ? route.fulfill(json(SHARE, 201)) : route.fulfill(json({ data: [] })));
}

/** The customer's side: the challenge, the session, the material and the feedback on it. */
async function mockCustomer(page, { passwordRequired = true } = {}) {
  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));

  await page.route(/\/api\/v1\/shares\/[^/]+\/assets\/[^/]+\/binary\/data/, route => {
    const uuid = route.request().url().split("/assets/")[1].split("/")[0];
    return route.fulfill({ status: 200, contentType: "video/mp4", body: ASSET_BYTES[uuid] ?? MEETING_MP4 });
  });

  await page.route(/\/api\/v1\/shares\/[^/]+\/comments(\?|$)/, route => route.fulfill(json({ data: COMMENTS })));
  await page.route(/\/api\/v1\/shares\/[^/]+\/annotations(\?|$)/, route => route.fulfill(json({ data: ANNOTATIONS })));
  await page.route(/\/api\/v1\/shares\/[^/]+\/reactions(\?|$)/, route => route.fulfill(json({ data: REACTIONS })));
  await page.route(/\/api\/v1\/shares\/[^/]+\/assets(\?|$)/, route => route.fulfill(json({ data: ASSETS })));

  await page.route(/\/api\/v1\/shares\/[^/]+\/sessions$/, route =>
    route.fulfill(json({
      sessionToken: "payload.signature",
      visitorName: "Maria from Acme",
      targetType: "COLLECTION",
      targetName: "Q3 launch film — rough cuts",
      targetDescription: "Three cuts for sign-off before the grade.",
      allowDownload: true,
      showMetadata: true,
      allowComments: true,
      allowReactions: true,
      allowAnnotations: true,
    })));

  // Registered last so the bare slug matches first without swallowing the sub-resources above.
  await page.route(/\/api\/v1\/shares\/[^/?]+(\?|$)/, route =>
    route.fulfill(json({ targetType: "COLLECTION", passwordRequired, visitorNameKnown: false })));
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

  // --- 1. Creating the link -------------------------------------------------
  {
    const page = await context.newPage();
    await mockOwner(page);
    // Deep-link, then sign in: auth is in-memory, so navigating after signing in throws the token
    // away and lands back on the login form.
    await page.goto(`${BASE}/ui/collections`);
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Password").fill("finger");
    await page.getByRole("button", { name: /sign in/i }).click();
    await page.getByTestId("collection-share").first().waitFor({ state: "attached", timeout: 15_000 });
    await page.getByTestId("collection-share").first().click({ force: true });
    await page.getByTestId("share-dialog-link").waitFor({ timeout: 15_000 });
    await shot(page, "share-dialog.png");
    await page.close();
  }

  // --- 2. The front door ----------------------------------------------------
  {
    const page = await context.newPage();
    // A tighter frame than the other two. The gate is one 420px card on an otherwise empty page, so
    // the default 1600x1000 window would put two thirds of a documentation figure into blank canvas.
    await page.setViewportSize({ width: 900, height: 560 });
    await mockCustomer(page, { passwordRequired: true });
    await page.goto(`${BASE}/ui/share/${SLUG}`);
    await page.getByTestId("share-gate").waitFor({ timeout: 15_000 });
    await page.getByTestId("share-gate-name").fill("Maria from Acme");
    await page.getByTestId("share-gate-password").fill(PASSWORD);
    await shot(page, "share-gate.png");
    await page.close();
  }

  // --- 3. The customer view, mid-review -------------------------------------
  {
    const page = await context.newPage();
    await mockCustomer(page, { passwordRequired: false });
    await page.goto(`${BASE}/ui/share/${SLUG}`);
    await page.getByTestId("share-gate").waitFor({ timeout: 15_000 });
    await page.getByTestId("share-gate-name").fill("Maria from Acme");
    await page.getByTestId("share-gate-submit").click();
    await page.getByTestId("share-grid").waitFor({ timeout: 15_000 });
    // Short frame: the grid is one row of tiles, and the rest of a 1000px window survives into the
    // documentation as two thirds of a figure showing nothing.
    await page.setViewportSize({ width: 1500, height: 520 });
    await shot(page, "share-collection.png");
    await page.setViewportSize({ width: 1600, height: 1000 });

    // Into one clip, where the feedback panel has something in it.
    await page.getByTestId("share-tile").first().click();
    await page.getByTestId("share-media-video").waitFor({ timeout: 15_000 });
    await page.getByTestId("share-comment").first().waitFor({ timeout: 15_000 });
    // Park the player on a decoded frame. Left alone it paints its own buffering spinner over the
    // picture, which reads in the documentation as a video that failed to load.
    await page.evaluate(async () => {
      const video = document.querySelector("video");
      if (!video) return;
      await new Promise(resolve => {
        if (video.readyState >= 2) return resolve(undefined);
        video.addEventListener("loadeddata", () => resolve(undefined), { once: true });
        setTimeout(resolve, 3000);
      });
      video.currentTime = 0.3;
      video.pause();
    });
    await shot(page, "share-view.png", { settle: 1400 });
    await page.close();
  }

  await browser.close();
  if (vite) vite.kill();

  console.log(`\nWrote into ${OUT}:`);
  results.forEach(line => console.log(line));
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
