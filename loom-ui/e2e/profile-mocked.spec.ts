import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the profile screen — no running Loom backend.
 *
 * `/profile` is reachable only from the sidebar avatar menu, so every case here enters
 * through that menu: a regression in it orphans the screen entirely. The user record is a
 * small in-memory store, and each save is recorded so the request body can be asserted —
 * the screen writes to the user record, and a partial write is the contract.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

interface StoredUser {
  uuid: string;
  username: string;
  firstname?: string;
  lastname?: string;
  email?: string;
  enabled: boolean;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function seeded(): StoredUser {
  return {
    uuid: ME_UUID,
    username: "admin",
    firstname: "Ada",
    lastname: "Lovelace",
    email: "ada@example.com",
    enabled: true,
  };
}

/**
 * @param saveStatus status to answer the profile save with; anything other than 200 leaves
 *   the stored user untouched, which is what the failure case needs to assert against.
 */
async function installMocks(page: Page, saveStatus = 200) {
  const user = seeded();
  const saveBodies: Record<string, unknown>[] = [];

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, user));

  // Registered after the generic matcher — Playwright routes are LIFO, so the specific
  // user path has to come later to win.
  await page.route(/\/api\/v1\/users\/[^/]+$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      saveBodies.push(body);
      if (saveStatus !== 200) return json(route, { message: "Permission denied" }, saveStatus);
      Object.assign(user, body);
      return json(route, user);
    }
    return json(route, user);
  });

  return { saveBodies, user };
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/** The avatar menu is the only route into `/profile`. */
async function openProfileFromAvatarMenu(page: Page) {
  await page.getByTestId("sidebar-avatar-button").click();
  await expect(page.getByTestId("sidebar-avatar-menu")).toBeVisible();
  await page.getByTestId("sidebar-avatar-profile").click();
  await expect(page.getByTestId("profile-view")).toBeVisible({ timeout: 10_000 });
}

test.describe("Profile – mocked e2e", () => {
  test("the avatar menu navigates to the profile route", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await openProfileFromAvatarMenu(page);
    await expect(page).toHaveURL(/\/profile$/);
  });

  test("fields populate from the loaded user", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openProfileFromAvatarMenu(page);

    await expect(page.getByTestId("profile-field-firstName")).toHaveValue("Ada");
    await expect(page.getByTestId("profile-field-lastName")).toHaveValue("Lovelace");
    await expect(page.getByTestId("profile-field-email")).toHaveValue("ada@example.com");
    // The username is server-owned and must stay read-only.
    await expect(page.getByTestId("profile-field-username")).toHaveValue("admin");
    await expect(page.getByTestId("profile-field-username")).toBeDisabled();
  });

  test("saving sends only the changed fields and repopulates the form", async ({ page }) => {
    const { saveBodies } = await installMocks(page);
    await login(page);
    await openProfileFromAvatarMenu(page);

    await page.getByTestId("profile-field-email").fill("ada@lovelace.dev");
    await page.getByTestId("profile-save").click();

    await expect.poll(() => saveBodies.length, { timeout: 5_000 }).toBe(1);
    // Untouched fields are absent — a full-record write would clobber whatever else
    // changed on the user in the meantime.
    expect(saveBodies[0]).toEqual({ email: "ada@lovelace.dev" });

    await expect(page.getByTestId("profile-error")).toBeHidden();
    await expect(page.getByTestId("profile-field-email")).toHaveValue("ada@lovelace.dev");
    await expect(page.getByTestId("profile-field-firstName")).toHaveValue("Ada");
  });

  test("a rejected save surfaces an error and leaves the form editable", async ({ page }) => {
    const { saveBodies, user } = await installMocks(page, 403);
    await login(page);
    await openProfileFromAvatarMenu(page);

    await page.getByTestId("profile-field-firstName").fill("Grace");
    await page.getByTestId("profile-save").click();

    await expect(page.getByTestId("profile-error")).toBeVisible({ timeout: 5_000 });
    expect(saveBodies).toEqual([{ firstname: "Grace" }]);
    expect(user.firstname).toBe("Ada");

    // The edit is not discarded and the user can retry.
    await expect(page.getByTestId("profile-field-firstName")).toHaveValue("Grace");
    await expect(page.getByTestId("profile-field-firstName")).toBeEditable();
    await expect(page.getByTestId("profile-save")).toBeEnabled();

    await page.getByTestId("profile-field-firstName").fill("Grace Hopper");
    await page.getByTestId("profile-save").click();
    await expect.poll(() => saveBodies.length, { timeout: 5_000 }).toBe(2);
    expect(saveBodies[1]).toEqual({ firstname: "Grace Hopper" });
  });

  test("logout from the same menu returns to the login form", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openProfileFromAvatarMenu(page);

    await page.getByTestId("sidebar-avatar-button").click();
    await page.getByTestId("sidebar-avatar-logout").click();

    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("profile-view")).toBeHidden();
  });
});
