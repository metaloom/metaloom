// Capture the Database Integrity admin screen for the website documentation.
//
// Writes into the page bundle of the page that shows them
// (website/content/english/docs/loom/database-integrity/), because asciidoc resolves a bare
// image:: filename inside the bundle of the page using it.
//
// Split out from capture-ui-screenshots.mjs on purpose: the interesting shot is of a database with
// something wrong with it, which means the caller has to break the demo database between the two
// runs. That is a shell concern, so this script takes one shot per invocation and the sequencing
// lives in the docs (spec/website/WEBSITE.md -> "Capturing the database integrity screen").
//
// Prerequisites:
//   1. A demo stack built from the current tree:
//        ./start-postgres.sh && ./start-demo.sh
//   2. The UI answers at http://localhost:8092/ui/ (admin / finger).
//
// Usage (from loom-ui/):
//   node scripts/capture-db-integrity-screenshots.mjs db-integrity-clean.png
//   node scripts/capture-db-integrity-screenshots.mjs db-integrity-findings.png --expand DANGLING_TOKEN_EDITOR
//   node scripts/capture-db-integrity-screenshots.mjs db-integrity-filtered.png --findings-only
//
// Env overrides:
//   UI_BASE_URL  (default http://localhost:8092/ui/)
//   LOOM_USER    (default admin)
//   LOOM_PASS    (default finger)
//   OUT_DIR      (default ../website/content/english/docs/loom/database-integrity)

import { chromium } from "playwright";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";

const BASE = process.env.UI_BASE_URL || "http://localhost:8092/ui/";
const USER = process.env.LOOM_USER || "admin";
const PASS = process.env.LOOM_PASS || "finger";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = process.env.OUT_DIR
  ? path.resolve(process.env.OUT_DIR)
  : path.resolve(__dirname, "../../website/content/english/docs/loom/database-integrity");

const args = process.argv.slice(2);
const name = args.find(a => !a.startsWith("--")) || "db-integrity-clean.png";
const expandAt = args.indexOf("--expand");
const expand = expandAt === -1 ? null : args[expandAt + 1];
const findingsOnly = args.includes("--findings-only");

fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1100 },
    deviceScaleFactor: 2,
  });
  // Force dark mode regardless of any persisted preference.
  await context.addInitScript(() => {
    try {
      localStorage.setItem("loom-ui-theme", "dark");
    } catch (e) {
      /* ignore */
    }
  });

  const page = await context.newPage();

  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill(USER);
  await page.getByPlaceholder("Password").fill(PASS);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page
    .locator(".MuiListItemButton-root")
    .filter({ hasText: /^Chat$/ })
    .first()
    .waitFor({ timeout: 20000 });
  await sleep(1200);

  // Reached through Spaces: every /admin/* screen behind the tab bar needs the area open first,
  // and a deep link cannot be used because the SPA has no basename here.
  await page
    .locator(".MuiListItemButton-root")
    .filter({ hasText: /^\d*Spaces\d*$/ })
    .first()
    .click({ timeout: 8000 });
  await sleep(1000);

  await page.getByRole("tab", { name: "Database integrity", exact: true }).click({ timeout: 8000 });
  await page.locator('[data-testid="db-integrity-admin"]').waitFor({ timeout: 10000 });
  // The sweep runs on the server; wait for the summary rather than a fixed delay.
  await page.locator('[data-testid="db-integrity-summary"]').waitFor({ timeout: 20000 });

  if (findingsOnly) {
    await page.locator('[data-testid="db-integrity-filter-findings"]').click({ timeout: 8000 });
    await sleep(400);
  }

  if (expand) {
    await page.locator(`[data-testid="db-integrity-toggle-${expand}"]`).click({ timeout: 8000 });
    await page.locator(`[data-testid="db-integrity-samples-${expand}"]`).waitFor({ timeout: 5000 });
  }

  await sleep(700);
  const file = path.join(OUT, name);
  await page.screenshot({ path: file });
  console.log(`captured ${file}`);

  await browser.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
