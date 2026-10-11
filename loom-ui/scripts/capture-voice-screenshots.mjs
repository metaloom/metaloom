// Capture the speaker-review and person-voice screens for the website documentation.
//
// Like scripts/capture-remix-screenshots.mjs this needs **no demo container and no Postgres**: it
// drives the real Loom UI against a fully intercepted API, the way the mocked specs under e2e/ do.
//
// Mocked rather than live because the demo seed has no speech: its corpus carries no audio a
// voiceprint node could cluster, so a live capture of the Speakers tab would photograph the empty
// state. What is *not* faked is the part being photographed - the real SpeakerReview and
// PersonVoiceSection, mounted through the real router, reading the real api/ clients. Only the
// network underneath is played by this script.
//
// The two pictures sit beside face-detection.png and persons.png in the docs/ui page bundle, which
// is where the people documentation lives.
//
// Usage (from loom-ui/):
//   node scripts/capture-voice-screenshots.mjs                    # both
//   node scripts/capture-voice-screenshots.mjs speakers-review.png # just one
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

const only = process.argv.slice(2).filter(a => a.endsWith(".png"));
const wanted = name => only.length === 0 || only.includes(name);

fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const DEMO_CONTENT = path.resolve(ROOT, "../demo-content");
const demoFile = rel => fs.readFileSync(path.join(DEMO_CONTENT, rel));

