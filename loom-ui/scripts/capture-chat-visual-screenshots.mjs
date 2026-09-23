// Capture the chat's asset visuals for the website documentation (docs/ui/ → "Chat & AI Agent").
//
// Like scripts/capture-share-screenshots.mjs, and unlike scripts/capture-ui-screenshots.mjs, this
// one needs **no demo container, no Postgres and no LLM**. It drives the real Loom UI against a
// fully intercepted API, the way the mocked specs under e2e/ do.
//
// That is the right call rather than a shortcut: photographing these two cards against a live
// stack means an LLM that decides, on its own, to call show_asset on the asset the caption talks
// about — so every run would produce a different picture, or none. What is *not* faked is the part
// being photographed: the real ChatWorkspace, AssetViewerCard, AssetResults and AssetVideoPlayer,
// reading the real api/ clients. Only the network underneath, and the agent's side of the
// conversation, are played by this script.
//
// The media is the demo container's own footage out of demo-content/, because a <video> handed
// invalid bytes paints its buffering spinner for ever and the picture is then of a player that
// looks broken.
//
// Prerequisites: none beyond `npm install` in loom-ui/ and the checked-in demo-content/ media. A
// Vite dev server is started automatically if one is not already listening.
//
// Usage (from loom-ui/):
//   node scripts/capture-chat-visual-screenshots.mjs
//
// Env overrides:
//   VITE_PORT  (default 3000)
//   OUT_DIR    (default ../website/content/english/docs/ui)

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
  : path.resolve(ROOT, "../website/content/english/docs/ui");

fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Fixtures — the demo corpus, as the agent would have found it
// ---------------------------------------------------------------------------

const DEMO = path.resolve(ROOT, "../demo-content");
const MEETING_MP4 = fs.readFileSync(path.join(DEMO, "videos/video-01-work-meeting-around-table.mp4"));
const MEETING_POSTER = fs.readFileSync(path.join(DEMO, "videos/video-01-work-meeting-around-table-poster.jpg"));
const STREET_MP4 = fs.readFileSync(path.join(DEMO, "videos/video-02-busy-street-traffic.mp4"));

/** Measured with ffprobe. The transport draws itself from this, so a wrong one shows a wrong clip length. */
const MEETING_SECONDS = 28.27;

const MEETING = "a1000000-0000-0000-0000-000000000001";
const STREET = "a1000000-0000-0000-0000-000000000002";
const CROSSING = "a1000000-0000-0000-0000-000000000003";
const VENDOR = "a1000000-0000-0000-0000-000000000004";

/** Where each asset's picture comes from, so a tile is a frame of the file it names. */
const PICTURES = {
  [MEETING]: MEETING_POSTER,
  [STREET]: fs.readFileSync(path.join(DEMO, "images/image-01-people-crossing-street.jpg")),
  [CROSSING]: fs.readFileSync(path.join(DEMO, "images/image-01-people-crossing-street.jpg")),
  [VENDOR]: fs.readFileSync(path.join(DEMO, "images/image-06-street-food-vendor.jpg")),
};

const BYTES = { [MEETING]: MEETING_MP4, [STREET]: STREET_MP4 };

const ASSETS = {
  [MEETING]: { filename: "video-01-work-meeting-around-table.mp4", mimeType: "video/mp4", size: 18_400_000 },
  [STREET]: { filename: "video-02-busy-street-traffic.mp4", mimeType: "video/mp4", size: 9_100_000 },
  [CROSSING]: { filename: "image-01-people-crossing-street.jpg", mimeType: "image/jpeg", size: 2_300_000 },
  [VENDOR]: { filename: "image-06-street-food-vendor.jpg", mimeType: "image/jpeg", size: 1_900_000 },
};

/** The moment the answer is about. Inside the clip, so the player really opens there. */
const HIT_MS = 9_400;

