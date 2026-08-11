import { test, expect, Page } from "@playwright/test";

/**
 * The owner's side: creating a share link from a collection card and from the asset action menu.
 *
 * Clipboard access needs granting explicitly — this is the first place in the application that
 * writes to it, so there is no existing context configuration to inherit.
 */

const USERNAME = "admin";
const COLLECTION_UUID = "c0000000-0000-0000-0000-000000000001";
const SHARE_UUID = "50000000-0000-0000-0000-000000000001";
const SLUG = "k3Rm2pQwXbN7vTsLd9aYc1";
const SHARE_URL = `http://localhost:3000/ui/share/${SLUG}`;

function json(body: unknown, status = 200) {
  return { status, contentType: "application/json", body: JSON.stringify(body) };
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

async function mockBackend(page: Page, captured: { creates: unknown[]; updates: unknown[] }) {
  await page.route("**/api/v1/**", (route) => route.fulfill(json({ data: [] })));

  await page.route("**/api/v1/login", (route) => route.fulfill(json({ token: "fake-jwt" })));
  await page.route("**/api/v1/me", (route) =>
    route.fulfill(json({ uuid: "11111111-1111-1111-1111-111111111111", username: USERNAME, enabled: true })),
  );

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
    captured.updates.push(JSON.parse(route.request().postData() ?? "{}"));
    return route.fulfill(json(shareResponse()));
  });

  await page.route(/\/api\/v1\/share-links$/, async (route) => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      captured.creates.push(body);
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
    const captured = { creates: [] as unknown[], updates: [] as unknown[] };
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

  test("changing the expiry updates the same link rather than making a second one", async ({ page }) => {
    const captured = { creates: [] as unknown[], updates: [] as unknown[] };
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
    const captured = { creates: [] as unknown[], updates: [] as unknown[] };
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
    const captured = { creates: [] as unknown[], updates: [] as unknown[] };
    await mockBackend(page, captured);
    await openAndLogin(page, "/ui/collections");
    await page.getByTestId("collection-share").first().click({ force: true });

    // The consequence of the one-name-per-link identity model, said before it surprises anybody.
    await expect(page.getByText(/one link per reviewer/i)).toBeVisible({ timeout: 10_000 });
  });
});
