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
/** Two further remixes, so the picker has a list worth filtering. Only served with `moreRemixes`. */
const REMIX_B_UUID = "3f5e7a90-1111-4222-8333-444455556666";
const REMIX_C_UUID = "8b1d2c30-9999-4888-8777-666655554444";
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
    name: "Team meeting cuts",
    description: "The original and the cuts made from it.",
    sourceAssetUuid: ASSET_A,
    memberCount,
    status: { created: new Date(0).toISOString() },
  };
}

/**
 * The other remixes the account owns. They exist so the picker's list is longer than one entry —
 * "offers the remixes" and "filters them" are not assertable against a single option.
 */
function otherRemixes() {
  return [
    {
      uuid: REMIX_B_UUID,
      name: "Sunset timelapse",
      description: "Every take of the same sunset.",
      sourceAssetUuid: null,
      memberCount: 4,
      status: { created: new Date(0).toISOString() },
    },
    {
      uuid: REMIX_C_UUID,
      name: "Harbour stills",
      description: "Frames pulled from the harbour clip.",
      sourceAssetUuid: null,
      memberCount: 1,
      status: { created: new Date(0).toISOString() },
    },
  ];
}

function memberList() {
  return {
    data: [
      {
        uuid: "aaaa1111-0000-0000-0000-000000000001",
        assetUuid: ASSET_A,
        role: "SOURCE",
        ordinal: 0,
        filename: "team-meeting.mp4",
        mimeType: "video/mp4",
        size: 52_000_000,
      },
      {
        uuid: "aaaa1111-0000-0000-0000-000000000002",
        assetUuid: ASSET_B,
        role: "DERIVED",
        ordinal: 1,
        filename: "team-meeting-cut.mp4",
        mimeType: "video/mp4",
        size: 12_000_000,
      },
    ],
  };
}