const RESULTS_VISUAL = {
  type: "asset-results",
  uuid: "b1000000-0000-0000-0000-000000000001",
  label: "the delivery date",
  payload: {
    query: "the delivery date",
    criteria: "spoken content",
    total: 12,
    totalExact: true,
    items: [
      { uuid: MEETING, title: ASSETS[MEETING].filename, mimeType: "video/mp4", score: 0.93, timeFromMs: HIT_MS,
        snippet: "we can hold the delivery date if the review lands on Friday" },
      { uuid: STREET, title: ASSETS[STREET].filename, mimeType: "video/mp4", score: 0.41, timeFromMs: 4_100,
        snippet: "delivery vans blocking the near lane" },
      { uuid: CROSSING, title: ASSETS[CROSSING].filename, mimeType: "image/jpeg", score: 0.22 },
      { uuid: VENDOR, title: ASSETS[VENDOR].filename, mimeType: "image/jpeg", score: 0.18 },
    ],
  },
};

const VIEWER_VISUAL = {
  type: "asset-viewer",
  uuid: MEETING,
  label: ASSETS[MEETING].filename,
  payload: {
    assetUuid: MEETING,
    filename: ASSETS[MEETING].filename,
    mimeType: "video/mp4",
    kind: "video",
    size: ASSETS[MEETING].size,
    startSeconds: HIT_MS / 1000,
    caption: "the delivery date is discussed from here",
  },
};

const SEARCH_ANSWER =
  "The delivery date comes up in the **team meeting** clip, about nine seconds in — "
  + "they agree it can hold if the review lands on Friday. The other three matches are about "
  + "delivery vans, not the schedule.";
const SHOW_ANSWER = "Here it is, opened on that exchange.";

function sse(events) {
  return events.map(([type, data]) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

function exchange(chatUuid, { tool, visual, answer, id }) {
  return [
    ["agent_start", { chatUuid, model: "qwen3-4b", maxTurns: 8 }],
    ["turn_start", { turn: 1 }],
    ["tool_start", { turn: 1, toolCallId: id, name: tool, args: {} }],
    ["tool_end", { turn: 1, toolCallId: id, name: tool, isError: false, summary: "ok", references: [], visuals: [visual] }],
    ["turn_end", { turn: 1 }],
    ["turn_start", { turn: 2 }],
    ["text_delta", { turn: 2, text: answer }],
    ["turn_end", { turn: 2 }],
    ["message_end", { message: { id: `m-${id}`, role: "assistant", content: answer, visuals: [visual], createdAt: new Date().toISOString() } }],
    ["agent_end", { chatUuid, status: "completed" }],
  ];
}

const json = (body, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });

async function mock(page) {
  const chats = [];
  let turn = 0;

  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/me", route =>
    route.fulfill(json({ uuid: "11111111-1111-1111-1111-111111111111", username: "admin", enabled: true })));

  // Media. The player is a real <video> over the real remux route, so it is handed real bytes.
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-info$/, route =>
    route.fulfill(json({ duration: MEETING_SECONDS, frameRate: 25, width: 1920, height: 1080, videoCodec: "h264", audioCodec: "aac", streamable: true })));
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-token$/, route =>
    route.fulfill(json({ token: "fake-media-token", expiresIn: 600 })));
  await page.route(/\/api\/v1\/assets\/[^/]+\/poster/, route => {
    const uuid = route.request().url().split("/assets/")[1].split("/")[0];
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: PICTURES[uuid] ?? MEETING_POSTER });
  });
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream/, route => {
    const uuid = route.request().url().split("/assets/")[1].split("/")[0];
    return route.fulfill({ status: 200, contentType: "video/mp4", body: BYTES[uuid] ?? MEETING_MP4 });
  });
  // Registered after /stream on purpose: that pattern matches this path too, and Playwright gives
  // the last registration first refusal.
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream-start/, route => {
    const requested = Number(new URL(route.request().url()).searchParams.get("t") ?? "0");
    return route.fulfill(json({ requested, start: requested }));
  });
  await page.route(/\/api\/v1\/assets\/[^/]+\/binary\/data/, route => {
    const uuid = route.request().url().split("/assets/")[1].split("/")[0];
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: PICTURES[uuid] ?? MEETING_POSTER });
  });
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]{36}$/, route => {
    const uuid = route.request().url().split("/assets/")[1].split("?")[0];
    const a = ASSETS[uuid] ?? ASSETS[MEETING];
    return route.fulfill(json({ uuid, file: a, tags: [{ name: "meeting" }, { name: "internal" }] }));
  });

  await page.route(/\/api\/v1\/chats$/, route => {
    if (route.request().method() === "POST") {
      const created = { uuid: "chat-1", title: "The delivery date", messages: [] };
      chats.unshift(created);
      return route.fulfill(json(created, 201));
    }
    return route.fulfill(json({ data: chats }));
  });
  await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, route => {
    const chatUuid = route.request().url().split("/chats/")[1].split("/")[0];
    turn += 1;
    const events = turn === 1
      ? exchange(chatUuid, { tool: "search_transcript", visual: RESULTS_VISUAL, answer: SEARCH_ANSWER, id: "c1" })
      : exchange(chatUuid, { tool: "show_asset", visual: VIEWER_VISUAL, answer: SHOW_ANSWER, id: "c2" });
    return route.fulfill({ status: 200, contentType: "text/event-stream", body: sse(events) });
  });
  await page.route(/\/api\/v1\/chats\/[^/]+$/, route => route.fulfill(json(chats[0] ?? {})));
}

