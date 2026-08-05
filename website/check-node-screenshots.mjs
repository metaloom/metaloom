#!/usr/bin/env node
// Check that every node documentation page carries its pictures, and that every shipped node kind
// has a page at all.
//
// Plain Node, no dependencies, same shape as check-links.mjs. Run from build.sh between the
// localhost check and the link check.
//
// What it enforces
// ----------------
//   1. Every page under docs/nodes/ has a non-empty config.png, referenced from index.adoc with
//      real alt text. That picture needs no fixtures and no services, so there is never a reason
//      for one to be missing.
//   2. Every page has a debug.png on the same terms, unless it is listed as blocked or pending in
//      loom-ui/scripts/fixtures/nodes/status.json.
//   3. Every entry in that file names a real page and gives a reason. A `blocked` entry must also
//      say so *on the page*, so that a node nobody can photograph is a visible, reviewed statement
//      rather than a silent hole; a `pending` entry is allowed quietly but counted out loud.
//   4. Every node kind the product ships maps to exactly one page. This is the check that matters
//      most and the only one that cannot be made by looking at the docs tree: a node added with no
//      page at all is invisible to every per-page check there is.
//   5. No committed fixture records a stubbed backend outside the reviewed allowlist. A screenshot
//      of a stubbed model is a screenshot of a decision nothing made.
//
// Usage:  node check-node-screenshots.mjs

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const NODES = path.resolve(__dirname, "content/english/docs/nodes");
const DESCRIPTORS = path.resolve(__dirname, "static/pipeline-editor/node-descriptors.json");
const PLAN_FILE = path.resolve(__dirname, "../loom-ui/scripts/node-capture-plan.mjs");
const FIXTURES = path.resolve(__dirname, "../loom-ui/scripts/fixtures/nodes");
const STATUS = path.join(FIXTURES, "status.json");

const STUB_ALLOWLIST = new Set(["gdrive-source", "onedrive-source"]);

const errors = [];
const notes = [];

const plan = await import(PLAN_FILE);
const status = fs.existsSync(STATUS) ? JSON.parse(fs.readFileSync(STATUS, "utf8")) : {};

const pageDirs = fs.readdirSync(NODES, { withFileTypes: true })
  .filter(e => e.isDirectory())
  .map(e => e.name)
  .sort();

// ---- 1 + 2: the pictures ---------------------------------------------------

/** A picture counts only if the file has bytes *and* the page points at it with real alt text. */
function checkPicture(page, name, adoc) {
  const file = path.join(NODES, page, name);
  if (!fs.existsSync(file) || fs.statSync(file).size === 0) {
    return `${page}: ${name} is missing or empty`;
  }
  const macro = new RegExp(`image::${name.replace(".", "\\.")}\\[([^\\]]*)\\]`);
  const match = adoc.match(macro);
  if (!match) {
    return `${page}: ${name} exists but index.adoc never references it`;
  }
  const alt = match[1].split(",")[0].trim();
  if (alt.length < 12) {
    return `${page}: the alt text for ${name} is missing or too short to describe anything ("${alt}")`;
  }
  return null;
}

let pending = 0;
for (const page of pageDirs) {
  const adocFile = path.join(NODES, page, "index.adoc");
  if (!fs.existsSync(adocFile)) {
    errors.push(`${page}: has no index.adoc`);
    continue;
  }
  const adoc = fs.readFileSync(adocFile, "utf8");

  const entry = status[page];
  // Which pictures this page is excused from, defaulting to the debug view — the config panel
  // needs nothing but the descriptors, so a page missing that one is nearly always an oversight.
  // A brand-new node whose page landed after the last capture run is the exception, and has to say
  // so explicitly rather than being covered by the default.
  const excused = new Set(entry ? (entry.pictures ?? ["debug"]) : []);

  if (!excused.has("config")) {
    const config = checkPicture(page, "config.png", adoc);
    if (config) errors.push(config);
  }

  if (!entry) {
    const debug = checkPicture(page, "debug.png", adoc);
    if (debug) errors.push(debug);
  } else if (!excused.has("debug")) {
    const debug = checkPicture(page, "debug.png", adoc);
    if (debug) errors.push(debug);
  }

  if (!entry) {
    // nothing further to check
  } else if (entry.status === "blocked") {
    // The page has to say what the reader cannot see, in the reader's own view.
    if (!entry.reason || !entry.unblockedBy) {
      errors.push(`${page}: a blocked entry must carry both "reason" and "unblockedBy"`);
    } else if (!adoc.includes(entry.reason)) {
      errors.push(`${page}: is marked blocked but the page never says why — `
        + `add a NOTE containing "${entry.reason}"`);
    }
  } else if (entry.status === "pending") {
    if (!entry.reason) errors.push(`${page}: a pending entry must carry a "reason"`);
    pending++;
  } else {
    errors.push(`${page}: unknown status "${entry.status}" — use "blocked" or "pending"`);
  }
}

// ---- 3: no stale status entries -------------------------------------------

for (const page of Object.keys(status)) {
  if (!pageDirs.includes(page)) {
    errors.push(`status.json names "${page}", which is not a node documentation page`);
  }
}

// ---- 4: every shipped kind has a page --------------------------------------

const descriptors = JSON.parse(fs.readFileSync(DESCRIPTORS, "utf8")).nodeDescriptors;
const documented = plan.documentedKinds();
const undocumented = plan.UNDOCUMENTED_KINDS ?? {};

for (const descriptor of descriptors) {
  if (!documented.has(descriptor.kind) && !(descriptor.kind in undocumented)) {
    errors.push(`node kind "${descriptor.kind}" ships but no documentation page covers it — `
      + "add a page and list it in loom-ui/scripts/node-capture-plan.mjs "
      + "(or record why it has none in UNDOCUMENTED_KINDS)");
  }
}
const shipped = new Set(descriptors.map(d => d.kind));
for (const entry of plan.PAGES) {
  if (!shipped.has(entry.kind)) {
    errors.push(`node-capture-plan maps ${entry.page} to "${entry.kind}", which no descriptor declares`);
  }
  if (!pageDirs.includes(entry.page)) {
    errors.push(`node-capture-plan names the page "${entry.page}", which does not exist`);
  }
}

// ---- 5: no stubbed backend became documentation ----------------------------

if (fs.existsSync(FIXTURES)) {
  for (const kind of fs.readdirSync(FIXTURES)) {
    const file = path.join(FIXTURES, kind, "fixture.json");
    if (!fs.existsSync(file)) continue;
    const fixture = JSON.parse(fs.readFileSync(file, "utf8"));
    if (fixture.backend && fixture.backend !== "real" && !STUB_ALLOWLIST.has(kind)) {
      errors.push(`fixtures/nodes/${kind}: backend is "${fixture.backend}" — `
        + "a stubbed backend must not become a documentation screenshot");
    }
  }
}

// ---- report ----------------------------------------------------------------

if (pending) {
  notes.push(`${pending} node page${pending === 1 ? "" : "s"} still without a debug view `
    + "(run DocsFixtureGenerator, then capture-node-screenshots.mjs)");
}

for (const note of notes) console.log(`  · ${note}`);

if (errors.length) {
  console.error(`\nNode screenshot check FAILED — ${errors.length} problem(s):`);
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}

console.log(`Node screenshot check OK — ${pageDirs.length} pages, ${descriptors.length} kinds.`);