/** One second of silence as 16 kHz mono WAV - enough for the player to draw its controls. */
function silentWav(seconds = 1) {
  const samples = 16000 * seconds;
  const data = samples * 2;
  const b = Buffer.alloc(44 + data);
  b.write("RIFF", 0); b.writeUInt32LE(36 + data, 4); b.write("WAVE", 8);
  b.write("fmt ", 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(16000, 24); b.writeUInt32LE(32000, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write("data", 36); b.writeUInt32LE(data, 40);
  return b;
}
const WAV = silentWav();

const ASSETS = {
  "a0000000-0000-0000-0000-000000000001": "team-meeting.mp4",
  "a0000000-0000-0000-0000-000000000002": "town-hall-q3.mp4",
  "a0000000-0000-0000-0000-000000000003": "podcast-episode-12.m4a",
};
const [MEETING, TOWNHALL, PODCAST] = Object.keys(ASSETS);

const ALICE = "p0000000-0000-0000-0000-000000000001";
const BOB = "p0000000-0000-0000-0000-000000000002";
const PERSONS = [
  { uuid: ALICE, alias: "alice", firstname: "Alice", lastname: "Smith", avatarUrl: `/api/v1/persons/${ALICE}/images/i1/data`, status: { created: "2026-08-01T10:00:00Z" } },
  { uuid: BOB, alias: "bob", firstname: "Bob", lastname: "Jones", status: { created: "2026-08-01T10:00:00Z" } },
];

const seg = (startMs, endMs) => ({ startMs, endMs });

const SPEAKERS = [
  {
    uuid: "c0000000-0000-0000-0000-000000000001", name: "Speaker 1", type: "speaker", reviewStatus: "PENDING",
    assetUuid: TOWNHALL, memberCount: 58, model: "redimnet2",
    suggestedPersonUuid: ALICE, suggestedPersonName: "Alice Smith", suggestedScore: 0.82,
    meta: { totalMs: 87_000, segments: [seg(4_000, 31_500), seg(62_000, 95_500), seg(140_000, 166_000)] },
  },
  {
    uuid: "c0000000-0000-0000-0000-000000000002", name: "Speaker 2", type: "speaker", reviewStatus: "PENDING",
    assetUuid: TOWNHALL, memberCount: 21, model: "redimnet2",
    meta: { totalMs: 34_500, segments: [seg(31_500, 49_000), seg(98_000, 115_000)] },
  },
  {
    uuid: "c0000000-0000-0000-0000-000000000003", name: "Speaker 1", type: "speaker", reviewStatus: "CONFIRMED",
    assetUuid: MEETING, memberCount: 40, model: "redimnet2", personUuid: BOB, personName: "Bob Jones",
    reviewedAt: "2026-09-02T09:00:00Z",
    meta: { totalMs: 61_000, segments: [seg(0, 12_000), seg(20_000, 52_000), seg(70_000, 87_000)] },
  },
  {
    uuid: "c0000000-0000-0000-0000-000000000004", name: "Speaker 3", type: "speaker", reviewStatus: "REJECTED",
    assetUuid: PODCAST, memberCount: 3, model: "redimnet2",
    meta: { totalMs: 4_500, segments: [seg(201_000, 205_500)] },
  },
];

/** The member windows of a speaker: 3 s every 1.5 s across each turn, as the node writes them. */
function windowsOf(cluster) {
  const out = [];
  for (const s of cluster.meta.segments) {
    for (let t = s.startMs; t + 3000 <= s.endMs; t += 1500) out.push({ timeFrom: t, timeTo: t + 3000 });
  }
  return out.map((w, i) => ({ embeddingUuid: `${cluster.uuid}-w${i}`, assetUuid: cluster.assetUuid, ...w }));
}

const sampleUrl = uuid => `/api/v1/persons/${ALICE}/voice-samples/${uuid}/data`;
const VOICE_SAMPLES = [
  {
    uuid: "v1", filename: "alice-town-hall.wav", mimeType: "audio/wav", size: 880_000, url: sampleUrl("v1"), hero: true,
    durationMs: 27_500, sourceAssetUuid: TOWNHALL, sourceClusterUuid: SPEAKERS[0].uuid, sourceTimeFrom: 4_000, sourceTimeTo: 31_500,
    prints: [
      { uuid: "pr1", sampleUuid: "v1", type: "speaker", model: "redimnet2", dimensions: 192, usable: true },
      { uuid: "pr2", sampleUuid: "v1", type: "speaker", model: "ecapa-tdnn", dimensions: 192, usable: true },
    ],
  },
  {
    uuid: "v2", filename: "voicemail.m4a", mimeType: "audio/mp4", size: 120_000, url: sampleUrl("v2"), hero: false,
    durationMs: 9_000,
    prints: [
      { uuid: "pr3", sampleUuid: "v2", type: "speaker", model: "redimnet2", usable: false, error: "no speech found" },
    ],
  },
];

const PERSON_IMAGES = [
  { uuid: "i1", filename: "alice-portrait.jpg", mimeType: "image/jpeg", size: 120_000, url: `/api/v1/persons/${ALICE}/images/i1/data`, avatar: true },
  { uuid: "i2", filename: "alice-office.jpg", mimeType: "image/jpeg", size: 98_000, url: `/api/v1/persons/${ALICE}/images/i2/data`, avatar: false },
];

const json = (body, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });

/**
 * The baseline mocks. Registration order is load-bearing: Playwright matches the most recently
 * registered handler first, so the catch-all goes in before everything it must not shadow.
 */
async function mockRest(page) {
  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/me", route =>
    route.fulfill(json({ uuid: "11111111-1111-1111-1111-111111111111", username: "admin", enabled: true })));

  await page.route(/\/api\/v1\/persons(\?|$)/, route => route.fulfill(json({ data: PERSONS, _metainfo: { totalCount: PERSONS.length } })));
  await page.route(/\/api\/v1\/persons\/[^/]+$/, route => route.fulfill(json({ ...PERSONS[0], heroVoiceUrl: sampleUrl("v1") })));
  await page.route(/\/api\/v1\/persons\/[^/]+\/images$/, route => route.fulfill(json({ data: PERSON_IMAGES })));
  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/[^/]+\/data$/, route => {
    const file = route.request().url().includes("/images/i2/") ? "persons/user-02-woman-glasses.jpg" : "persons/person-01-woman-long-hair.jpg";
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: demoFile(file) });
  });
  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route =>
    route.fulfill(json({ data: [{ ...SPEAKERS[0], reviewStatus: "CONFIRMED", personUuid: ALICE, personName: "Alice Smith" }] })));
  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples$/, route => route.fulfill(json({ data: VOICE_SAMPLES })));
  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples\/[^/]+\/data$/, route =>
    route.fulfill({ status: 200, contentType: "audio/wav", body: WAV }));

  await page.route(/\/api\/v1\/clusters(\?|$)/, route => {
    const url = new URL(route.request().url());
    const type = url.searchParams.get("type");
    const status = url.searchParams.get("status");
    const data = SPEAKERS.filter(c => (!type || c.type === type) && (!status || c.reviewStatus === status));
    return route.fulfill(json({ data, _metainfo: { totalCount: data.length } }));
  });
  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => {
    const uuid = route.request().url().split("/clusters/")[1].split("/members")[0];
    const cluster = SPEAKERS.find(c => c.uuid === uuid);
    const members = cluster ? windowsOf(cluster) : [];
    return route.fulfill(json({ members, total: members.length }));
  });

  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => {
    const uuid = route.request().url().split("/assets/")[1];
    return route.fulfill(json({ uuid, file: { filename: ASSETS[uuid] ?? "recording.mp4", mimeType: "video/mp4", size: 1 } }));
  });
  await page.route(/\/api\/v1\/assets\/[^/]+\/audio-clip/, route =>
    route.fulfill({ status: 200, contentType: "audio/wav", body: WAV }));
}