interface Captured {
  creates: unknown[];
  memberAdds: unknown[];
  /** The URL each member-add went to — the body alone cannot say *which* remix was picked. */
  memberAddUrls: string[];
  memberDeletes: string[];
  sourceSets: unknown[];
  /** Bodies of the update calls. A rename is a POST to /remixes/:uuid, not a PATCH. */
  updates: unknown[];
  remixDeletes: string[];
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
 *
 * The remix itself is *stateful* here: its name, its membership and whether it still exists are
 * held in closures the handlers mutate. Writes in this feature are only observable through the
 * read that follows them — the dialog reloads after every member change — so a mock that always
 * replayed the same two members could not tell a working remove from a no-op.
 */
async function mockRest(
  page: Page,
  options: { assetInRemix?: boolean; moreRemixes?: boolean } = {},
): Promise<Captured> {
  const captured: Captured = {
    creates: [], memberAdds: [], memberAddUrls: [], memberDeletes: [],
    sourceSets: [], updates: [], remixDeletes: [], searches: [],
  };

  // Server-side state for REMIX_UUID.
  const members = memberList().data;
  let remixName = "Team meeting cuts";
  let remixDeleted = false;

  /** The remix as it stands now — its count derived from the membership, never asserted twice. */
  const currentRemix = () => ({ ...remixResponse(members.length), name: remixName });

  const remixByUuid = (uuid: string) =>
    uuid === REMIX_UUID ? currentRemix() : otherRemixes().find(r => r.uuid === uuid) ?? currentRemix();

  /**
   * The remixes `GET /assets/:uuid/remixes` reports for ASSET_A — its own route, not a field on
   * the asset response, so it needs its own mock and its own state. Adding the asset to a remix
   * pushes onto this, which is what lets the chip appear without a reload.
   */
  const assetRemixes: unknown[] = options.assetInRemix ? [remixResponse()] : [];

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
        ? [{ type: "remix", uuid: REMIX_UUID, score: 1, title: "Team meeting cuts", subtitle: "The original and the cuts." }]
        : [],
      _metainfo: { totalHits: isRemix ? 1 : 0, totalExact: true, perPage: 25, offset: 0, tookMs: 1, provider: "postgres", capabilities: [], warnings: [] },
    }));
  });

  await page.route(/\/api\/v1\/search\/assets(\?.*)?$/, route => {
    captured.searches.push(route.request().url());
    return route.fulfill(json({
      data: [{ type: "asset", uuid: ASSET_A, assetUuid: ASSET_A, score: 1, title: "team-meeting.mp4", mimeType: "video/mp4", size: 1024 }],
      _metainfo: { totalHits: 1, totalExact: true, perPage: 25, offset: 0, tookMs: 1, provider: "postgres", capabilities: [], warnings: [] },
    }));
  });

  await page.route(/\/api\/v1\/search\/status(\?.*)?$/, route =>
    route.fulfill(json({ available: true, provider: "postgres", capabilities: ["HIGHLIGHT"] })));

  await page.route(/\/api\/v1\/assets(\?.*)?$/, route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: [assetResponse(ASSET_A, "team-meeting.mp4"), assetResponse(ASSET_B, "team-meeting-cut.mp4")] }),
    })
  );

  // Membership routes are registered before the /:uuid one for the same reason the server
  // registers them first: a bare uuid matcher would otherwise swallow "/assets" and "/source".
  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/assets\/[0-9a-f-]+$/, route => {
    const url = route.request().url();
    captured.memberDeletes.push(url);
    // Drop the membership, so the reload the dialog does next reflects the removal.
    const assetUuid = url.split("/").pop() ?? "";
    const index = members.findIndex(m => m.assetUuid === assetUuid);
    if (index >= 0) members.splice(index, 1);
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/assets(\?.*)?$/, route => {
    const url = route.request().url();
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      captured.memberAdds.push(body);
      captured.memberAddUrls.push(url);
      // Which remix was picked is in the URL, not the body — the chip must name that one.
      const target = remixByUuid(url.match(/remixes\/([0-9a-f-]+)\/assets/)?.[1] ?? REMIX_UUID);
      const grown = { ...target, memberCount: (target.memberCount ?? 0) + 1 };
      if ((body.assetUuids ?? []).includes(ASSET_A)) assetRemixes.push(grown);
      return route.fulfill(json(grown));
    }
    return route.fulfill(json({ data: members }));
  });

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+\/source$/, route => {
    captured.sourceSets.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(remixResponse()) });
  });

  await page.route(/\/api\/v1\/remixes\/[0-9a-f-]+$/, route => {
    const method = route.request().method();
    if (method === "DELETE") {
      captured.remixDeletes.push(route.request().url());
      remixDeleted = true;
      return route.fulfill({ status: 204, body: "" });
    }
    if (method === "POST") {
      // Update is POST, not PATCH — the server's convention throughout (see api/remixes.ts).
      const body = route.request().postDataJSON();
      captured.updates.push(body);
      if (body.name) remixName = body.name;
      return route.fulfill(json(currentRemix()));
    }
    return route.fulfill(json(currentRemix()));
  });

  await page.route(/\/api\/v1\/remixes(\?.*)?$/, route => {
    if (route.request().method() === "POST") {
      captured.creates.push(route.request().postDataJSON());
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(remixResponse(2)) });
    }
    const data = [
      ...(remixDeleted ? [] : [currentRemix()]),
      ...(options.moreRemixes ? otherRemixes() : []),
    ];
    return route.fulfill(json({ data }));
  });

  // Asset-detail routes, registered last so the asset-scoped remix list is matched before the
  // bare-uuid asset route that would otherwise swallow it.
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+$/, route =>
    route.fulfill(json(assetResponse(ASSET_A, "team-meeting.mp4")))
  );
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]+\/remixes(\?.*)?$/, route =>
    route.fulfill(json({ data: assetRemixes }))
  );

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

/**
 * Land on the asset-detail view for ASSET_A.
 *
 * Deep-link first, then sign in: auth is in-memory, so a `goto` after signing in throws the
 * token away and lands back on the login form.
 */
