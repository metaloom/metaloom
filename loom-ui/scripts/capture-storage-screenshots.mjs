// Capture the Storage admin screen for the website documentation.
//
// Writes into the page bundle of the page that shows them
// (website/content/english/docs/loom/storage/), because asciidoc resolves a bare
// image:: filename inside the bundle of the page using it.
//
// One shot per invocation, like capture-db-integrity-screenshots.mjs: the second interesting shot
// is of an install that has something to say - a nearly full volume, or an S3 pool - and arranging
// that is a shell concern rather than something this script should fake.
//
// Prerequisites:
//   1. A demo stack built from the current tree:
//        ./start-postgres.sh && ./start-demo.sh
//   2. The UI answers at http://localhost:8092/ui/ (admin / finger).
//
// Usage (from loom-ui/):
//   node scripts/capture-storage-screenshots.mjs storage-overview.png
//   node scripts/capture-storage-screenshots.mjs storage-backends.png --focus backends
//   node scripts/capture-storage-screenshots.mjs storage-categories.png --focus categories
//
// Env overrides:
//   UI_BASE_URL  (default http://localhost:8092/ui/)
//   LOOM_USER    (default admin)
//   LOOM_PASS    (default finger)
//   OUT_DIR      (default ../website/content/english/docs/loom/storage)

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
  : path.resolve(__dirname, "../../website/content/english/docs/loom/storage");

const args = process.argv.slice(2);
const name = args.find(a => !a.startsWith("--")) || "storage-overview.png";
const focusAt = args.indexOf("--focus");
const focus = focusAt === -1 ? null : args[focusAt + 1];

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

  await page.getByRole("tab", { name: "Storage", exact: true }).click({ timeout: 8000 });
  await page.locator('[data-testid="storage-admin"]').waitFor({ timeout: 10000 });
  // The report is several aggregate scans on the server; wait for the summary rather than a fixed delay.
  await page.locator('[data-testid="storage-summary"]').waitFor({ timeout: 30000 });

  await sleep(700);
  const file = path.join(OUT, name);

  if (focus) {
    // A section shot rather than the whole page: the backend cards and the category table are what
    // the docs explain, and a full-page capture shrinks both to unreadable.
    const testId = focus === "backends" ? "storage-backends" : "storage-categories";
    await page.locator(`[data-testid="${testId}"]`).screenshot({ path: file });
  } else {
    await page.screenshot({ path: file });
  }
  console.log(`captured ${file}`);

  await browser.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