async function signIn(page, deepLink) {
  await page.goto(`${BASE}${deepLink}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.getByPlaceholder("Username").waitFor({ state: "hidden", timeout: 15_000 });
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
  const shot = async (page, name, { settle = 800 } = {}) => {
    await sleep(settle);
    await page.screenshot({ path: path.join(OUT, name) });
    results.push(`  ✓ ${name}`);
    console.log(`captured ${name}`);
  };

  // --- 1. The Speakers tab, one card opened onto its turns and one playing ---
  if (wanted("speakers-review.png")) {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page, "/ui/detection");
    await page.getByRole("tab", { name: "Speakers", exact: true }).click({ timeout: 15_000 });
    await page.getByTestId("speaker-card").first().waitFor({ timeout: 15_000 });
    // The asset names arrive after the cards.
    await page.getByText("town-hall-q3.mp4").first().waitFor({ timeout: 15_000 });
    const confirmed = page.locator(`[data-testid=speaker-card][data-cluster-id="${SPEAKERS[2].uuid}"]`);
    await confirmed.getByTestId("speaker-expand").click();
    await confirmed.getByTestId("speaker-turn").first().waitFor({ timeout: 15_000 });
    const suggested = page.locator(`[data-testid=speaker-card][data-cluster-id="${SPEAKERS[0].uuid}"]`);
    await suggested.getByTestId("speaker-play-play").click();
    await suggested.getByTestId("speaker-play-audio").waitFor({ timeout: 15_000 });
    await page.mouse.move(0, 0);
    await shot(page, "speakers-review.png", { settle: 1200 });
    await page.close();
  }

  // --- 2. A person's voice samples, below their pictures ---------------------
  if (wanted("person-voice.png")) {
    const page = await context.newPage();
    await mockRest(page);
    await signIn(page, `/ui/persons/${ALICE}`);
    await page.getByTestId("person-voice-sample").first().waitFor({ timeout: 15_000 });
    await page.getByTestId("person-speaker-candidate").first().waitFor({ timeout: 15_000 });
    await page.getByText("town-hall-q3.mp4").first().waitFor({ timeout: 15_000 });
    await page.getByTestId("person-hero-voice-play").click();
    await page.getByTestId("person-hero-voice-audio").waitFor({ timeout: 15_000 });
    await page.mouse.move(0, 0);
    await shot(page, "person-voice.png", { settle: 1200 });
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
