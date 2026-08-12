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

// Most shots belong to the UI page bundle. The search index ones belong to the page that documents
// them, because asciidoc resolves a bare image:: filename inside the bundle of the page using it.
const SEARCH_INDEX_OUT = path.resolve(OUT, "../loom/search-indices");

fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(SEARCH_INDEX_OUT, { recursive: true });

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

  const shot = async (name, { settle = 900, dir = OUT } = {}) => {
    await sleep(settle);
    const file = path.join(dir, name);
    await page.screenshot({ path: file });
    results.push(`  ✓ ${name}`);
    console.log(`captured ${name}`);
  };

  // Click a sidebar nav entry by its exact label (avoids deep-link reloads,
  // which do not work because the SPA has no basename under /ui/).
  // The digits allow for a badge counter rendered inside the entry (e.g. "Tasks" + "3").
  const clickNav = async (label) => {
    const item = page
      .locator(".MuiListItemButton-root")
      .filter({ hasText: new RegExp(`^\\d*${label}\\d*$`) })
      .first();
    await item.click({ timeout: 8000 });
    await sleep(1200);
  };

  // The ACL entries (Users, Groups, Permissions, API Keys, Blacklist) live in a collapsible
  // sub-group that starts closed, so they must be revealed before they can be clicked.
  const openAclGroup = async () => {
    const users = page.locator('[data-testid="sidebar-item-/admin/users"]');
    if (await users.isVisible().catch(() => false)) return;
    await page.locator('[data-testid="sidebar-group-acl"]').click({ timeout: 8000 });
    await users.waitFor({ timeout: 4000 });
    await sleep(400);
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

  // ---- Asset detail ----
  // Target a named demo asset rather than "the first card": the list order is not stable, and
  // this one is the richest — a stored image binary plus tags, an annotation, a reaction,
  // detections and a task.
  await capture("asset-detail.png", async () => {
    const card = page
      .locator("main .MuiPaper-root")
      .filter({ hasText: "sunset-beach.jpg" })
      .first();
    await card.click({ timeout: 8000 });
    await page.waitForURL(/\/assets\/.+/, { timeout: 8000 }).catch(() => {});
    await shot("asset-detail.png", { settle: 1600 });
  });

  // ---- Library ----
  // The view auto-selects the first library, which in the demo holds nothing — pick one with
  // assets so the shot shows the grid and its thumbnails rather than the empty state.
  await capture("library.png", async () => {
    await clickNav("Library");
    await page
      .locator(".MuiListItemButton-root")
      .filter({ hasText: /assets/ })
      .filter({ hasNotText: "0 assets" })
      .first()
      .click({ timeout: 6000 })
      .catch(() => {});
    await shot("library.png", { settle: 1400 });
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

  // ---- A person's own page: their pictures and their avatar ----
  // Reached by clicking through from the Persons panel rather than by deep link, for the same reason
  // clickNav exists. The demo seeds every person with pictures, so this has something to show.
  await capture("persons.png", async () => {
    await clickNav("Detection");
    await page.locator('[data-testid="facedetection-section-persons"]').click({ timeout: 8000 });
    await sleep(600);
    await page.locator('[data-testid="person-name"]').first().click({ timeout: 8000 });
    await page.locator('[data-testid="person-detail"]').waitFor({ timeout: 8000 });
    await shot("persons.png", { settle: 1400 });
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

  // ---- Monitoring dashboard ----
  await capture("monitoring.png", async () => {
    await clickNav("Monitoring");
    // Recharts animates its series in; wait it out so the charts are not caught mid-draw.
    await shot("monitoring.png", { settle: 2200 });
  });

  // ---- Chat sessions (the published sessions and their context) ----
  await capture("chat-sessions.png", async () => {
    await clickNav("Chat Sessions");
    await shot("chat-sessions.png", { settle: 1200 });
  });

  // ---- Tasks board ----
  await capture("tasks.png", async () => {
    await clickNav("Tasks");
    await shot("tasks.png", { settle: 1200 });
  });

  // ---- Workflow: the three keyboard-driven review modes ----
  //
  // The mode switch is a ToggleButtonGroup; only the modes an e2e spec drives carry a testid, so
  // Rating and Tagging are picked by their exact label — "Tagging" is a substring of nothing here,
  // but exact matching keeps that true if a label is ever added.
  const workflowMode = async (label) => {
    await clickNav("Workflow");
    await page.getByRole("button", { name: label, exact: true }).click({ timeout: 8000 });
    await sleep(900);
  };

  await capture("workflow-rating.png", async () => {
    await workflowMode("Rating");
    await page.locator('[data-testid="workflow-rating-value"]').waitFor({ timeout: 8000 });
    await shot("workflow-rating.png", { settle: 1400 });
  });

  await capture("workflow-tagging.png", async () => {
    await workflowMode("Tagging");
    await page.locator('[data-testid="workflow-tag-input"]').waitFor({ timeout: 8000 });
    await shot("workflow-tagging.png", { settle: 1400 });
  });

  // The dedup queue only has something to show once a fingerprint-dedup run has proposed groups; if
  // the demo data carries none, this shot is skipped rather than capturing the empty state.
  await capture("workflow-dedup.png", async () => {
    await clickNav("Workflow");
    await page.locator('[data-testid="workflow-mode-deduplication"]').click({ timeout: 8000 });
    await page.locator('[data-testid="dedup-group"]').waitFor({ timeout: 8000 });
    await shot("workflow-dedup.png", { settle: 1400 });
  });

  // ---- User management ----
  await capture("users.png", async () => {
    await openAclGroup();
    await clickNav("Users");
    await shot("users.png");
  });

  // ---- ACL / roles & permissions ----
  await capture("acl-roles.png", async () => {
    await openAclGroup();
    await clickNav("Permissions");
    await shot("acl-roles.png", { settle: 1200 });
  });

  // ---- Search indices (admin tab, not a sidebar entry) ----
  // Reached through Spaces — every /admin/* screen except Spaces and Memory Denylist lives behind
  // the AdminArea tab bar, and a deep link cannot be used because the SPA has no basename here.
  //
  // These two land in the Search Indices page bundle rather than docs/ui/, because that is the page
  // that shows them and every image:: in the docs is a bare filename resolved inside its own bundle.
  await capture("search-indices.png", async () => {
    await clickNav("Spaces");
    await page.getByRole("tab", { name: "Indices", exact: true }).click({ timeout: 8000 });
    await page.locator('[data-testid="search-indices-admin"]').waitFor({ timeout: 8000 });
    await shot("search-indices.png", { settle: 1400, dir: SEARCH_INDEX_OUT });
  });

  // ---- Search index reindex in flight ----
  // The lexical index is the one guaranteed to be populated in the demo, and its rebuild is a
  // single SQL call — so it reports no total and the bar renders indeterminate, which is exactly
  // the state the docs need to explain. Shoot immediately: on a demo-sized corpus it finishes fast.
  await capture("search-indices-job.png", async () => {
    const reindex = page.locator('[data-testid="search-index-action-lexical-reindex"]');
    await reindex.click({ timeout: 6000 });
    await page
      .locator('[data-testid="search-index-job-progress-lexical"]')
      .waitFor({ timeout: 6000 })
      .catch(() => {});
    await shot("search-indices-job.png", { settle: 250, dir: SEARCH_INDEX_OUT });
  });

  // ---- API keys (try to open the create dialog) ----
  await capture("api-keys.png", async () => {
    await openAclGroup();
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
