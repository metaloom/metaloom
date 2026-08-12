import { test, expect, Page } from "@playwright/test";

/**
 * The owner's side: creating a share link from a collection card and from the asset action menu.
 *
 * Clipboard access needs granting explicitly — this is the first place in the application that
 * writes to it, so there is no existing context configuration to inherit.
 */

const USERNAME = "admin";
const ME_UUID = "11111111-1111-1111-1111-111111111111";
const COLLECTION_UUID = "c0000000-0000-0000-0000-000000000001";
const ASSET_UUID = "a0000000-0000-0000-0000-000000000001";
const SHARE_UUID = "50000000-0000-0000-0000-000000000001";
const SLUG = "k3Rm2pQwXbN7vTsLd9aYc1";
const SHARE_URL = `http://localhost:3000/ui/share/${SLUG}`;

function json(body: unknown, status = 200) {
  return { status, contentType: "application/json", body: JSON.stringify(body) };
}

/**
 * Every write the dialog makes, so a test can assert on the body rather than on the screen alone —
 * `deletes` included, because "Done leaves the link in place" is only provable by nothing arriving.
 */
type Captured = { creates: Record<string, unknown>[]; updates: Record<string, unknown>[]; deletes: string[] };

function newCaptured(): Captured {
  return { creates: [], updates: [], deletes: [] };
}

function shareResponse(overrides: Record<string, unknown> = {}) {
  return {
    uuid: SHARE_UUID,
    slug: SLUG,
    url: SHARE_URL,
    targetType: "COLLECTION",
    targetUuid: COLLECTION_UUID,
    targetName: "Autumn campaign",
    passwordProtected: true,
    expired: false,
    allowDownload: true,
    showMetadata: true,
    allowComments: true,
    allowReactions: true,
    allowAnnotations: true,
    viewCount: 0,
    feedbackCount: 0,
    status: { created: "2026-08-11T10:00:00Z", edited: "2026-08-11T10:00:00Z" },
    ...overrides,
  };
}

/** A shareable asset, for the second way into this dialog: the asset-detail overflow menu. */
function assetResponse() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "team-meeting.mp4", mimeType: "video/mp4", size: 52_000_000 },
    status: { creator: { uuid: ME_UUID }, created: "2026-08-01T10:00:00Z" },
  };
}

async function mockBackend(page: Page, captured: Captured, options: { createStatus?: number } = {}) {
  await page.route("**/api/v1/**", (route) => route.fulfill(json({ data: [] })));

  await page.route("**/api/v1/login", (route) => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/me", (route) =>
    route.fulfill(json({ uuid: ME_UUID, username: USERNAME, enabled: true })),
  );

  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, (route) => route.fulfill(json(assetResponse())));

  await page.route(/\/api\/v1\/collections(\?|$)/, (route) =>
    route.fulfill(
      json({
        data: [
          {
            uuid: COLLECTION_UUID,
            name: "Autumn campaign",
            status: { created: "2026-08-01T10:00:00Z", edited: "2026-08-01T10:00:00Z" },
          },
        ],
        _metainfo: { totalCount: 1, perPage: 25 },
      }),
    ),
  );

  await page.route(/\/api\/v1\/share-links\/[^/]+$/, async (route) => {
    if (route.request().method() === "DELETE") {
      captured.deletes.push(route.request().url());
      return route.fulfill({ status: 204, body: "" });
    }
    captured.updates.push(JSON.parse(route.request().postData() ?? "{}"));
    return route.fulfill(json(shareResponse()));
  });

  await page.route(/\/api\/v1\/share-links$/, async (route) => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      captured.creates.push(body);
      if (options.createStatus && options.createStatus >= 400) {
        return route.fulfill(json({ message: "the link could not be minted" }, options.createStatus));
      }
      // The password comes back exactly once, on create — the dialog has to show it immediately.
      return route.fulfill(json(shareResponse({ password: body.password }), 201));
    }
    return route.fulfill(json({ data: [] }));
  });
}

/**
 * Deep-link first, then sign in.
 *
 * Auth is in-memory, so a `goto` after signing in throws the token away and lands back on the
 * login form. Signing in on the requested URL resolves to that route instead — the same order
 * `search-indices-mocked` and `db-integrity-mocked` use.
 */
