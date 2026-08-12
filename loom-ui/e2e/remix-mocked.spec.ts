import { test, expect, Page } from "@playwright/test";

/**
 * Mocked tests for the remix surfaces in the asset browser.
 *
 * No running Loom backend is required: every REST call is intercepted with `page.route`. The
 * tests drive the real UI — the pinned remix band, the opened remix dialog, and the selection
 * tray's "Combine into remix" — and assert both what the user sees and the shape of the requests
 * the client produces.
 */

const SHA512 = "a".repeat(128);
const REMIX_UUID = "9d1c1c6a-5b1a-4d33-8f0f-1a2b3c4d5e6f";
const ASSET_A = "11111111-1111-1111-1111-111111111111";
const ASSET_B = "22222222-2222-2222-2222-222222222222";

function assetResponse(uuid: string, filename: string) {
  return {
    uuid,
    file: { filename, mimeType: "image/jpeg", size: 1024, origin: "upload" },
    hashes: { sha512: SHA512 },
    tags: [],
    status: { created: new Date(0).toISOString() },
  };
}

function remixResponse(memberCount = 2) {
  return {
    uuid: REMIX_UUID,
    name: "Coastal drone cuts",
    description: "The original and the cuts made from it.",
    sourceAssetUuid: ASSET_A,
    memberCount,
    status: { created: new Date(0).toISOString() },
  };
}

function memberList() {
  return {
    data: [
      {
        uuid: "aaaa1111-0000-0000-0000-000000000001",
        assetUuid: ASSET_A,
        role: "SOURCE",
        ordinal: 0,
        filename: "drone-coastal.mp4",
        mimeType: "video/mp4",
        size: 52_000_000,
      },
      {
        uuid: "aaaa1111-0000-0000-0000-000000000002",
        assetUuid: ASSET_B,
        role: "DERIVED",
        ordinal: 1,
        filename: "coastal-cut.mp4",
        mimeType: "video/mp4",
        size: 12_000_000,
      },
    ],
  };
}

interface Captured {
  creates: unknown[];
  memberAdds: unknown[];
  memberDeletes: string[];
  sourceSets: unknown[];
  /** Every /search/* URL the page asked for, so a test can assert which route the filter used. */
  searches: string[];
}

const json = (body: unknown, status = 200) => ({
  status,
  contentType: "application/json",
  body: JSON.stringify(body),
});

/**
 * Install the baseline REST mocks.
 *
 * Order matters: Playwright matches the most recently registered handler first, so the catch-all
 * goes in before everything it must not shadow. And the list matchers have to tolerate a query
 * string, because every list client appends `?limit=`.
 */