/**
 * Park the player on a decoded frame.
 *
 * Left alone it paints its own buffering spinner over the picture, which reads in the
 * documentation as a video that failed to load.
 */
async function settlePlayer(page, seconds) {
  await page.evaluate(async at => {
    const video = document.querySelector("video");
    if (!video) return;
    await new Promise(resolve => {
      if (video.readyState >= 2) return resolve(undefined);
      video.addEventListener("loadeddata", () => resolve(undefined), { once: true });
      setTimeout(resolve, 3000);
    });
    video.currentTime = at;
    video.pause();
  }, seconds);
}

async function main() {
  const vite = await ensureDevServer(ROOT, PORT);
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1600, height: 820 }, deviceScaleFactor: 2 });
  // Force dark mode regardless of any persisted preference, and widen the workspace panel.
  //
  // The 80/20 default is right for working — the conversation is the thing — but at 1600px it
  // leaves the panel about 290px wide, which clips the filenames and half the tab bar. Both
  // halves are the subject here, so the split is set to something a reader can read.
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
      localStorage.setItem("loom.chat.splitPct", "62");
    } catch (e) {
      /* ignore */
    }
  });

  const page = await context.newPage();
  const results = [];
  const shot = async (name, settle = 900) => {
    await sleep(settle);
    await page.screenshot({ path: path.join(OUT, name) });
    results.push(`  ✓ ${name}`);
    console.log(`captured ${name}`);
  };

  await mock(page);
  await page.goto(`${BASE}/ui/`, { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: "Sign in" }).click();

  const input = page.getByPlaceholder(/Ask about assets/i);
  await input.waitFor({ timeout: 20_000 });

  // --- 1. A search: the strip under the answer, the panel beside it ---------
  await input.fill("where do they talk about the delivery date?");
  await input.press("Enter");
  await page.getByTestId("chat-results-panel").waitFor({ timeout: 20_000 });
  await page.getByTestId("chat-asset-result").first().waitFor({ timeout: 20_000 });
  await shot("chat-asset-results.png", 1600);

  // --- 2. The follow-up: one asset, embedded and playable -------------------
  await input.fill("play that bit");
  await input.press("Enter");
  await page.getByTestId("chat-asset-video").waitFor({ timeout: 20_000 });
  await settlePlayer(page, HIT_MS / 1000);
  // The card grew after the auto-scroll ran, so its caption is below the fold until the transcript
  // is pushed down again — and the caption is half of what this picture is meant to show.
  await page.getByTestId("chat-asset-viewer-caption").scrollIntoViewIfNeeded();
  await shot("chat-asset-viewer.png", 1400);

  await browser.close();
  if (vite) vite.kill();

  console.log(`\nWrote into ${OUT}:`);
  results.forEach(line => console.log(line));
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
