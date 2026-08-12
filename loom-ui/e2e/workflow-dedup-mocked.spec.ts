import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Workflow "deduplication" mode — no running Loom backend required.
 *
 * This screen used to be a mock that paired adjacent assets and kept decisions in React state, so
 * the assertions that matter here are all about the write path: pressing a key must actually PATCH
 * the right group, and a PATCH that fails must take the chip back rather than leave a row looking
 * decided when the server knows nothing about it.
 *
 * Route-matching gotcha: the list client appends `?status=` and `?limit=`, so the collection
 * matcher has to be `(\?|$)` — a `$`-anchored regex falls through to the catch-all.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const GROUP_UUID = "99999999-9999-9999-9999-999999999999";
const KEEP_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const DUP_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

interface PatchCall {
  uuid: string;
  body: { status?: string; keepAssetUuid?: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset(uuid: string, filename: string, size: number) {
  return {
    uuid,
    file: { filename, mimeType: "video/mp4", size },
    status: { creator: { uuid: ME_UUID } },
  };
}

function pendingGroup() {
  return {
    uuid: GROUP_UUID,
    algorithm: "metaloom-multisector-v1",
    status: "PENDING",
    keepAssetUuid: KEEP_UUID,
    score: 0.93,
    members: [
      { assetUuid: KEEP_UUID, role: "KEEP", score: 1.0, size: 52_000_000, zeroChunkCount: 0 },
      { assetUuid: DUP_UUID, role: "DUP", score: 0.93, size: 18_000_000, zeroChunkCount: 0 },
    ],
  };
}

/**
 * @param groups the review queue to serve
 * @param patchStatus status code the PATCH route answers with (500 exercises the rollback path)
 */
async function installMocks(page: Page, groups: unknown[] = [pendingGroup()], patchStatus = 200) {
  const patches: PatchCall[] = [];

  // Catch-all first (lowest priority) — empty collections for everything the workflow view fans out to.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/assets(\?|$)/, route => json(route, { data: [], _metainfo: { totalCount: 0 } }));

  await page.route(/\/api\/v1\/dedup-groups(\?|$)/, route =>
    json(route, { data: groups, _metainfo: { totalCount: groups.length, perPage: 25 } })
  );

  await page.route(/\/api\/v1\/dedup-groups\/[^/?]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/dedup-groups/")[1].split("?")[0]);
    if (route.request().method() !== "PATCH") {
      return json(route, pendingGroup());
    }
    const body = JSON.parse(route.request().postData() || "{}");
    patches.push({ uuid, body });
    if (patchStatus !== 200) {
      return route.fulfill({ status: patchStatus, contentType: "application/json", body: JSON.stringify({ message: "nope" }) });
    }
    return json(route, { ...pendingGroup(), status: body.status, keepAssetUuid: body.keepAssetUuid ?? KEEP_UUID });
  });

  // The member assets, resolved one by one for their filenames and sizes.
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("?")[0]);
    return json(route, uuid === KEEP_UUID
      ? asset(KEEP_UUID, "drone-coastal.mp4", 52_000_000)
      : asset(DUP_UUID, "drone-coastal-720p.mp4", 18_000_000));
  });

  return patches;
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openDedupMode(page: Page) {
  await page.getByRole("button", { name: "Workflow" }).click();
  await page.getByTestId("workflow-mode-deduplication").click();
}