async function openAssetDetail(page: Page) {
  await page.goto(`/ui/assets/${ASSET_A}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("asset-actions-menu-button")).toBeVisible({ timeout: 10_000 });
}

/** Open the remix band's card and wait for its members to render. */
async function openRemix(page: Page) {
  await page.getByTestId("remix-card").click();
  await expect(page.getByTestId("remix-dialog")).toBeVisible();
  await expect(page.getByTestId("remix-member")).toHaveCount(2);
}

/** Open the asset-detail overflow menu's "Add to remix…" dialog. */
async function openAddToRemix(page: Page) {
  await page.getByTestId("asset-actions-menu-button").click();
  await page.getByTestId("asset-add-to-remix-menu-item").click();
  await expect(page.getByTestId("add-to-remix-dialog")).toBeVisible();
}

/**
 * Remove one member by filename.
 *
 * The remove button only appears on hover, and the whole grid is disabled while the write is in
 * flight — so removing two in a row has to wait for the first to settle, which the caller does by
 * asserting the new count between calls.
 */
async function removeMember(page: Page, filename: string) {
  const row = page.getByTestId("remix-member").filter({ hasText: filename });
  await row.hover();
  await row.getByTestId("remix-member-remove").click();
}

test.describe("Remix – mocked", () => {
  test("the remix band renders a card distinct from the asset cards", async ({ page }) => {
    await mockRest(page);
    await login(page);

    const card = page.getByTestId("remix-card");
    await expect(card).toHaveCount(1);
    await expect(card).toContainText("Team meeting cuts");
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

    const source = page.getByTestId("remix-member").filter({ hasText: "team-meeting.mp4" });
    await expect(source).toContainText("Original");
  });

  test("removing a member issues a DELETE keyed by asset uuid", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openRemix(page);

    const derived = page.getByTestId("remix-member").filter({ hasText: "team-meeting-cut.mp4" });
    await derived.hover();
    await derived.getByTestId("remix-member-remove").click();

    await expect.poll(() => captured.memberDeletes.length).toBe(1);
    expect(captured.memberDeletes[0]).toContain(`/remixes/${REMIX_UUID}/assets/${ASSET_B}`);
  });

  test("promoting a member posts to /source", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openRemix(page);

    const derived = page.getByTestId("remix-member").filter({ hasText: "team-meeting-cut.mp4" });
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
    const assetCardsBefore = await page.getByText("team-meeting.mp4").count();
    expect(assetCardsBefore).toBeGreaterThan(0);

    await page.getByRole("combobox").first().click();
    await page.getByTestId("assets-filter-remix").click();

    await expect(page.getByTestId("remix-card")).toHaveCount(1);
    await expect(page.getByText("team-meeting.mp4")).toHaveCount(0);
    await expect(page.getByTestId("assets-count")).toContainText("1 remix");
  });

  /**
   * Typing a query narrows the band to matching remixes, through /search/results with
   * types=remix. /search/assets could never answer this: it forces types={asset} server-side.
   */
  test("searching narrows the remix band through the remix search route", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);

    await page.getByPlaceholder(/search assets/i).fill("meeting");

    await expect.poll(() => captured.searches.some(u => u.includes("/search/results") && u.includes("types=remix"))).toBe(true);
    await expect(page.getByTestId("remix-card")).toContainText("Team meeting cuts");
  });

  /** A search hit carries no member count, so the card must show none rather than inventing one. */
  test("a searched remix card shows no member count", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await expect(page.getByTestId("remix-card")).toContainText("2 assets");

    await page.getByPlaceholder(/search assets/i).fill("meeting");

    await expect(page.getByTestId("remix-card")).toContainText("Team meeting cuts");
    await expect(page.getByTestId("remix-card")).not.toContainText("assets");
  });

  test("the combine action is disabled until something is selected", async ({ page }) => {
    await mockRest(page);
    await login(page);

    await page.getByRole("button", { name: "Select", exact: true }).click();

    await expect(page.getByTestId("bulk-actions-menu-button")).toBeDisabled();
  });

  /**
   * The other way into a remix: one asset at a time, from the detail view's overflow menu. The
   * selection tray builds a group out of many assets; this adds the one you are looking at.
   */
  test("the asset overflow menu adds this asset to an existing remix", async ({ page }) => {
    const captured = await mockRest(page);
    await openAssetDetail(page);

    // Nothing yet — the chip must be earned by the add, not by the page merely rendering.
    await expect(page.getByTestId("asset-remix-chip")).toHaveCount(0);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByTestId("asset-add-to-remix-menu-item").click();
    await expect(page.getByTestId("add-to-remix-dialog")).toBeVisible();

    // The picker is a freeSolo Autocomplete over the existing remixes; choosing one from the
    // list must add to it rather than create a second remix with the same name.
    await page.getByTestId("add-to-remix-input").click();
    await page.getByRole("option", { name: "Team meeting cuts" }).click();
    await page.getByTestId("add-to-remix-submit").click();

    await expect.poll(() => captured.memberAdds.length).toBe(1);
    expect(captured.memberAdds[0]).toEqual({ assetUuids: [ASSET_A] });
    expect(captured.creates).toHaveLength(0);

    // The dialog closes and the membership is re-read, so the chip arrives without a reload.
    await expect(page.getByTestId("add-to-remix-dialog")).toHaveCount(0);
    await expect(page.getByTestId("asset-remix-chip")).toContainText("Team meeting cuts");
  });

  test("a remix chip links back to the asset browser with that remix open", async ({ page }) => {
    await mockRest(page, { assetInRemix: true });
    await openAssetDetail(page);

    const chip = page.getByTestId("asset-remix-chip");
    await expect(chip).toHaveCount(1);
    await expect(chip).toContainText("Team meeting cuts");

    await chip.click();

    // The same `?remix=` deep link the grid writes — one way into an opened remix, not two.
    await expect(page).toHaveURL(new RegExp(`/assets\\?remix=${REMIX_UUID}$`), { timeout: 5_000 });
    await expect(page.getByTestId("remix-dialog")).toBeVisible({ timeout: 10_000 });
  });

  // --- The picker: which remix an asset joins ------------------------------

  /**
   * The dialog's contents, as opposed to the fact that it opens. Its list is the account's
   * remixes — everything an asset could join. A picker that offered a stale or truncated list
   * would send the asset to the wrong group, and the only symptom is a chip with the wrong name.
   */
  test("the picker offers every existing remix and refuses to submit until one is chosen", async ({ page }) => {
    await mockRest(page, { moreRemixes: true });
    await openAssetDetail(page);
    await openAddToRemix(page);

    // Nothing chosen yet — a bare "Add" with no target would either 404 or create a nameless remix.
    await expect(page.getByTestId("add-to-remix-submit")).toBeDisabled();

    await page.getByTestId("add-to-remix-input").click();
    const options = page.getByRole("option");
    await expect(options).toHaveCount(3);
    await expect(options.nth(0)).toHaveText("Team meeting cuts");
    await expect(options.nth(1)).toHaveText("Sunset timelapse");
    await expect(options.nth(2)).toHaveText("Harbour stills");
  });

  /**
   * Typing filters the list. The control is freeSolo, so what is typed is also a candidate *name*
   * for a new remix — filtering to nothing therefore means "this name is free", not "no match".
   */
  test("typing in the picker filters the offered remixes", async ({ page }) => {
    await mockRest(page, { moreRemixes: true });
    await openAssetDetail(page);
    await openAddToRemix(page);

    // Open the list before typing. MUI's filter is skipped for the keystroke that opens the popup
    // (handleInputChange clears `inputPristine`, then handleOpen sets it again), so a single
    // atomic fill into a closed picker narrows nothing — real typing self-corrects on the second
    // character, and clicking the field first is what a user does anyway.
    await page.getByTestId("add-to-remix-input").click();
    await page.getByTestId("add-to-remix-input").fill("harb");
    await expect(page.getByRole("option")).toHaveCount(1);
    await expect(page.getByRole("option")).toHaveText("Harbour stills");

    await page.getByTestId("add-to-remix-input").fill("timelapse");
    await expect(page.getByRole("option")).toHaveText("Sunset timelapse");

    // A name that matches nothing leaves no option to click, and the submit stays enabled: the
    // typed text is the new remix's name.
    await page.getByTestId("add-to-remix-input").fill("Something entirely new");
    await expect(page.getByRole("option")).toHaveCount(0);
    await expect(page.getByTestId("add-to-remix-submit")).toBeEnabled();
  });

  /**
   * Submitting after a filter. Which remix was picked lives in the URL and nowhere in the body, so
   * that is what has to be asserted — a picker that filtered the list but kept the first remix
   * selected would produce a byte-identical request to the correct one.
   */
  test("the picker adds the asset to the filtered-to remix, not the first one", async ({ page }) => {
    const captured = await mockRest(page, { moreRemixes: true });
    await openAssetDetail(page);
    await openAddToRemix(page);

    await page.getByTestId("add-to-remix-input").click();
    await page.getByTestId("add-to-remix-input").fill("sunset");
    await expect(page.getByRole("option")).toHaveCount(1);
    await page.getByRole("option", { name: "Sunset timelapse" }).click();
    await page.getByTestId("add-to-remix-submit").click();

    await expect.poll(() => captured.memberAdds.length).toBe(1);
    expect(captured.memberAdds[0]).toEqual({ assetUuids: [ASSET_A] });
    expect(captured.memberAddUrls[0]).toContain(`/remixes/${REMIX_B_UUID}/assets`);
    // Chosen from the list, so no second remix of the same name was created alongside it.
    expect(captured.creates).toHaveLength(0);

    await expect(page.getByTestId("add-to-remix-dialog")).toHaveCount(0);
    await expect(page.getByTestId("asset-remix-chip")).toContainText("Sunset timelapse");
  });

  // --- The opened remix: rename, membership, delete ------------------------

  /**
   * Renaming. The name field is the dialog title, committed on Enter or blur — POST, not PATCH:
   * the server takes updates as POST throughout (`updateRemix` in api/remixes.ts).
   *
   * The card behind the dialog has to relabel too. It is fed by a separate list request, so a
   * rename that updated only the dialog would look right until the dialog closed.
   */
  test("renaming a remix posts the new name and the card relabels", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openRemix(page);

    await expect(page.getByTestId("remix-name-input")).toHaveValue("Team meeting cuts");
    await page.getByTestId("remix-name-input").fill("Coastal, recut");
    await page.getByTestId("remix-name-input").press("Enter");

    await expect.poll(() => captured.updates.length).toBe(1);
    expect(captured.updates[0]).toMatchObject({ name: "Coastal, recut" });

    await expect(page.getByTestId("remix-card")).toContainText("Coastal, recut");
    await expect(page.getByTestId("remix-card")).not.toContainText("Team meeting cuts");
  });

  /**
   * Member order. `ordinal` is written server-side but there is no reordering UI, so the only
   * contract is that the dialog renders the order it was served — with the source first, which is
   * how the list reads as "the original and what came from it".
   */
  test("the members render in the served order, source first", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await openRemix(page);

    const rows = page.getByTestId("remix-members").getByTestId("remix-member");
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText("team-meeting.mp4");
    await expect(rows.nth(0)).toContainText("Original");
    await expect(rows.nth(1)).toContainText("team-meeting-cut.mp4");
  });

  /**
   * The count and the rows come from two different responses — `remix.memberCount` and the member
   * list — and the dialog reloads both after every write. They agree until one of those writes
   * half-lands, which is exactly the failure this asserts against: a count of 2 over a single row
   * is a member that was dropped without the group noticing.
   */
  test("the member count agrees with the rows, before and after a removal", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await openRemix(page);

    const rows = page.getByTestId("remix-member");
    await expect(page.getByTestId("remix-member-count")).toHaveText("2 assets");
    await expect(rows).toHaveCount(2);

    await removeMember(page, "team-meeting-cut.mp4");

    await expect(rows).toHaveCount(1);
    await expect(page.getByTestId("remix-member-count")).toHaveText("1 asset");
    // And the band's card, fed by its own request, moved with it.
    await expect(page.getByTestId("remix-card")).toContainText("1 asset");
  });

  /**
   * Deleting the group, not its assets. The remix card leaves the band; the assets that were in it
   * stay in the catalogue — a delete that took the members with it would lose files.
   */
  test("deleting a remix removes its card and leaves the member assets in the grid", async ({ page }) => {
    const captured = await mockRest(page);
    await login(page);
    await openRemix(page);

    await page.getByTestId("remix-delete").click();

    await expect.poll(() => captured.remixDeletes.length).toBe(1);
    expect(captured.remixDeletes[0]).toContain(`/remixes/${REMIX_UUID}`);
    expect(captured.memberDeletes).toHaveLength(0);

    await expect(page.getByTestId("remix-dialog")).toBeHidden();
    await expect(page).not.toHaveURL(/remix=/);
    await expect(page.getByTestId("remix-card")).toHaveCount(0);

    await expect(page.getByText("team-meeting.mp4").first()).toBeVisible();
    await expect(page.getByText("team-meeting-cut.mp4").first()).toBeVisible();
  });

  /**
   * The empty state. An emptied remix is not deleted — the group survives its last member, so the
   * dialog has to say so rather than render an empty grid that reads as a failed load.
   */
  test("a remix whose last member was removed says it is empty", async ({ page }) => {
    await mockRest(page);
    await login(page);
    await openRemix(page);

    await removeMember(page, "team-meeting-cut.mp4");
    await expect(page.getByTestId("remix-member")).toHaveCount(1);
    await removeMember(page, "team-meeting.mp4");

    await expect(page.getByTestId("remix-member")).toHaveCount(0);
    await expect(page.getByTestId("remix-empty")).toBeVisible();
    await expect(page.getByTestId("remix-member-count")).toHaveText("0 assets");
    // Emptied, not gone: the dialog is still open on a remix that still exists.
    await expect(page.getByTestId("remix-dialog")).toBeVisible();
    await expect(page.getByTestId("remix-name-input")).toHaveValue("Team meeting cuts");
  });
});
