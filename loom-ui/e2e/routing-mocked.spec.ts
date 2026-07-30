import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the /ui base path (LOOM_UI.md §3.7). The app is mounted under /ui/ in
 * both dev and the served build, and React Router is given a matching basename.
 *
 * Before that, three things went wrong: visiting /ui bounced to / after login (the router
 * saw /ui as an unknown route and its catch-all redirected), a route pushed by the router
 * did not survive a reload, and bare / was a dead end. The dev server reproduces the same
 * base-path and history-fallback behaviour the Vert.x UIService provides in production
 * (see UIServiceRoutingTest), so these run without a backend.
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
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Routing under the /ui base path – mocked", () => {

  test("bare / lands on the app under /ui/", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await expect(page).toHaveURL(/\/ui\/$/);
  });

  test("logging in at /ui/ stays at /ui/", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await login(page);
    await expect(page).toHaveURL(/\/ui\/$/);
  });

  test("a route pushed by the router survives a reload", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await login(page);

    await page.getByTestId("sidebar-item-/memory").click();
    await expect(page).toHaveURL(/\/ui\/memory$/);

    await page.reload();
    await expect(page).toHaveURL(/\/ui\/memory$/);
    // The token is in-memory only (§4.4), so a reload lands back on the login form —
    // but on the *same* URL, and signing in must resolve to the route, not to the root.
    await login(page);
    await expect(page).toHaveURL(/\/ui\/memory$/);
  });

  test("a nested deep link resolves", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/chat/sessions");
    await login(page);
    await expect(page).toHaveURL(/\/ui\/chat\/sessions$/);
  });
});