async function mockRest(page: Page): Promise<Captured> {
  const captured: Captured = { creates: [], memberAdds: [], memberDeletes: [], sourceSets: [], searches: [] };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route(/\/api\/v1\/libraries(\?|$)/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [{ uuid: "lib-1", name: "Main Library" }] }) })
  );

  // Search. /search/results is where a remix can be returned at all - /search/assets forces
  // types={asset} server-side.
  await page.route(/\/api\/v1\/search\/results(\?.*)?$/, route => {
    captured.searches.push(route.request().url());
    const isRemix = route.request().url().includes("types=remix");
    return route.fulfill(json({
      data: isRemix
        ? [{ type: "remix", uuid: REMIX_UUID, score: 1, title: "Coastal drone cuts", subtitle: "The original and the cuts." }]
        : [],
      _metainfo: { totalHits: isRemix ? 1 : 0, totalExact: true, perPage: 25, offset: 0, tookMs: 1, provider: "postgres", capabilities: [], warnings: [] },
    }));
  });

  await page.route(/\/api\/v1\/search\/assets(\?.*)?$/, route => {
    captured.searches.push(route.request().url());
    return route.fulfill(json({
      data: [{ type: "asset", uuid: ASSET_A, assetUuid: ASSET_A, score: 1, title: "drone-coastal.mp4", mimeType: "video/mp4", size: 1024 }],
      _metainfo: { totalHits: 1, totalExact: true, perPage: 25, offset: 0, tookMs: 1, provider: "postgres", capabilities: [], warnings: [] },
    }));
  });

  await page.route(/\/api\/v1\/search\/status(\?.*)?$/, route =>
    route.fulfill(json({ available: true, provider: "postgres", capabilities: ["HIGHLIGHT"] })));

  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: [assetResponse(ASSET_A, "drone-coastal.mp4"), assetResponse(ASSET_B, "coastal-cut.mp4")] }),
    })
  );

  // Membership routes are registered before the /:uuid one for the same reason the server
  // registers them first: a bare uuid matcher would otherwise swallow "/assets" and "/source".
  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/assets\/[0-9a-f-]+$/, route => {
    captured.memberDeletes.push(route.request().url());
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/assets(\?.*)?$/, route => {
    if (route.request().method() === "POST") {
      captured.memberAdds.push(route.request().postDataJSON());
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(remixResponse(3)) });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(memberList()) });
  });

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/source$/, route => {
    captured.sourceSets.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(remixResponse()) });
  });

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+$/, route => {
    if (route.request().method() === "DELETE") {
      return route.fulfill({ status: 204, body: "" });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(remixResponse()) });
  });

  await page.route(/\/api\/v1\/remixes(\?.*)?$/, route => {
    if (route.request().method() === "POST") {
      captured.creates.push(route.request().postDataJSON());
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(remixResponse(2)) });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [remixResponse()] }) });
  });

  return captured;
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  // exact: true — role-name matching is substring-based.
  await page.getByRole("button", { name: "Assets", exact: true }).first().click();
  await expect(page.getByRole("heading", { name: "Assets" })).toBeVisible({ timeout: 10_000 });
}

/** Open the remix band's card and wait for its members to render. */
async function openRemix(page: Page) {
  await page.getByTestId("remix-card").click();
  await expect(page.getByTestId("remix-dialog")).toBeVisible();
  await expect(page.getByTestId("remix-member")).toHaveCount(2);
}

