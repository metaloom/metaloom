// Capture Loom UI screenshots for the website documentation (docs/ui/).
//
// Drives a headless Chromium (via the Playwright already installed under loom-ui)
// against a running demo container and writes dark-mode PNGs into the website
// page bundle at website/content/english/docs/ui/.
//
// Prerequisites (see spec/website/WEBSITE.md → "Capturing Loom UI screenshots"):
//   1. Build a fresh demo image, then start the stack:
//        ./start-postgres.sh && ./start-demo.sh
//   2. The UI must answer at http://localhost:8092/ui/ (admin / finger).
//
// Usage (from loom-ui/):
//   node scripts/capture-ui-screenshots.mjs
//
// Env overrides:
//   UI_BASE_URL  (default http://localhost:8092/ui/)
//   LOOM_USER    (default admin)
//   LOOM_PASS    (default finger)
//   OUT_DIR      (default ../website/content/english/docs/ui)

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
  : path.resolve(__dirname, "../../website/content/english/docs/ui");

fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1000 },
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
  const results = [];

  const shot = async (name, { settle = 900 } = {}) => {
    await sleep(settle);
    const file = path.join(OUT, name);
    await page.screenshot({ path: file });
    results.push(`  ✓ ${name}`);
    console.log(`captured ${name}`);
  };

  // Click a sidebar nav entry by its exact label (avoids deep-link reloads,
  // which do not work because the SPA has no basename under /ui/).
  const clickNav = async (label) => {
    const item = page
      .locator(".MuiListItemButton-root")
      .filter({ hasText: new RegExp(`^${label}$`) })
      .first();
    await item.click({ timeout: 8000 });
    await sleep(1200);
  };

  const capture = async (name, fn) => {
    try {
      await fn();
    } catch (e) {
      results.push(`  ✗ ${name} — ${e.message.split("\n")[0]}`);
      console.warn(`FAILED ${name}: ${e.message.split("\n")[0]}`);
    }
  };

  // ---- Login ----
  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.getByPlaceholder("Username").fill(USER);
  await page.getByPlaceholder("Password").fill(PASS);
  await page.getByRole("button", { name: "Sign in" }).click();
  // Wait for the app shell (sidebar) to render.
  await page
    .locator(".MuiListItemButton-root")
    .filter({ hasText: /^Chat$/ })
    .first()
    .waitFor({ timeout: 20000 });
  await sleep(1500);

  // ---- Chat (landing) ----
  await capture("chat.png", async () => {
    await shot("chat.png", { settle: 1500 });
  });

  // ---- Assets ----
  await capture("assets.png", async () => {
    await clickNav("Assets");
    await shot("assets.png");
  });

  // ---- Asset detail (click the first asset card) ----
  await capture("asset-detail.png", async () => {
    const card = page.locator("main .MuiPaper-root").first();
    await card.click({ timeout: 8000 });
    await page.waitForURL(/\/assets\/.+/, { timeout: 8000 }).catch(() => {});
    await shot("asset-detail.png", { settle: 1600 });
  });

  // ---- Library ----
  await capture("library.png", async () => {
    await clickNav("Library");
    await shot("library.png");
  });

  // ---- Tags ----
  await capture("tags.png", async () => {
    await clickNav("Tags");
    await shot("tags.png");
  });

  // ---- Face detection (Detection view defaults to the Faces tab) ----
  await capture("face-detection.png", async () => {
    await clickNav("Detection");
    await shot("face-detection.png", { settle: 1400 });
  });

  // ---- Skills ----
  await capture("skills.png", async () => {
    await clickNav("Skills");
    await shot("skills.png");
  });

  // ---- Agent memory ----
  await capture("memory.png", async () => {
    await clickNav("Memory");
    await shot("memory.png");
  });

  // ---- Pipeline editor ----
  await capture("pipeline-editor.png", async () => {
    await clickNav("Pipelines");
    await shot("pipeline-editor.png", { settle: 1800 });
  });

  // ---- Pipeline versioning (open the version-history popover) ----
  await capture("pipeline-versions.png", async () => {
    const badge = page.locator('[data-testid="pipeline-version-badge"]').first();
    await badge.click({ timeout: 6000 });
    await page
      .locator('[data-testid="pipeline-version-list"]')
      .first()
      .waitFor({ timeout: 4000 })
      .catch(() => {});
    await shot("pipeline-versions.png", { settle: 900 });
    await page.keyboard.press("Escape").catch(() => {});
  });

  // ---- Cortex instances ----
  await capture("cortex.png", async () => {
    await clickNav("Cortex");
    await shot("cortex.png");
  });

  // ---- User management ----
  await capture("users.png", async () => {
    await clickNav("Users");
    await shot("users.png");
  });

  // ---- ACL / roles & permissions ----
  await capture("acl-roles.png", async () => {
    await clickNav("Permissions");
    await shot("acl-roles.png", { settle: 1200 });
  });

  // ---- API keys (try to open the create dialog) ----
  await capture("api-keys.png", async () => {
    await clickNav("API Keys");
    await sleep(600);
    const createBtn = page
      .locator("main button")
      .filter({ hasText: /Create|New|Generate|Add|Token|Key/i })
      .first();
    if (await createBtn.count()) {
      await createBtn.click({ timeout: 4000 }).catch(() => {});
      await page
        .locator('.MuiDialog-root, [role="dialog"]')
        .first()
        .waitFor({ timeout: 3000 })
        .catch(() => {});
    }
    await shot("api-keys.png", { settle: 900 });
  });

  console.log("\n--- Screenshot summary ---\n" + results.join("\n"));
  console.log(`\nOutput dir: ${OUT}`);

  await browser.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
