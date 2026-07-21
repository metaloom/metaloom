import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Workflow "rating" mode — no running Loom backend required.
 * All `/api/v1/**` calls are intercepted via `page.route`, with a small in-memory
 * reaction store so a star-rating round-trips against the asset-scoped reaction
 * routes and survives a reload:
 *   GET/POST /api/v1/assets/:uuid/reactions
 *
 * Because auth is held in memory only, a reload logs the user out; re-logging in
 * and finding the rating still present proves it was persisted server-side (the
 * mock store) and re-hydrated, not just kept in React state.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const ASSET_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

interface StoredReaction {
  uuid: string;
  assetUuid: string;
  type?: string;
  rating?: number;
  status: { creator: { uuid: string }; created: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset(uuid: string, filename: string) {
  return {
    uuid,
    file: { filename, mimeType: "image/jpeg", size: 1024 },
    status: { creator: { uuid: ME_UUID } },
  };
}

async function installMocks(page: Page) {
  const reactions: StoredReaction[] = [];
  let seq = 0;

  // Catch-all first (lowest priority) — empty collections for the many list
  // endpoints the workflow view fans out to (detections, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets$/, route =>
    json(route, { data: [asset(ASSET_A, "workflow-a.jpg"), asset(ASSET_B, "workflow-b.jpg")], _metainfo: { totalCount: 2 } })
  );

  // Asset-scoped reactions: list (filtered by asset) + create.
  await page.route(/\/api\/v1\/assets\/[^/]+\/reactions$/, route => {
    const assetUuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("/reactions")[0]);
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredReaction = {
        uuid: `reaction-${++seq}`,
        assetUuid,
        type: body.type,
        rating: body.rating,
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      reactions.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: reactions.filter(r => r.assetUuid === assetUuid) });
  });

  // Asset reaction by uuid: update (POST) + delete.
  await page.route(/\/api\/v1\/assets\/[^/]+\/reactions\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/reactions/")[1].split("?")[0]);
    const existing = reactions.find(r => r.uuid === uuid);
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (existing) {
        existing.type = body.type;
        existing.rating = body.rating;
      }
      return json(route, existing ?? {}, 200);
    }
    const idx = reactions.findIndex(r => r.uuid === uuid);
    if (idx >= 0) reactions.splice(idx, 1);
    return route.fulfill({ status: 204, body: "" });
  });

  // Single asset by id (registered after the reaction routes so those win).
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("?")[0]);
    return json(route, asset(uuid, "workflow-a.jpg"));
  });
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openWorkflow(page: Page) {
  await page.getByRole("button", { name: "Workflow" }).click();
  await expect(page.getByTestId("workflow-rating-value")).toBeVisible({ timeout: 10_000 });
}

test.describe("Workflow rating – mocked e2e", () => {
  test("rate an asset, reload, and the rating persists", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    const ratingValue = page.getByTestId("workflow-rating-value");
    await expect(ratingValue).toHaveText("—");

    // Rate the current asset via the keyboard binding (5 stars) → POSTs a reaction.
    await page.keyboard.press("5");
    await expect(ratingValue).toHaveText("5", { timeout: 10_000 });

    // Reload: auth is in-memory so this logs us out. Log back in and reopen the
    // workflow — the rating must be re-hydrated from the persisted reaction.
    await page.reload();
    await login(page);
    await openWorkflow(page);

    await expect(page.getByTestId("workflow-rating-value")).toHaveText("5", { timeout: 10_000 });
  });
});
