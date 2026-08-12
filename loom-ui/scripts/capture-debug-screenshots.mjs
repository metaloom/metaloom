// Capture pipeline Debug Mode screenshots for the website documentation (docs/pipeline/).
//
// Unlike scripts/capture-ui-screenshots.mjs, this one needs **no demo container and no Cortex
// worker**. It drives the real Loom UI against a fully intercepted API, exactly the way the
// mocked Playwright specs under e2e/ do, and plays the server itself — including pushing the
// `NODE_BREAKPOINT_HELD` frame that puts a node into the held state.
//
// That is not a shortcut, it is the only way to get these particular pictures reliably. A halted
// run is a transient state: to photograph it against a live stack you would have to arm a
// breakpoint and catch the moment a file reaches it, which is a race, and the results shown would
// be whatever that run happened to produce rather than the ones the surrounding prose describes.
// Here every payload on screen is fixed, so the screenshots and the documentation cannot drift
// apart, and re-running this script reproduces them byte for byte.
//
// The node descriptors, however, are the *real* ones — the same snapshot the public pipeline
// editor is staged with — so ports, content types and their colours are not invented here.
//
// Prerequisites: none beyond `npm install` in loom-ui/. A Vite dev server is started
// automatically if one is not already listening.
//
// Usage (from loom-ui/):
//   node scripts/capture-debug-screenshots.mjs
//
// Env overrides:
//   VITE_PORT  (default 3000)
//   OUT_DIR    (default ../website/content/english/docs/pipeline)

import { chromium } from "playwright";
import { fileURLToPath } from "url";
import { spawn } from "child_process";
import path from "path";
import fs from "fs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.VITE_PORT ?? 3000);
const BASE = `http://localhost:${PORT}`;
const OUT = process.env.OUT_DIR
  ? path.resolve(process.env.OUT_DIR)
  : path.resolve(ROOT, "../website/content/english/docs/pipeline");

const DESCRIPTORS = path.resolve(ROOT, "../website/static/pipeline-editor/node-descriptors.json");

fs.mkdirSync(OUT, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Media Analysis";
const RUN_UUID = "run-0000-0000-0000-000000000001";
const ITEM_UUID = "item-0000-0000-0000-000000000001";

// ---------------------------------------------------------------------------
// Fixtures produced by the real nodes
// ---------------------------------------------------------------------------

/**
 * Everything below comes out of `DebugScreenshotFixtureGenerator` (integration-test), which runs the
 * actual ThumbnailNode and FacedetectNode over real footage with previews switched on and writes
 * what they produced into ./fixtures.
 *
 * That indirection is the point. An earlier version of this script wrote its payloads by hand and
 * got three separate things wrong: it showed the thumbnail node emitting `PROCESSED` (it emits
 * `DONE`), ran that node over a JPEG (it is a video contact-sheet generator), and drew detection
 * boxes into a hand-painted gradient — advertising an overlay the UI did not yet have. Nothing here
 * is written by hand any more, so the screenshots cannot describe a product that does not exist.
 */
const FIXTURES = path.resolve(__dirname, "fixtures");
const manifest = JSON.parse(fs.readFileSync(path.join(FIXTURES, "manifest.json"), "utf8"));

const MEDIA_PATH = `/media/library/${manifest.source}`;
const FACES = manifest.facedetectWide;
const THUMB = manifest.thumbnail;

const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Media Folder", position: { x: 0, y: 120 }, data: {} },
    { id: "hash", type: "sha512", label: "SHA-512", position: { x: 330, y: 0 }, data: {} },
    { id: "thumb", type: "thumbnail", label: "Thumbnail", position: { x: 330, y: 150 }, data: {} },
    { id: "faces", type: "facedetect", label: "Face Detection", position: { x: 680, y: 150 }, data: {} },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "hash", targetPort: "media", branch: "ANY" },
    { id: "e2", source: "src", sourcePort: "media", target: "thumb", targetPort: "media", branch: "ANY" },
    // facedetect takes the clip on its `video` port: this source is a video, and the two paths
    // behave differently enough that wiring the wrong one would misdescribe the node.
    { id: "e3", source: "src", sourcePort: "media", target: "faces", targetPort: "video", branch: "ANY" },
  ],
};

