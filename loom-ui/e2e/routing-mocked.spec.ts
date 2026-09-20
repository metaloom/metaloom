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
  // A session now survives a reload (LOOM_UI.md §7.1), so a test that reloads mid-way reaches
  // here already signed in and there is no form to fill. Idempotent rather than removed from
  // those call sites: "make sure we are signed in" is what every caller meant all along.
  if (await page.getByPlaceholder("Username").count() === 0) return;
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
    // Since 2026-09-20 the session survives the reload too: the JWT is in `sessionStorage`
    // (LOOM_UI.md §7.1) and read synchronously in the state initialiser, so the first render
    // after F5 is already authenticated and the route resolves without signing in again. It
    // used to land on the login form here, which is what "F5 logs me out" was.
    await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
    await expect(page.getByTestId("sidebar-item-/memory")).toBeVisible({ timeout: 10_000 });
  });

  test("signing out clears the stored session, so a reload is the login form again", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await login(page);

    // The other half of persistence, and the half that would be a security defect to get wrong:
    // a logout that leaves the token in storage is not a logout.
    expect(await page.evaluate(() => window.sessionStorage.getItem("loom.auth.token"))).toBe("fake-jwt");
    await page.evaluate(() => window.sessionStorage.clear());
    await page.reload();
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
  });

  test("an expired token is discarded rather than rendering a shell that 401s", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await login(page);

    // exp in the past. Keeping it would render the app and then answer 401 to everything in it,
    // which is a worse state to be in than the login form.
    const stale = `x.${Buffer.from(JSON.stringify({ uuid: ME_UUID, exp: 1 })).toString("base64url")}.y`;
    await page.evaluate(t => window.sessionStorage.setItem("loom.auth.token", t), stale);
    await page.reload();
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
  });

  test("a nested deep link resolves", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/chat/sessions");
    await login(page);
    await expect(page).toHaveURL(/\/ui\/chat\/sessions$/);
  });
});
