#!/usr/bin/env node
// Check that the MetaLoom // Graph browser page carries its pictures.
//
// Plain Node, no dependencies, same shape as check-node-screenshots.mjs. Run from build.sh, against
// the SOURCE tree rather than dist/ — a missing image is a fact about the content, and finding it
// after Hugo has already rendered a page with a broken <img> is finding it one step too late.
//
// What it enforces
// ----------------
//   1. Every PNG in the bundle is non-empty. A zero-byte file is what a failed capture leaves
//      behind, and it renders as a broken image rather than as an error.
//   2. Every PNG is referenced from index.adoc. An orphan is either a rename nobody finished or a
//      picture somebody meant to use, and both are worth knowing about.
//   3. Every reference has REAL alt text — at least 12 characters, and not the filename. This is
//      the rule that actually gets broken: `image::inspector.png[inspector]` passes every other
//      check there is and tells a screen reader nothing.
//   4. The expected set is present. A screenshot silently dropped from the capture spec would
//      otherwise disappear from the page without anything noticing.
//
// Usage:  node check-graph-screenshots.mjs

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BUNDLE = path.resolve(__dirname, "content/english/graph/browser");
const PAGE = path.join(BUNDLE, "index.adoc");

/**
 * The shots e2e/screenshots.spec.ts captures, in the graph repository.
 *
 * Listed here rather than derived from the directory, because deriving it from the directory would
 * make "the file is missing" and "the file was never expected" the same state — which is the one
 * failure this list exists to tell apart.
 */
const EXPECTED = [
  "browser.png",
  "query-editor.png",
  "graph-view.png",
  "inspector.png",
  "schema-sidebar.png",
  "index-panel.png",
  "result-table.png",
];

const MIN_ALT = 12;

const errors = [];

if (!fs.existsSync(PAGE)) {
  fail([`${path.relative(__dirname, PAGE)} does not exist.`]);
}

const adoc = fs.readFileSync(PAGE, "utf8");

// image::name.png[alt text,role=img-fluid]
const referenced = new Map();
for (const match of adoc.matchAll(/image::([^\[\]]+)\[([^\]]*)\]/g)) {
  referenced.set(match[1].trim(), match[2]);
}

const present = fs.existsSync(BUNDLE)
  ? fs.readdirSync(BUNDLE).filter((f) => f.endsWith(".png")).sort()
  : [];

// ---- 1: the files are real -------------------------------------------------

for (const png of present) {
  const size = fs.statSync(path.join(BUNDLE, png)).size;
  if (size === 0) {
    errors.push(`${png} is zero bytes — a failed capture, not a picture.`);
  }
}

// ---- 4: everything expected is here ----------------------------------------

for (const png of EXPECTED) {
  if (!present.includes(png)) {
    errors.push(
      `${png} is missing. Capture it in the graph repository and import it:\n` +
        `      cd graph-server/src/main/frontend && npm run screenshots\n` +
        `      ./import-graph-screenshots.sh`
    );
  }
}

// ---- 2 + 3: referenced, with alt text worth having -------------------------

for (const png of present) {
  if (!referenced.has(png)) {
    errors.push(`${png} is in the bundle but nothing on the page references it.`);
    continue;
  }
  // The first positional attribute is the alt text; anything after a comma is a role or an option.
  const alt = referenced.get(png).split(",")[0].trim();
  if (alt.length < MIN_ALT) {
    errors.push(
      `${png} has alt text of ${alt.length} characters ("${alt}"). ` +
        `Describe what is in the picture, not what the file is called.`
    );
  } else if (alt.toLowerCase().replace(/[^a-z]/g, "") === png.replace(".png", "").replace(/[^a-z]/g, "")) {
    errors.push(`${png} uses its own filename as alt text, which tells a screen reader nothing.`);
  }
  if (!referenced.get(png).includes("role=img-fluid")) {
    errors.push(`${png} is referenced without role=img-fluid, so it will not scale on a phone.`);
  }
}

for (const png of referenced.keys()) {
  if (!present.includes(png)) {
    errors.push(`The page references ${png}, which is not in the bundle.`);
  }
}

if (errors.length) {
  fail(errors);
}

console.log(`check-graph-screenshots: ${present.length} screenshot(s) present and referenced with alt text.`);

function fail(lines) {
  console.error("\ncheck-graph-screenshots FAILED:\n");
  for (const line of lines) console.error(`  - ${line}`);
  console.error("");
  process.exit(1);
}