function pipeline() {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "aaaaaaaa-0000-0000-0000-000000000001",
    versionNumber: 4,
    name: PIPELINE_NAME,
    description: "Hash, contact sheet and face detection over a video library",
    definition: DEFINITION,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

const RUN = {
  uuid: RUN_UUID,
  pipelineUuid: PIPELINE_UUID,
  started: "2026-07-20T10:00:00Z",
  status: "RUNNING",
  mediaCount: 42,
  successCount: 17,
  failureCount: 0,
  dryRun: false,
};

const ITEM = {
  uuid: ITEM_UUID,
  runUuid: RUN_UUID,
  itemSeq: 0,
  mediaPath: MEDIA_PATH,
  state: "SUCCESS",
};

const ORIGIN = { itemId: ITEM_UUID, seq: 0, total: 1 };

/** The URL the list route advertises for one preview key, exactly as PipelineModelBuilder builds it. */
function previewUrl(taskUuid, key) {
  return `/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}`
    + `/tasks/${taskUuid}/previews/${encodeURIComponent(key)}`;
}

/** The image files the generator wrote, keyed the way the previews map keys them. */
function fixtureImages() {
  const images = new Map();
  images.set("thumbnail", { file: "thumbnail-grid.jpg", key: "thumbnail", task: "task-thumb" });
  for (const name of FACES.images) {
    // `wide-detections.jpg` is the port-level frame; `wide-detections-3.jpg` is element 3.
    const key = name.replace(/^wide-/, "").replace(/\.jpg$/, "").replace(/^detections-(\d+)$/, "detections#$1");
    images.set(key, { file: name, key, task: "task-faces" });
  }
  return images;
}

const IMAGES = fixtureImages();

function previewsFor(taskUuid) {
  const previews = {};
  for (const { file, key, task } of IMAGES.values()) {
    if (task !== taskUuid) continue;
    const size = imageSize(path.join(FIXTURES, file));
    previews[key] = { mimeType: "image/jpeg", width: size.width, height: size.height, url: previewUrl(taskUuid, key) };
  }
  return previews;
}

/** Read a JPEG's dimensions out of its SOF marker, so the caption states the real size. */
function imageSize(file) {
  const buf = fs.readFileSync(file);
  let offset = 2;
  while (offset < buf.length) {
    if (buf[offset] !== 0xff) { offset++; continue; }
    const marker = buf[offset + 1];
    // SOF0..SOF3 and SOF5..SOF15 carry the frame dimensions; everything else is skipped by length.
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { height: buf.readUInt16BE(offset + 5), width: buf.readUInt16BE(offset + 7) };
    }
    offset += 2 + buf.readUInt16BE(offset + 2);
  }
  return { width: 0, height: 0 };
}

const TASKS = [
  {
    uuid: "task-src", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "src", nodeKind: "filesystem-source", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 4,
    outputs: {
      media: {
        contentType: "media/video", cardinality: "ONE",
        elements: [{ origin: ORIGIN, value: MEDIA_PATH }],
      },
    },
  },
  {
    uuid: "task-hash", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "hash", nodeKind: "sha512", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 31,
    outputs: {
      hash: {
        contentType: "hash/sha512", cardinality: "ONE",
        elements: [{ origin: ORIGIN, value: "0f8ef1c9ab34de5678901243b7c0e19f5a2d8c4b6e11f0a9d3c7b58e2f46a0d1" }],
      },
    },
  },
  {
    uuid: "task-thumb", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "thumb", nodeKind: "thumbnail", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 148,
    outputs: {
      thumbnail: {
        contentType: "artifact/image", cardinality: "ONE",
        elements: [{ origin: ORIGIN, value: THUMB.path }],
      },
      // `DONE` is what ThumbnailNode emits. This said `PROCESSED` for as long as it was written by hand.
      flag: {
        contentType: "scalar/string", cardinality: "ONE",
        elements: [{ origin: ORIGIN, value: THUMB.flag }],
      },
    },
    previews: previewsFor("task-thumb"),
  },
  {
    uuid: "task-faces", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "faces", nodeKind: "facedetect", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 1402,
    outputs: {
      // Encoded JSON strings, because a detection/* port declares String.class. The UI parses them
      // back; handing it objects here would hide that it has to.
      detections: {
        contentType: "detection/face", cardinality: "MANY",
        elements: FACES.elements.map((element, seq) => ({
          origin: { itemId: ITEM_UUID, seq, total: FACES.elements.length },
          value: JSON.stringify(element),
        })),
      },
      face_count: {
        contentType: "scalar/integer", cardinality: "ONE",
        elements: [{ origin: ORIGIN, value: FACES.faceCount }],
      },
      flag: {
        contentType: "scalar/string", cardinality: "ONE",
        elements: [{ origin: ORIGIN, value: FACES.flag }],
      },
    },
    previews: { ...previewsFor("task-faces"), detections: { ...previewsFor("task-faces").detections, markdown: FACES.markdown } },
  },
];