test.describe("Workflow deduplication – mocked e2e", () => {
  test("renders the real pending queue with size and completeness", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);

    const group = page.getByTestId("dedup-group");
    await expect(group).toBeVisible({ timeout: 10_000 });
    await expect(group).toHaveAttribute("data-group-uuid", GROUP_UUID);

    // The keep is the larger file; both members show their discovery-time size.
    await expect(page.getByTestId("dedup-keep")).toContainText("drone-coastal.mp4");
    await expect(page.getByTestId("dedup-keep")).toContainText("50 MB");
    await expect(page.getByTestId(`dedup-member-${DUP_UUID}`)).toContainText("drone-coastal-720p.mp4");
    await expect(page.getByTestId(`dedup-member-${DUP_UUID}`)).toContainText("17 MB");
  });

  test("Y confirms the group and PATCHes CONFIRMED", async ({ page }) => {
    const patches = await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);
    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });

    await page.keyboard.press("y");

    await expect(page.getByTestId("dedup-decision-chip")).toBeVisible({ timeout: 10_000 });
    expect(patches).toHaveLength(1);
    expect(patches[0].uuid).toBe(GROUP_UUID);
    expect(patches[0].body.status).toBe("CONFIRMED");
  });

  test("N rejects the group and PATCHes REJECTED", async ({ page }) => {
    const patches = await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);
    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });

    await page.keyboard.press("n");

    await expect(page.getByTestId("dedup-decision-chip")).toBeVisible({ timeout: 10_000 });
    expect(patches).toHaveLength(1);
    expect(patches[0].body.status).toBe("REJECTED");
  });

  // The two buttons below duplicate what Y/N do. They are covered separately because the keyboard
  // handler stands down while a text field has focus, so on a real review pass the buttons are the
  // only way through — and until now no test had ever clicked either of them.

  test("the Confirm button PATCHes CONFIRMED", async ({ page }) => {
    const patches = await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);
    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("dedup-confirm").click();

    await expect(page.getByTestId("dedup-decision-chip")).toBeVisible({ timeout: 10_000 });
    expect(patches).toHaveLength(1);
    expect(patches[0].uuid).toBe(GROUP_UUID);
    expect(patches[0].body.status).toBe("CONFIRMED");
  });

  test("the Reject button PATCHes REJECTED", async ({ page }) => {
    const patches = await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);
    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("dedup-reject").click();

    await expect(page.getByTestId("dedup-decision-chip")).toBeVisible({ timeout: 10_000 });
    expect(patches).toHaveLength(1);
    expect(patches[0].body.status).toBe("REJECTED");
  });

  test("the group score is the algorithm's, rendered to two decimals", async ({ page }) => {
    // The score is what a reviewer weighs the pair on, so it must be the server's number and not a
    // member score: the KEEP member scores 1.0 here and the group scores 0.93.
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);

    await expect(page.getByTestId("dedup-group-score")).toHaveText("Score: 0.93", { timeout: 10_000 });
  });

  test("a group without a score omits the label rather than printing NaN", async ({ page }) => {
    const { score: _score, ...scoreless } = pendingGroup();
    await installMocks(page, [scoreless]);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);

    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("dedup-group-score")).toHaveCount(0);
  });

  test("a failed PATCH reverts the chip instead of leaving a decided-looking row", async ({ page }) => {
    const patches = await installMocks(page, [pendingGroup()], 500);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);
    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });

    await page.keyboard.press("y");

    // The request went out...
    await expect.poll(() => patches.length).toBe(1);
    // ...but nothing was written, so the row must go back to undecided and say so.
    await expect(page.getByText(/could not save your decision/i)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("dedup-decision-chip")).toHaveCount(0);
  });

  test("choosing a different keep PATCHes keepAssetUuid without deciding the group", async ({ page }) => {
    const patches = await installMocks(page);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);
    await expect(page.getByTestId("dedup-group")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId(`dedup-make-keep-${DUP_UUID}`).click();

    await expect.poll(() => patches.length).toBe(1);
    expect(patches[0].body.keepAssetUuid).toBe(DUP_UUID);
    expect(patches[0].body.status).toBe("PENDING");
    // Reassigning is not a decision - no chip.
    await expect(page.getByTestId("dedup-decision-chip")).toHaveCount(0);
  });

  test("an empty queue says so instead of rendering a blank pane", async ({ page }) => {
    await installMocks(page, []);
    await page.goto("/");
    await login(page);
    await openDedupMode(page);

    await expect(page.getByTestId("dedup-empty")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("dedup-group")).toHaveCount(0);
  });
});
