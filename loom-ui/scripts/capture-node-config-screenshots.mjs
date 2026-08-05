// Capture the configuration panel of every node, for the node documentation pages.
//
// One picture per page: `website/content/english/docs/nodes/<page>/config.png`.
//
// Why this is its own script
// --------------------------
// Its only input is `website/static/pipeline-editor/node-descriptors.json` — the same snapshot the
// public pipeline editor is staged with. No fixtures, no Java, no natives, no services, no run and
// no websocket: the settings panel is built entirely from `descriptor.parameters`, so a node whose
// model server is unreachable still has a perfectly real configuration form. Coupling this to the
// debug-view capture would make all 34 of these wait on 34 fixtures, when they are precisely the
// ones that can all be taken today.
//
// It also wants the opposite viewport policy — tall and narrow, resized per node to whatever that
// node's parameter list needs — which would fight the debug loop's fixed canvas.
//
// What it deliberately does not do
// --------------------------------
// It does not widen the 280px panel to get a friendlier aspect ratio. That would photograph a UI
// nobody has. The panel is narrow in the product, so it is narrow here, and the doc pages give the
// image an explicit width instead.
//
// Usage (from loom-ui/):
//   node scripts/capture-node-config-screenshots.mjs [pageName …]
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
const DESCRIPTORS = path.resolve(ROOT, "../website/static/pipeline-editor/node-descriptors.json");

const PIPELINE_UUID = "22222222-2222-2222-2222-222222222222";

const sleep = ms => new Promise(r => setTimeout(r, ms));

const only = process.argv.slice(2);
const plan = only.length ? PAGES.filter(p => only.includes(p.page)) : PAGES;
if (!plan.length) {
  console.error(`no pages matched ${only.join(", ")}`);
  process.exit(1);
}

/**
 * One pipeline holding one node of every kind we want a picture of.
 *
 * Laid out on a wide grid and never fitted to the canvas: nothing here is photographed from the
 * canvas, the nodes exist only so that each one can be clicked to open its panel. They are spread
 * out anyway so that clicking one cannot land on another.
 */
function pipeline() {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "bbbbbbbb-0000-0000-0000-000000000001",
    versionNumber: 1,
    name: "Node reference",
    description: "One node of every kind, for the documentation's configuration screenshots",
    definition: {
      nodes: plan.map((entry, i) => ({
        id: entry.page,
        type: entry.kind,
        label: entry.page,
        position: { x: (i % 6) * 260, y: Math.floor(i / 6) * 220 },
        // Carried through to the panel: the four dynamic-port kinds resolve their ports from saved
        // options, and `filter` in particular has nothing to show without its buckets.
        data: entry.nodeData ?? {},
      })),
      edges: [],
    },
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

async function mockBackend(page) {
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
}

async function main() {
  const missing = [];
  const descriptors = JSON.parse(fs.readFileSync(DESCRIPTORS, "utf8")).nodeDescriptors;
  const byKind = new Map(descriptors.map(d => [d.kind, d]));
  for (const entry of plan) {
    if (!byKind.has(entry.kind)) missing.push(entry.kind);
  }
  if (missing.length) {
    throw new Error(`node-capture-plan names kinds absent from the staged descriptors: ${missing.join(", ")}`
      + " — regenerate website/static/pipeline-editor/node-descriptors.json");
  }

  const vite = await ensureDevServer(ROOT, PORT);
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1400, height: 1000 },
    deviceScaleFactor: 2,
    // The node cards carry a blinking "active" dot. Without this every regeneration produces a
    // different picture of the same state, so every run looks like a change.
    reducedMotion: "reduce",
  });
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
      localStorage.removeItem("loom-ui-pipeline-debug");
    } catch (e) { /* ignore */ }
  });

  const page = await context.newPage();
  await mockBackend(page);

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await page.getByTestId("pipeline-canvas").waitFor({ timeout: 20_000 });
  await sleep(1200);

  const captured = [];
  for (const entry of plan) {
    try {
      captured.push(await capture(page, entry));
    } catch (e) {
      console.error(`  ✗ ${entry.page}: ${e.message}`);
    }
  }

  await browser.close();
  if (vite) vite.kill();

  console.log(`\ncaptured ${captured.length}/${plan.length} configuration panels into ${OUT}`);
  if (captured.length !== plan.length) process.exitCode = 1;
}

async function capture(page, entry) {
  // A single click opens the panel — there is no gear button, and double-click is unbound.
  //
  // Dispatched rather than clicked: React Flow floats its minimap over the bottom-right of the
  // canvas and its zoom controls over the bottom-left, and both swallow a real pointer event aimed
  // at a node underneath them. The debug capture hits the same thing on the breakpoint gutter.
  const node = page.getByTestId(`pipeline-node-${entry.page}`);
  await node.waitFor({ timeout: 8_000 });
  await node.dispatchEvent("click");
  const panel = page.getByTestId("pipeline-node-detail");
  await panel.waitFor({ timeout: 8_000 });
  await sleep(350);

  // Size the window to the panel's content, in both directions.
  //
  // Growing matters because `image-manipulation` has 23 parameters and `dominant-color` 22, and at
  // the default height those are a picture of the top third of a form with a scrollbar down the
  // side. Shrinking matters just as much: the panel is a flex child that stretches to the canvas,
  // so a node with three settings would be photographed with half a page of empty surface under it.
  //
  // Neither can be read off the panel or its body. Both stretch, so their `scrollHeight` equals
  // their `clientHeight` no matter what is inside — `tika` (3 parameters) and `image-manipulation`
  // (23) report an identical 958. The element that actually holds the form is the tab panel two
  // levels down: its *bottom edge* is where short content ends, and its `scrollHeight` overflow is
  // how much taller long content needs to be. Take both.
  //
  // Measured up to three times: changing the viewport can itself rewrap a long label.
  for (let pass = 0; pass < 3; pass++) {
    const needed = await page.evaluate(() => {
      const body = document.querySelector('[data-testid="pipeline-node-detail-body"]');
      const panel = document.querySelector('[data-testid="pipeline-node-detail"]');
      const inner = body?.firstElementChild;
      if (!panel || !inner || !inner.children.length) return null;
      const kids = [...inner.children];
      const bottom = Math.max(...kids.map(k => k.getBoundingClientRect().bottom));
      const overflow = Math.max(0, ...kids.map(k => k.scrollHeight - k.clientHeight));
      return Math.ceil(bottom - panel.getBoundingClientRect().top + overflow);
    });
    if (!needed) break;
    const target = Math.min(4000, Math.max(320, needed + 24));
    const current = page.viewportSize();
    if (Math.abs(current.height - target) < 8) break;
    await page.setViewportSize({ width: current.width, height: target });
    await sleep(250);
  }

  const box = await panel.boundingBox();
  if (!box || box.width < 40) {
    throw new Error("the detail panel is collapsed");
  }
  const dir = path.join(OUT, entry.page);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, "config.png");
  await page.screenshot({ path: file, clip: box });
  console.log(`  ✓ ${entry.page}/config.png (${Math.round(box.width)}×${Math.round(box.height)})`);

  // Back to a sane height before the next node, so the click target is where the layout expects it.
  await page.setViewportSize({ width: 1400, height: 1000 });
  await sleep(150);
  return entry.page;
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