const HELD = [{ nodeId: "faces", itemUuid: ITEM_UUID, elementSeq: 0 }];

// ---------------------------------------------------------------------------
// Backend interception
// ---------------------------------------------------------------------------

/**
 * Intercept every REST call the editor makes.
 *
 * The armed/held state is mutable and starts empty on purpose. Serving a held run from the first
 * request would mean the "here is what each node produced" screenshot already had an amber ring
 * and a transport bar in it, and the two pictures the documentation contrasts would be the same
 * picture. The capture arms the breakpoint, then flips this state, in the order a reader does.
 */
async function mockBackend(page) {
  const state = { armed: [], held: [] };
  const descriptors = fs.readFileSync(DESCRIPTORS, "utf8");
  const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  // Catch-all first; the specific routes registered after it take precedence.
  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: descriptors }));
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill(json({ data: [pipeline()], _metainfo: { totalCount: 1 } })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill(json({ data: [pipeline()], _metainfo: { totalCount: 1 } })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route => route.fulfill(json({ data: [RUN] })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items`, route =>
    route.fulfill(json({ data: [ITEM] })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`, route =>
    route.fulfill(json({ data: TASKS })));
  // One route for every preview key, port-level and per-element alike. The key is URL-encoded in
  // the path (`detections%230`), which is exactly how PipelineModelBuilder emits it.
  for (const { file, key } of IMAGES.values()) {
    const bytes = fs.readFileSync(path.join(FIXTURES, file));
    await page.route(`**/previews/${encodeURIComponent(key)}`, route =>
      route.fulfill({ status: 200, contentType: "image/jpeg", body: bytes }));
  }
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/breakpoints`, route => {
    if (route.request().method() === "PUT") {
      state.armed = JSON.parse(route.request().postData() || "{}").nodeIds ?? [];
    }
    route.fulfill(json({ nodeIds: state.armed, held: state.held }));
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/breakpoints/*/continue`, route =>
    route.fulfill(json({ message: "Released 1 held execution of node thumb" })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/steps`, route =>
    route.fulfill(json({ nodeIds: state.armed, held: [] })));

  return state;
}

/**
 * Intercept the events socket so this script can play the server.
 *
 * `routeWebSocket` returns a promise that must be awaited *before* the page navigates: a route
 * registered after the app has opened its socket intercepts nothing.
 */
async function mockEventsSocket(page) {
  const sockets = [];
  await page.routeWebSocket(/\/pipelines\/events\/ws/, ws => sockets.push(ws));
  return async () => {
    for (let i = 0; i < 150 && sockets.length === 0; i++) await sleep(100);
    if (!sockets.length) throw new Error("the UI never opened its events socket");
    return sockets[0];
  };
}

function push(ws, type, over = {}) {
  ws.send(JSON.stringify({ type, pipelineName: PIPELINE_NAME, timestamp: Date.now(), ...over }));
}

// ---------------------------------------------------------------------------
// Dev server
// ---------------------------------------------------------------------------

/**
 * Answering, not merely bound.
 *
 * A TCP probe is the obvious check and the wrong one: Vite binds to the `localhost` *name*, which
 * on a dual-stack host may be `::1` alone, so a connect to 127.0.0.1 reports a server that is
 * plainly running as absent. Ask over HTTP and let the resolver do what the browser will do.
 */
async function serverAnswering(port) {
  try {
    await fetch(`http://localhost:${port}/ui/`, { signal: AbortSignal.timeout(1500) });
    return true;
  } catch {
    return false;
  }
}

async function ensureDevServer() {
  if (await serverAnswering(PORT)) {
    console.log(`using the Vite server already listening on ${PORT}`);
    return null;
  }
  console.log(`starting Vite on ${PORT}…`);
  // `npx` hangs in this environment — call the installed binary directly.
  const bin = path.resolve(ROOT, "node_modules/.bin/vite");
  const proc = spawn(bin, ["--port", String(PORT), "--strictPort"], { cwd: ROOT, stdio: "ignore" });
  for (let i = 0; i < 100; i++) {
    await sleep(300);
    if (await serverAnswering(PORT)) return proc;
  }
  proc.kill();
  throw new Error(`Vite did not come up on ${PORT}`);
}

// ---------------------------------------------------------------------------
// Capture
// ---------------------------------------------------------------------------

async function main() {
  const vite = await ensureDevServer();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1000 },
    deviceScaleFactor: 2,
  });
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
      // Start every capture from a known state rather than whatever a previous run persisted.
      localStorage.removeItem("loom-ui-pipeline-debug");
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

  const nodeBounds = () => page.evaluate(() => {
    const boxes = [...document.querySelectorAll(".react-flow__node")].map(n => n.getBoundingClientRect());
    if (!boxes.length) return null;
    return {
      left: Math.min(...boxes.map(b => b.left)),
      top: Math.min(...boxes.map(b => b.top)),
      right: Math.max(...boxes.map(b => b.right)),
      bottom: Math.max(...boxes.map(b => b.bottom)),
    };
  });

  /**
   * Re-fit the graph to the canvas.
   *
   * `fitView` alone is not enough. It fits the *nodes as React Flow measured them*, and a result
   * strip carrying a long file path makes a card wider after that measurement — so the rightmost
   * node ends up past the edge of a canvas that was correctly fitted a moment earlier. Zoom out
   * until the nodes actually are inside it.
   */
  const fitGraph = async () => {
    await page.locator(".react-flow__controls-fitview").click();
    await sleep(600);
    for (let i = 0; i < 6; i++) {
      const canvas = await page.getByTestId("pipeline-canvas").boundingBox();
      const b = await nodeBounds();
      if (!b) return;
      if (b.left >= canvas.x && b.right <= canvas.x + canvas.width) return;
      await page.locator(".react-flow__controls-zoomout").click();
      await sleep(350);
      await page.locator(".react-flow__controls-fitview").click();
      await sleep(450);
    }
  };

  /**
   * Photograph the canvas alone, cropped to the graph.
   *
   * The result strips print a port name and a value on one line at about 9px. In a screenshot of
   * the whole window those lines survive at full resolution but not on a documentation page, which
   * scales the image down to a column width — and a strip you cannot read is the one thing these
   * pictures exist to show. The toolbar band is kept, because the transport controls are part of
   * what is being described.
   */
  const shotCanvas = async (name, { settle = 900, pad = 28 } = {}) => {
    await sleep(settle);
    // Fit last, not first: result strips and a held badge change how wide a card is, and a graph
    // fitted before they arrived is a graph whose right-hand node is now off the canvas.
    await fitGraph();
    const canvas = await page.getByTestId("pipeline-canvas").boundingBox();
    // React Flow's fitView will not zoom past its own ceiling, so a four-node graph leaves most
    // of a 1000px canvas empty however well it is fitted. Crop to the nodes instead.
    const nodes = await nodeBounds();
    const clip = nodes
      ? {
          x: Math.max(canvas.x, nodes.left - pad),
          y: Math.max(canvas.y, nodes.top - pad),
          width: Math.min(canvas.x + canvas.width, nodes.right + pad) - Math.max(canvas.x, nodes.left - pad),
          height: Math.min(canvas.y + canvas.height, nodes.bottom + pad) - Math.max(canvas.y, nodes.top - pad),
        }
      : canvas;
    await shot(name, { settle: 0, clip });
  };

  /**
   * Photograph the result dialog, cropped to the part of it that has content.
   *
   * The dialog is a fixed 80vh, sized for the largest thing it may have to show — a full-frame
   * image, a hundred-row table — so a three-row table leaves two thirds of it empty. On a
   * documentation page that whitespace reads as a rendering fault rather than as a roomy dialog,
   * so the crop follows the content instead of the frame.
   *
   * The crop measures `.MuiDialog-paper`, not the testid: that is on the Dialog root, which
   * spans the whole viewport, and clipping to it would photograph the page rather than the panel.
   */
  const shotDialog = async (name, { settle = 900 } = {}) => {
    await sleep(settle);
    const box = await page.locator(".MuiDialog-paper").boundingBox();
    const contentBottom = await page.evaluate(() => {
      const view = document.querySelector('[data-testid^="result-view-"]');
      if (!view) return null;
      const child = view.firstElementChild;
      const r = (child ?? view).getBoundingClientRect();
      return r.bottom;
    });
    const bottom = contentBottom ? Math.min(box.y + box.height, contentBottom + 28) : box.y + box.height;
    await shot(name, { settle: 0, clip: { x: box.x, y: box.y, width: box.width, height: bottom - box.y } });
  };

  const backend = await mockBackend(page);
  const waitForSocket = await mockEventsSocket(page);

  // ---- Login and open the editor ----
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  // Exactly one click, and only when the editor is not already open. Clicking Pipelines a second
  // time is a navigation *out of* the editor as far as the unsaved-changes guard is concerned, and
  // the guard answers it with the discard dialog — which then sits over the canvas and swallows
  // every click the rest of this script makes.
  if (!(await page.getByTestId("pipeline-canvas").isVisible().catch(() => false))) {
    await page.getByRole("button", { name: "Pipelines" }).first().click();
  }
  await page.getByTestId("pipeline-canvas").waitFor({ timeout: 20_000 });
  await page.getByTestId("pipeline-node-thumb").waitFor({ timeout: 20_000 });
  await sleep(1200);
  await fitGraph();

  // ---- 1. Debug on ----
  // Nothing is captured with it off: docs/ui/ already shows the plain editor, and the claim the
  // documentation makes — that debug mode changes nothing until you turn it on — is better made
  // by that existing screenshot than by a near-duplicate of it here.
  await page.getByTestId("pipeline-debug-toggle").click();
  await sleep(500);

  // ---- 2. Results on the canvas: pick the run item ----
  await page.getByTestId(`pipeline-run-row-${RUN_UUID}`).click();
  await page.getByTestId("pipeline-run-detail-drawer").waitFor({ timeout: 8_000 });
  await page.getByTestId("pipeline-run-item").first().click();
  await sleep(900);
  await page.getByTestId("pipeline-run-detail-close").click();
  await page.getByTestId("pipeline-run-detail-drawer").waitFor({ state: "hidden", timeout: 8_000 });
  // The cards grow a result strip, so the graph needs re-fitting before it is photographed.
  await fitGraph();
  await shotCanvas("debug-results.png", { settle: 1400 });

  // ---- 3. A held node ----
  // The gutter dot arms the halt; the server then reports the node as held. Dispatched rather
  // than clicked because React Flow's minimap floats over the bottom-right of the canvas.
  await page.getByTestId("pipeline-node-breakpoint-faces").dispatchEvent("click");
  await sleep(400);
  backend.held = HELD;
  const ws = await waitForSocket();
  push(ws, "NODE_BREAKPOINT_HELD", {
    nodeId: "faces", itemUuid: ITEM_UUID, elementSeq: 0, mediaPath: MEDIA_PATH,
  });
  await page.getByTestId("pipeline-debug-held-count").waitFor({ timeout: 8_000 });
  // Full window here: a halted run is the one state worth showing in the context of the whole
  // application, since the run banner and the log are part of reading it.
  await shot("debug-held-full.png", { settle: 1200 });
  await shotCanvas("debug-held.png", { settle: 200 });

  // ---- 4. One result, full size ----
  // The produced contact sheet: the case the whole preview channel exists for, because the port
  // itself carries only a path on the worker that wrote it.
  await page.getByTestId("pipeline-node-thumb").getByTestId("node-result-port-thumbnail")
    .dispatchEvent("click");
  await page.getByTestId("pipeline-result-detail").waitFor({ timeout: 8_000 });
  await shotDialog("debug-detail-image.png", { settle: 1400 });
  await page.getByTestId("pipeline-result-detail-close").click();
  await sleep(600);

  // ---- 5. A node describing its own output as a table ----
  // Opens on Markdown: a node's own description of its output outranks every content-type default.
  await page.getByTestId("pipeline-node-faces").getByTestId("node-result-port-detections")
    .dispatchEvent("click");
  await page.getByTestId("pipeline-result-detail").waitFor({ timeout: 8_000 });
  await shotDialog("debug-detail-table.png", { settle: 1200 });

  // ---- 6. The same detections as pictures ----
  // The Image tab of a detection port: the frame the boxes were measured on, with the boxes drawn
  // over it, and one crop per detected face underneath. This is the answer to the two questions a
  // table cannot answer — where in the picture, and who.
  await page.getByTestId("result-tab-image").click();
  await page.getByTestId("result-element-previews").waitFor({ timeout: 8_000 });
  await shotDialog("debug-detail-faces.png", { settle: 1400 });
  await page.getByTestId("pipeline-result-detail-close").click();

  console.log("\n--- Screenshot summary ---\n" + results.join("\n"));
  console.log(`\nOutput dir: ${OUT}`);

  await browser.close();
  if (vite) vite.kill();
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
