// Capture the debug view of every node, for the node documentation pages.
//
//   website/content/english/docs/nodes/<page>/debug.png         — the node card with its results
//   website/content/english/docs/nodes/<page>/debug-detail.png  — one result opened, where there is
//                                                                 a picture or a description to open
//
// What makes these honest
// -----------------------
// Every payload on screen comes out of `loom-ui/scripts/fixtures/nodes/<kind>/fixture.json`, which
// `DocsFixtureGenerator` (integration-test) writes by running the actual node over actual media and
// mapping the answer through the actual wire mapper. This script contributes the *pictures* and
// nothing else — it never invents a port, a value or a state. A kind with no fixture is skipped and
// reported, never faked.
//
// Why the backend is mocked rather than live
// ------------------------------------------
// The same reason capture-debug-screenshots.mjs mocks it: thirty-odd nodes, several of which need a
// GPU service, cannot be photographed reproducibly by starting a stack and racing it. Fixed payloads
// mean a re-run reproduces the pictures and the pages cannot drift from them.
//
// Usage (from loom-ui/):
//   node scripts/capture-node-screenshots.mjs [pageName …]
//
// Env overrides:
//   VITE_PORT  (default 3000)
//   OUT_DIR    (default ../website/content/english/docs/nodes)

import { chromium } from "playwright";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";

import { ensureDevServer } from "./lib/devserver.mjs";
import { PAGES } from "./node-capture-plan.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const PORT = Number(process.env.VITE_PORT ?? 3000);
const BASE = `http://localhost:${PORT}`;
const OUT = process.env.OUT_DIR
  ? path.resolve(process.env.OUT_DIR)
  : path.resolve(ROOT, "../website/content/english/docs/nodes");
const FIXTURES = path.resolve(__dirname, "fixtures/nodes");
const DESCRIPTORS = path.resolve(ROOT, "../website/static/pipeline-editor/node-descriptors.json");

const PIPELINE_UUID = "33333333-3333-3333-3333-333333333333";
const RUN_UUID = "run-0000-0000-0000-000000000003";
const ITEM_UUID = "item-0000-0000-0000-000000000003";

/**
 * The only backends allowed to be something other than the real thing.
 *
 * Both are cloud APIs that cannot be stood up locally at all, and in both cases the stub replaces
 * Google or Microsoft while every line of our own code below it runs for real. Everywhere else a
 * stub means the picture shows a decision nothing made, and several node tests do inject canned
 * clients — one of them paints its own gradient — so those must never become documentation.
 */
const STUB_ALLOWLIST = new Set(["gdrive-source", "onedrive-source"]);

const sleep = ms => new Promise(r => setTimeout(r, ms));

const only = process.argv.slice(2);
const plan = (only.length ? PAGES.filter(p => only.includes(p.page)) : PAGES);

function loadFixture(kind) {
  const file = path.join(FIXTURES, kind, "fixture.json");
  if (!fs.existsSync(file)) return null;
  const fixture = JSON.parse(fs.readFileSync(file, "utf8"));
  if (fixture.backend && fixture.backend !== "real" && !STUB_ALLOWLIST.has(kind)) {
    throw new Error(`${kind}: fixture records backend "${fixture.backend}" — `
      + "a stubbed backend must not become a documentation screenshot");
  }
  return fixture;
}

