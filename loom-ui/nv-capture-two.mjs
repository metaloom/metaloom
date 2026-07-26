// Re-capture only the two screenshots affected by this change (library previews, ACL descriptions),
// using the same viewport/dark-mode setup as scripts/capture-ui-screenshots.mjs.
import { chromium } from "playwright";
import path from "path";

const BASE = process.env.UI_BASE_URL || "http://localhost:3000/";
const OUT = process.env.OUT_DIR ? path.resolve(process.env.OUT_DIR) : path.resolve("../website/content/english/docs/ui");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 2 });
await context.addInitScript(() => { try { localStorage.setItem("loom-ui-theme", "dark"); } catch (e) { /* ignore */ } });
const page = await context.newPage();
page.on("console", (m) => { if (m.type() === "error") console.log("console error:", m.text().slice(0, 160)); });

const clickNav = async (label) => {
  const item = page.locator(".MuiListItemButton-root").filter({ hasText: new RegExp(`^\\d*${label}\\d*$`) }).first();
  await item.click({ timeout: 8000 });
  await sleep(1200);
};

await page.goto(BASE, { waitUntil: "networkidle" });
await page.getByPlaceholder("Username").fill("admin");
await page.getByPlaceholder("Password").fill("finger");
await page.getByRole("button", { name: "Sign in" }).click();
await page.locator(".MuiListItemButton-root").filter({ hasText: /^Chat$/ }).first().waitFor({ timeout: 20000 });
await sleep(1500);

await clickNav("Library");
await sleep(1500);
const imgs = await page.locator('main img[src*="/binary/data"]').count();
console.log("library thumbnails rendered:", imgs);
if (imgs === 0) throw new Error("no thumbnails rendered — refusing to write library.png");
await page.screenshot({ path: path.join(OUT, "library.png") });

const users = page.locator('[data-testid="sidebar-item-/admin/users"]');
if (!(await users.isVisible().catch(() => false))) {
  await page.locator('[data-testid="sidebar-group-acl"]').click({ timeout: 8000 });
  await users.waitFor({ timeout: 4000 });
  await sleep(400);
}
await clickNav("Permissions");
await sleep(600);
const firstRole = page.locator("main .MuiListItemButton-root").first();
await firstRole.click({ timeout: 8000 });   // the matrix only renders for a selected role
await sleep(1200);
const descs = await page.locator("main :text('Create new assets')").count();
console.log("permission descriptions rendered:", descs);
if (descs === 0) throw new Error("no permission descriptions — refusing to write acl-roles.png");
await page.screenshot({ path: path.join(OUT, "acl-roles.png") });
console.log("done");
await browser.close();
