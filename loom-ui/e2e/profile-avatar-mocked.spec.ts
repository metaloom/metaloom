import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the profile picture on `/profile`.
 *
 * This screen had a file picker for its whole life that only ever produced a local `FileReader`
 * preview - nothing was uploaded, and the picture vanished on the next reload. So what is under
 * test here is specifically that the preview is a *pending* value rather than the value:
 *
 *  - a successful pick uploads and then shows the picture the server actually holds;
 *  - a failed upload puts the old one back, rather than leaving a picture on screen that nobody
 *    stored - which is the original bug, made subtler.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const AVATAR_URL = `/api/v1/users/${ME_UUID}/avatar/data`;

/** A one-pixel PNG, so the picker has real bytes to hand to the upload. */
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function me(avatarUrl: string | null) {
  return {
    uuid: ME_UUID,
    username: "admin",
    firstname: "Ada",
    lastname: "Lovelace",
    email: "ada@metaloom.io",
    enabled: true,
    avatarUrl,
  };
}

async function installMocks(page: Page, avatarUrl: string | null = null) {
  // Registered first so the specific routes below win - Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, me(avatarUrl)));
  await page.route(new RegExp(`/api/v1/users/${ME_UUID}$`), route => json(route, me(avatarUrl)));
  // The picture itself: an <img src> cannot carry a bearer token, so this is served on the session
  // cookie in production. Here it just has to return bytes.
  await page.route(new RegExp(`${AVATAR_URL}$`), route =>
    route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
}

async function open(page: Page) {
  await page.goto("/ui/profile");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function pickAPicture(page: Page) {
  await page.getByTestId("profile-avatar-input").setInputFiles({
    name: "me.png",
    mimeType: "image/png",
    buffer: PNG,
  });
}

test.describe("Profile picture - mocked", () => {

  test("picking a file uploads it and shows what the server stored", async ({ page }) => {
    await installMocks(page);
    let posted = 0;
    let contentType: string | null = null;
    await page.route(/\/api\/v1\/me\/avatar$/, route => {
      if (route.request().method() !== "POST") return route.fallback();
      posted += 1;
      contentType = route.request().headers()["content-type"] ?? null;
      return json(route, { uuid: "aaaa", filename: "me.png", mimeType: "image/png", size: 68, url: AVATAR_URL });
    });

    await open(page);
    await expect(page.getByTestId("profile-avatar")).toBeVisible({ timeout: 10_000 });
    await pickAPicture(page);

    await expect.poll(() => posted).toBe(1);
    // The boundary has to come from the browser. Setting Content-Type by hand produces a body the
    // server cannot parse, which is a documented trap in REST_BINARY_HANDLING.md.
    expect(contentType).toContain("multipart/form-data");
    expect(contentType).toContain("boundary=");

    // The served URL, not the FileReader data: from here the picture is whatever the account holds,
    // and every other screen renders it from the same place.
    await expect(page.getByTestId("profile-avatar").locator("img"))
      .toHaveAttribute("src", AVATAR_URL, { timeout: 10_000 });
  });

  test("a failed upload clears the preview and says why", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/me\/avatar$/, route =>
      route.request().method() === "POST"
        ? json(route, { message: "Not enough space" }, 507)
        : route.fallback());

    await open(page);
    await expect(page.getByTestId("profile-avatar")).toBeVisible({ timeout: 10_000 });
    await pickAPicture(page);

    await expect(page.getByTestId("profile-avatar-error")).toBeVisible({ timeout: 10_000 });
    // The original failure mode, made subtler: a preview left on screen after a failed upload is a
    // picture the user believes they have and the server has never seen.
    await expect(page.getByTestId("profile-avatar").locator("img")).toHaveCount(0);
  });

  test("an account with a picture offers to remove it, and one without does not", async ({ page }) => {
    await installMocks(page, AVATAR_URL);
    let deleted = 0;
    await page.route(/\/api\/v1\/me\/avatar$/, route => {
      if (route.request().method() !== "DELETE") return route.fallback();
      deleted += 1;
      return route.fulfill({ status: 204, body: "" });
    });

    await open(page);
    await expect(page.getByTestId("profile-avatar").locator("img"))
      .toHaveAttribute("src", AVATAR_URL, { timeout: 10_000 });

    await page.getByTestId("profile-avatar-remove").click();

    await expect.poll(() => deleted).toBe(1);
    await expect(page.getByTestId("profile-avatar").locator("img")).toHaveCount(0);
    // The button goes with the picture: offering to remove something that is not there is noise.
    await expect(page.getByTestId("profile-avatar-remove")).toHaveCount(0);
  });

  test("an account with no picture shows initials and no remove button", async ({ page }) => {
    await installMocks(page);
    await open(page);

    await expect(page.getByTestId("profile-avatar")).toHaveText("AL", { timeout: 10_000 });
    await expect(page.getByTestId("profile-avatar-remove")).toHaveCount(0);
  });
});