async function openAndLogin(page: Page, path: string) {
  await page.goto(path);
  await page.getByPlaceholder("Username").fill(USERNAME);
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.use({ permissions: ["clipboard-read", "clipboard-write"] });

test.describe("Share dialog – mocked", () => {
  test("sharing a collection mints a link, shows its password once, and copies the URL", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });

    await expect(page.getByTestId("share-dialog-title")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-dialog-link")).toHaveValue(SHARE_URL, { timeout: 10_000 });

    // The link exists as soon as the dialog opens — there is no "create" button to press first.
    expect(captured.creates).toHaveLength(1);
    expect(captured.creates[0]).toMatchObject({ targetType: "COLLECTION", targetUuid: COLLECTION_UUID });

    // A generated password is prefilled and readable, not hidden behind a reveal.
    const password = await page.getByTestId("share-dialog-password").inputValue();
    expect(password).toMatch(/^[a-z]+-[a-z]+-\d{2}$/);

    await page.getByTestId("share-dialog-copy").click();
    const clipboard = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboard).toBe(SHARE_URL);
  });

  test("sharing an asset mints an ASSET link from the asset-detail overflow menu", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, `/ui/assets/${ASSET_UUID}`);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByTestId("asset-share-menu-item").click();

    await expect(page.getByTestId("share-dialog-title")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-dialog-link")).toHaveValue(SHARE_URL, { timeout: 10_000 });

    // The one thing that differs from the collection route: the target the link is minted for.
    // Sharing an asset through a COLLECTION link would hand the reviewer the wrong library.
    expect(captured.creates).toHaveLength(1);
    expect(captured.creates[0]).toMatchObject({ targetType: "ASSET", targetUuid: ASSET_UUID });
    // And the dialog names what is being shared, so the asset is identifiable before sending.
    await expect(page.getByTestId("share-dialog-title")).toContainText("team-meeting.mp4");
  });

  test("changing the expiry updates the same link rather than making a second one", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });
    await expect(page.getByTestId("share-dialog-link")).toHaveValue(SHARE_URL, { timeout: 10_000 });

    await page.getByTestId("share-dialog-expiry").click();
    await page.getByRole("option", { name: "Never" }).click();

    await expect.poll(() => captured.updates.length).toBeGreaterThan(0);
    expect(captured.updates[captured.updates.length - 1]).toMatchObject({ clearExpiry: true });
    expect(captured.creates).toHaveLength(1);
  });

  test("turning the password off sends removePassword and hides the field", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });
    await expect(page.getByTestId("share-dialog-password")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("share-dialog-password-toggle").click();

    await expect(page.getByTestId("share-dialog-password")).toHaveCount(0);
    await expect.poll(() => captured.updates.length).toBeGreaterThan(0);
    expect(captured.updates[captured.updates.length - 1]).toMatchObject({ removePassword: true });
  });

  test("the dialog warns that one link carries one name", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });

    // The consequence of the one-name-per-link identity model, said before it surprises anybody.
    await expect(page.getByText(/one link per reviewer/i)).toBeVisible({ timeout: 10_000 });
  });

  test("a create that fails leaves the dialog open and says so", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured, { createStatus: 500 });
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });

    await expect(page.getByTestId("share-dialog-error")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-dialog-error")).toContainText(/could not be created/i);

    // Staying open is the whole point: a dialog that closes on a failed write reads as success,
    // and the user walks away believing they have a link to send.
    await expect(page.getByTestId("share-dialog-title")).toBeVisible();
    await expect(page.getByTestId("share-dialog-link")).toHaveValue("");
    // Nothing to copy, so the button that would put an empty string on the clipboard is disabled.
    await expect(page.getByTestId("share-dialog-copy")).toBeDisabled();
    expect(captured.creates).toHaveLength(1);
  });

  test("Done closes the dialog and leaves the minted link alive", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });
    await expect(page.getByTestId("share-dialog-link")).toHaveValue(SHARE_URL, { timeout: 10_000 });

    await page.getByTestId("share-dialog-done").click();

    await expect(page.getByTestId("share-dialog-title")).toHaveCount(0);
    // Done is not Cancel. The link has been sent to somebody by now, so closing must not revoke it.
    expect(captured.deletes).toHaveLength(0);
    expect(captured.creates).toHaveLength(1);
  });

  test("turning downloading off travels in the update body", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });
    await expect(page.getByTestId("share-dialog-link")).toHaveValue(SHARE_URL, { timeout: 10_000 });

    const download = page.getByRole("checkbox", { name: /allow downloading/i });
    await expect(download).toBeChecked();
    await page.getByTestId("share-dialog-download").click();

    await expect.poll(() => captured.updates.length).toBeGreaterThan(0);
    expect(captured.updates[captured.updates.length - 1]).toMatchObject({ allowDownload: false });
    // The mocked response still says allowDownload: true; the box must follow what was asked for,
    // not what the stale echo carries back.
    await expect(download).not.toBeChecked();
  });

  test("turning feedback off sends all three feedback flags together", async ({ page }) => {
    const captured = newCaptured();
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });
    await expect(page.getByTestId("share-dialog-link")).toHaveValue(SHARE_URL, { timeout: 10_000 });

    const feedback = page.getByRole("checkbox", { name: /comments, marks and reactions/i });
    await expect(feedback).toBeChecked();
    await page.getByTestId("share-dialog-feedback").click();

    await expect.poll(() => captured.updates.length).toBeGreaterThan(0);
    // One checkbox, three server flags — a link that allows reactions but not comments is not
    // something this dialog offers, and the update must not leave one of them behind.
    expect(captured.updates[captured.updates.length - 1]).toMatchObject({
      allowComments: false,
      allowReactions: false,
      allowAnnotations: false,
    });
    await expect(feedback).not.toBeChecked();
  });
});
