import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the chat / workspace split (LOOM_UI.md §3.6). No backend and no LLM:
 * the split is pure layout state, so the mocks only have to get past the login gate.
 *
 * The split used to be a pixel width capped at 700px, which left the divider with a few
 * hundred pixels of travel on any real monitor. These tests pin the replacement: an 80/20
 * default, a range that actually spans the viewport, and a workspace panel that can be
 * hidden and stays hidden.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function widthOf(page: Page, testId: string): Promise<number> {
  const box = await page.getByTestId(testId).boundingBox();
  if (!box) throw new Error(`${testId} has no bounding box`);
  return box.width;
}

/** Chat share of the split area, in percent — the rail outside the split is excluded. */
async function chatSharePct(page: Page): Promise<number> {
  const chat = await widthOf(page, "chat-column");
  const panel = await widthOf(page, "chat-workspace-panel");
  const divider = await widthOf(page, "chat-split-divider");
  return Math.round((chat / (chat + panel + divider)) * 100);
}

async function dragDividerTo(page: Page, x: number) {
  const divider = await page.getByTestId("chat-split-divider").boundingBox();
  if (!divider) throw new Error("divider has no bounding box");
  await page.mouse.move(divider.x + divider.width / 2, divider.y + divider.height / 2);
  await page.mouse.down();
  await page.mouse.move(x, divider.y + divider.height / 2, { steps: 10 });
  await page.mouse.up();
}

test.use({ viewport: { width: 1600, height: 900 } });

test.describe("Chat workspace split – mocked", () => {

  test("defaults to an 80/20 chat/workspace split", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await expect(page.getByTestId("chat-column")).toBeVisible();
    expect(await chatSharePct(page)).toBeGreaterThanOrEqual(78);
    expect(await chatSharePct(page)).toBeLessThanOrEqual(82);
  });

  test("the divider travels across most of the viewport", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await dragDividerTo(page, 380);
    const narrow = await widthOf(page, "chat-column");

    await dragDividerTo(page, 1560);
    const wide = await widthOf(page, "chat-column");

    // The old pixel split clamped the chat column to 280..700; the range has to be
    // substantially wider than that or the divider still feels stuck.
    expect(wide - narrow).toBeGreaterThan(600);
    expect(await widthOf(page, "chat-workspace-panel")).toBeGreaterThan(0);
  });

  test("double-clicking the divider restores the 80/20 default", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await dragDividerTo(page, 400);
    expect(await chatSharePct(page)).toBeLessThan(50);

    await page.getByTestId("chat-split-divider").dblclick();
    expect(await chatSharePct(page)).toBeGreaterThanOrEqual(78);
  });

  test("the workspace panel can be hidden and stays hidden across a reload", async ({ page }) => {
    await installMocks(page);
    await login(page);

    const full = await widthOf(page, "chat-column");
    await page.getByTestId("chat-panel-collapse").click();
    await expect(page.getByTestId("chat-workspace-panel")).toHaveCount(0);
    await expect(page.getByTestId("chat-split-divider")).toHaveCount(0);
    expect(await widthOf(page, "chat-column")).toBeGreaterThan(full);

    // The preference is persisted, so a reload must not bring the panel back.
    await page.reload();
    await login(page);
    await expect(page.getByTestId("chat-workspace-panel")).toHaveCount(0);

    await page.getByTestId("chat-panel-toggle").click();
    await expect(page.getByTestId("chat-workspace-panel")).toBeVisible();
  });
});