test.describe("Remix – mocked", () => {
  test("the remix band renders a card distinct from the asset cards", async ({ page }) => {
    await mockRest(page);
    await login(page);

    const card = page.getByTestId("remix-card");
    await expect(card).toHaveCount(1);
    await expect(card).toContainText("Coastal drone cuts");
    await expect(card).toContainText("2 assets");
  });

  test("clicking a remix card opens the dialog and puts the remix in the URL", async ({ page }) => {
    await mockRest(page);
    await login(page);

    await page.getByTestId("remix-card").click();

    await expect(page.getByTestId("remix-dialog")).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`remix=${REMIX_UUID}`));
    await expect(page.getByTestId("remix-member")).toHaveCount(2);
  });

  /**
   * The open state is driven by the URL, not by component state, so browser history moves through
   * it. That is what makes the dialog linkable and what a screenshot script relies on.
   *
   * Exercised with back/forward rather than a fresh `page.goto`: the auth token lives in memory
   * only, so a full reload logs the session out — an existing property of the app, not of this
   * feature.
   */
  test("the opened remix is driven by the URL, so history moves through it", async ({ page }) => {
    await mockRest(page);
    await login(page);

    await openRemix(page);
    await expect(page).toHaveURL(new RegExp(`remix=${REMIX_UUID}`));

    await page.getByRole("button", { name: "Close" }).last().click();
    await expect(page.getByTestId("remix-dialog")).toBeHidden();
    await expect(page).not.toHaveURL(/remix=/);

    await page.goBack();
    await expect(page).toHaveURL(new RegExp(`remix=${REMIX_UUID}`));
    await expect(page.getByTestId("remix-dialog")).toBeVisible();
    await expect(page.getByTestId("remix-member")).toHaveCount(2);
  });

  test("the source member is marked as the original", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await openRemix(page);

    const source = page.getByTestId("remix-member").filter({ hasText: "drone-coastal.mp4" });
    await expect(source).toContainText("Original");
  });

  test("removing a member issues a DELETE keyed by asset uuid", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openRemix(page);

    const derived = page.getByTestId("remix-member").filter({ hasText: "coastal-cut.mp4" });
    await derived.hover();
    await derived.getByTestId("remix-member-remove").click();

    await expect.poll(() => captured.memberDeletes.length).toBe(1);
    expect(captured.memberDeletes[0]).toContain(`/remixes/${REMIX_UUID}/assets/${ASSET_B}`);
  });

  test("promoting a member posts to /source", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openRemix(page);

    const derived = page.getByTestId("remix-member").filter({ hasText: "coastal-cut.mp4" });
    await derived.hover();
    await derived.getByTestId("remix-member-source").click();

    await expect.poll(() => captured.sourceSets.length).toBe(1);
    expect(captured.sourceSets[0]).toMatchObject({ sourceAssetUuid: ASSET_B });
  });

  /**
   * The selection tray path. One POST carrying every selected uuid — not a create followed by an
   * add, which would leave a named but empty remix behind if the second call failed.
   */
  test("combining a selection creates the remix and its members in one request", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "Select", exact: true }).click();
    const checkboxes = page.getByRole("checkbox");
    await checkboxes.nth(0).click();
    await checkboxes.nth(1).click();

    await page.getByTestId("bulk-actions-menu-button").click();
    await page.getByTestId("bulk-combine-remix").click();

    await expect(page.getByTestId("remix-create-dialog")).toBeVisible();
    await page.getByTestId("remix-create-name").fill("Beach set");
    await page.getByTestId("remix-create-submit").click();

    await expect.poll(() => captured.creates.length).toBe(1);
    expect(captured.creates[0]).toMatchObject({
      name: "Beach set",
      assetUuids: [ASSET_A, ASSET_B],
    });
  });

  // --- Search and filtering -------------------------------------------------

  /**
   * The "Remixes" option in the type filter. It narrows a different axis than the mime-based
   * options do, so it hides the asset grid rather than filtering it.
   */
  test("the Remixes filter shows only remix cards", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await expect(page.getByTestId("remix-card")).toHaveCount(1);
    const assetCardsBefore = await page.getByText("drone-coastal.mp4").count();
    expect(assetCardsBefore).toBeGreaterThan(0);

    await page.getByRole("combobox").first().click();
    await page.getByTestId("assets-filter-remix").click();

    await expect(page.getByTestId("remix-card")).toHaveCount(1);
    await expect(page.getByText("drone-coastal.mp4")).toHaveCount(0);
    await expect(page.getByTestId("assets-count")).toContainText("1 remix");
  });

  /**
   * Typing a query narrows the band to matching remixes, through /search/results with
   * types=remix. /search/assets could never answer this: it forces types={asset} server-side.
   */
  test("searching narrows the remix band through the remix search route", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await page.getByPlaceholder(/search assets/i).fill("coastal");

    await expect.poll(() => captured.searches.some(u => u.includes("/search/results") && u.includes("types=remix"))).toBe(true);
    await expect(page.getByTestId("remix-card")).toContainText("Coastal drone cuts");
  });

  /** A search hit carries no member count, so the card must show none rather than inventing one. */
  test("a searched remix card shows no member count", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await expect(page.getByTestId("remix-card")).toContainText("2 assets");

    await page.getByPlaceholder(/search assets/i).fill("coastal");

    await expect(page.getByTestId("remix-card")).toContainText("Coastal drone cuts");
    await expect(page.getByTestId("remix-card")).not.toContainText("assets");
  });

  test("the combine action is disabled until something is selected", async ({ page }) => {
    await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "Select", exact: true }).click();

    await expect(page.getByTestId("bulk-actions-menu-button")).toBeDisabled();
  });
});