/** The pipeline shown in the picture: one node, the one the page is about. */
function pipeline(entry, fixture) {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "cccccccc-0000-0000-0000-000000000001",
    versionNumber: 1,
    name: entry.page,
    description: `A single ${entry.kind} node, as the debugger shows it`,
    definition: {
      nodes: [{
        id: entry.kind,
        type: entry.kind,
        label: entry.page,
        position: { x: 0, y: 0 },
        // The four dynamic-port kinds resolve their ports from saved options, so the card would be
        // portless without the options the fixture was produced under.
        data: fixture.nodeData ?? {},
      }],
      edges: [],
    },
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

function task(entry, fixture) {
  return {
    uuid: "task-0000-0000-0000-000000000001",
    runUuid: RUN_UUID,
    itemUuid: ITEM_UUID,
    nodeId: entry.kind,
    nodeKind: entry.kind,
    elementSeq: 0,
    state: fixture.state ?? "COMPLETED",
    attempt: 1,
    maxAttempts: 3,
    durationMs: fixture.durationMs ?? 0,
    errorMessage: fixture.message ?? null,
    outputs: fixture.outputs ?? {},
    // The URL shape the server emits: the port id, URL-encoded, under the task's preview route.
    previews: Object.fromEntries(Object.entries(fixture.previews ?? {}).map(([key, meta]) => [
      key,
      {
        ...(meta.file ? {
          mimeType: meta.mimeType,
          width: meta.width,
          height: meta.height,
          url: `/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}`
            + `/tasks/task-0000-0000-0000-000000000001/previews/${encodeURIComponent(key)}`,
        } : {}),
        ...(meta.markdown ? { markdown: meta.markdown } : {}),
        ...(meta.frame != null ? { frame: meta.frame } : {}),
        ...(meta.skippedReason ? { skippedReason: meta.skippedReason } : {}),
      },
    ])),
  };
}

const RUN = {
  uuid: RUN_UUID,
  pipelineUuid: PIPELINE_UUID,
  started: "2026-07-20T10:00:00Z",
  status: "COMPLETED",
  mediaCount: 1,
  successCount: 1,
  failureCount: 0,
  dryRun: false,
};

function item(fixture) {
  return {
    uuid: ITEM_UUID,
    runUuid: RUN_UUID,
    mediaPath: fixture.source?.path ?? "/media/library/example",
    state: "COMPLETED",
    started: "2026-07-20T10:00:01Z",
    finished: "2026-07-20T10:00:02Z",
  };
}

async function mockBackend(page, state) {
  const descriptors = fs.readFileSync(DESCRIPTORS, "utf8");
  const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/api/v1/**", route => route.fulfill(json({ data: [] })));
  await page.route("**/api/v1/login", route => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: descriptors }));
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill(json({ data: [state.pipeline], _metainfo: { totalCount: 1 } })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill(json({ data: [state.pipeline], _metainfo: { totalCount: 1 } })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route => route.fulfill(json({ data: [RUN] })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items`, route =>
    route.fulfill(json({ data: [state.item] })));
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`, route =>
    route.fulfill(json({ data: [state.task] })));
  // Preview bytes, served from the files the generator wrote beside the fixture.
  await page.route("**/previews/*", route => {
    const key = decodeURIComponent(route.request().url().split("/previews/")[1]);
    const bytes = state.previewBytes[key];
    if (!bytes) return route.fulfill({ status: 404, body: "" });
    route.fulfill({ status: 200, contentType: state.previewTypes[key] ?? "image/jpeg", body: bytes });
  });
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/breakpoints`, route =>
    route.fulfill(json({ nodeIds: [], held: [] })));
}

async function main() {
  const vite = await ensureDevServer(ROOT, PORT);
  const browser = await chromium.launch({ headless: true });

  const done = [], skipped = [];
  for (const entry of plan) {
    const fixture = loadFixture(entry.kind);
    if (!fixture) {
      skipped.push(entry.page);
      continue;
    }
    try {
      await capture(browser, entry, fixture);
      done.push(entry.page);
    } catch (e) {
      console.error(`  ✗ ${entry.page}: ${e.message}`);
      skipped.push(entry.page);
    }
  }

  await browser.close();
  if (vite) vite.kill();

  console.log(`\ncaptured ${done.length}/${plan.length} node debug views`);
  if (skipped.length) {
    // Never silent. A page without a debug view is a real gap and the guard counts it; hiding it
    // here would let the count drift without anyone noticing.
    console.log(`no fixture yet (run DocsFixtureGenerator for these): ${skipped.join(", ")}`);
  }
}

async function capture(browser, entry, fixture) {
  const previewBytes = {}, previewTypes = {};
  for (const [key, meta] of Object.entries(fixture.previews ?? {})) {
    if (!meta.file) continue;
    previewBytes[key] = fs.readFileSync(path.join(FIXTURES, entry.kind, meta.file));
    previewTypes[key] = meta.mimeType ?? "image/jpeg";
  }

  const context = await browser.newContext({
    viewport: { width: 1400, height: 900 },
    deviceScaleFactor: 2,
    // The node card's "active" dot has a blink keyframe; without this the same state photographs
    // differently every run and every regeneration reads as a change.
    reducedMotion: "reduce",
  });
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
      localStorage.removeItem("loom-ui-pipeline-debug");
    } catch (e) { /* ignore */ }
  });
  const page = await context.newPage();
  await mockBackend(page, {
    pipeline: pipeline(entry, fixture),
    task: task(entry, fixture),
    item: item(fixture),
    previewBytes,
    previewTypes,
  });

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await page.getByTestId("pipeline-canvas").waitFor({ timeout: 20_000 });
  await page.getByTestId(`pipeline-node-${entry.kind}`).waitFor({ timeout: 20_000 });
  await sleep(900);

  // Debug mode is what puts results on the cards at all.
  await page.getByTestId("pipeline-debug-toggle").click();
  await sleep(400);
  await page.getByTestId(`pipeline-run-row-${RUN_UUID}`).click();
  await page.getByTestId("pipeline-run-detail-drawer").waitFor({ timeout: 8_000 });
  await page.getByTestId("pipeline-run-item").first().click();
  await sleep(800);
  await page.getByTestId("pipeline-run-detail-close").click();
  await page.getByTestId("pipeline-run-detail-drawer").waitFor({ state: "hidden", timeout: 8_000 });
  await sleep(700);

  const dir = path.join(OUT, entry.page);
  fs.mkdirSync(dir, { recursive: true });

  if (entry.view === "run-detail") {
    await captureRunDetail(page, entry, dir);
    console.log(`  ✓ ${entry.page}/debug.png (run detail)`);
    await context.close();
    return;
  }

  // React Flow's own chrome is hidden rather than cropped around. The minimap floats over the
  // bottom-right of the canvas and the zoom controls over the bottom-left, and with a single node
  // fitted to the middle of the canvas either can land on top of the card. The existing debug
  // captures crop them away; there is nothing to crop away from here, because the picture *is* the
  // card. None of it is part of what the page is showing.
  await page.addStyleTag({
    content: ".react-flow__minimap, .react-flow__controls, .react-flow__attribution { display: none !important; }",
  });

  // Fit last, and measure after fitting. A result strip carrying a long file path widens the card
  // *after* React Flow measured it, so a box read before the fit is the box of a narrower card and
  // the picture comes out with its right-hand side sliced off.
  await page.locator(".react-flow__controls-fitview").dispatchEvent("click").catch(() => {});
  await sleep(600);

  const card = await page.getByTestId(`pipeline-node-${entry.kind}`).boundingBox();
  if (!card) throw new Error("the node card never rendered");
  const canvas = await page.getByTestId("pipeline-canvas").boundingBox();
  const pad = 22;
  // Clamped to the canvas: a card fitted at the zoom ceiling can still start left of it, and a clip
  // that runs outside the viewport is silently truncated rather than rejected.
  const x = Math.max(canvas.x, card.x - pad);
  const y = Math.max(canvas.y, card.y - pad);
  await page.screenshot({
    path: path.join(dir, "debug.png"),
    clip: {
      x, y,
      width: Math.min(card.x + card.width + pad, canvas.x + canvas.width) - x,
      height: Math.min(card.y + card.height + pad, canvas.y + canvas.height) - y,
    },
  });

  // One result opened, when there is something worth opening: an image, or the node's own
  // description of its output. A port carrying a single scalar adds nothing over the strip.
  //
  // Every candidate is tried in order, not just the first: `NodeResultStrip` caps the card at
  // MAX_ROWS ports and collapses the rest into a "+n more" chip that is not clickable, so a node
  // whose richest port sorts past the cap has nothing to click there. `sam2` is the case — it emits
  // mask_count, flag and segments before its two image ports — and taking only the first candidate
  // produced no detail picture at all, silently.
  const richPorts = Object.entries(fixture.previews ?? {})
    .filter(([key, meta]) => !key.includes("#") && (meta.file || meta.markdown))
    .map(([key]) => key);
  let detail = false;
  for (const rich of richPorts) {
    const port = page.getByTestId(`pipeline-node-${entry.kind}`).getByTestId(`node-result-port-${rich}`);
    if (await port.count()) {
      await port.dispatchEvent("click");
      await page.getByTestId("pipeline-result-detail").waitFor({ timeout: 8_000 });
      await sleep(1100);
      // Narrow the window before measuring, so the dialog sizes itself to the payload instead of to
      // a 1400 px canvas. The dialog is responsive and takes what the viewport gives it, so a table
      // — objectdetect's 33 detections are the worst case — otherwise photographs with a field of
      // empty background twice as wide as the table itself. Resizing the window is honest in a way
      // that clipping the picture is not: the header, the chip and the close button stay whole,
      // because this is what the dialog genuinely looks like in a narrower window.
      const wanted = await page.evaluate(() => {
        const view = document.querySelector('[data-testid^="result-view-"]');
        const child = view?.firstElementChild ?? view;
        if (!child) return null;
        // The container is a block and spans whatever it is given, so its width says nothing — and
        // neither does a Markdown table's, because it stretches to 100% while its columns stay
        // content-sized. What has a real width is the far edge of the last cell, or the image.
        const box = child.getBoundingClientRect();
        const cells = [...child.querySelectorAll("th, td")].map(n => n.getBoundingClientRect().right);
        const others = [...child.querySelectorAll("img, pre")]
          .map(n => n.getBoundingClientRect().width).filter(w => w > 0);
        if (cells.length) return Math.ceil(Math.max(...cells) - box.left);
        return Math.ceil(others.length ? Math.max(...others) : box.width);
      });
      if (wanted) {
        await page.setViewportSize({ width: Math.max(720, Math.min(1400, wanted + 190)), height: 900 });
        await sleep(500);
      }

      const box = await page.locator(".MuiDialog-paper").boundingBox();
      // Crop to the content rather than to the fixed 80vh frame, so a short payload is not
      // photographed with half a dialog of empty space under it.
      const bottom = await page.evaluate(() => {
        const view = document.querySelector('[data-testid^="result-view-"]');
        const child = view?.firstElementChild ?? view;
        return child ? child.getBoundingClientRect().bottom : null;
      });
      const height = bottom ? Math.min(box.height, bottom - box.y + 28) : box.height;
      await page.screenshot({
        path: path.join(dir, "debug-detail.png"),
        clip: { x: box.x, y: box.y, width: box.width, height },
      });
      detail = true;
      break;
    }
  }

  console.log(`  ✓ ${entry.page}/debug.png${detail ? " + debug-detail.png" : ""}`);
  await context.close();
}

/**
 * The debug view for a node that has no output ports.
 *
 * `NodeResultStrip` returns null when a task carries no outputs, so the dedup card in debug mode is
 * a title over an empty body — a truthful picture of nothing. What the product actually shows for
 * these nodes is the detail sidebar's Results tab: the task's state, how long it took, and the
 * explicit "no outputs" line. That is the node's real shape, so it is what this photographs.
 */
async function captureRunDetail(page, entry, dir) {
  // Dispatched rather than clicked, for the same reason the config capture does it: React Flow's
  // minimap and zoom controls float over the canvas and swallow a pointer event aimed underneath.
  const node = page.getByTestId(`pipeline-node-${entry.kind}`);
  await node.dispatchEvent("click");
  const panel = page.getByTestId("pipeline-node-detail");
  await panel.waitFor({ timeout: 8_000 });
  await page.getByRole("tab", { name: "Results", exact: true }).click();
  // The tab renders from the inspected item, which the run-detail drawer selected above; without
  // that selection this shows the "pick an item" placeholder rather than the task.
  await page.getByTestId("node-results").waitFor({ timeout: 8_000 });
  await sleep(500);

  const box = await panel.boundingBox();
  const content = await page.evaluate(() => {
    const results = document.querySelector('[data-testid="node-results"]');
    return results ? results.getBoundingClientRect().bottom : null;
  });
  const height = content ? Math.min(box.height, content - box.y + 20) : box.height;
  await page.screenshot({
    path: path.join(dir, "debug.png"),
    clip: { x: box.x, y: box.y, width: box.width, height },
  });
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
