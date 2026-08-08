import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Workflow "tagging" mode — no running Loom backend required.
 * All `/api/v1/**` calls are intercepted via `page.route`, with a small in-memory
 * tag store so a tag round-trips against the asset-scoped tag routes and comes
 * back on the asset itself:
 *   GET  /api/v1/tags                        — the vocabulary the autocomplete offers
 *   POST /api/v1/assets/:uuid/tags           — attach (resolve-or-create, returns the tag)
 *   DELETE /api/v1/assets/:uuid/tags/:tagUuid — detach
 *
 * Because auth is held in memory only, a reload logs the user out; re-logging in
 * and finding the chip still present proves it came back on `AssetResponse.tags`
 * from the (mock) server, not from React state.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

interface StoredTag {
  uuid: string;
  name: string;
  collection: string;
  nodeKind?: string;
  confidence?: number;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page, opts: { failTagWrites?: boolean; seed?: StoredTag[] } = {}) {
  // Tags attached to ASSET_A. The asset response is built from this, so a reload re-reads it.
  const attached: StoredTag[] = [...(opts.seed ?? [])];
  let seq = 0;

  const asset = () => ({
    uuid: ASSET_A,
    file: { filename: "workflow-a.jpg", mimeType: "image/jpeg", size: 1024 },
    status: { creator: { uuid: ME_UUID } },
    tags: attached,
  });

  // Catch-all first (lowest priority) — Playwright matches the last-registered route, so every
  // route below this one wins over it.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // The vocabulary. "sunset" is offered but attached to nothing, so it can only come from here.
  await page.route(/\/api\/v1\/tags(\?|$)/, route =>
    json(route, {
      data: [
        { uuid: "vocab-1", name: "sunset", collection: "default" },
        { uuid: "vocab-2", name: "archive", collection: "default" },
      ],
    })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route => json(route, { data: [asset()], _metainfo: { totalCount: 1 } }));

  // Asset-scoped tags: attach.
  await page.route(/\/api\/v1\/assets\/[^/]+\/tags$/, route => {
    if (route.request().method() !== "POST") {
      return json(route, { data: attached });
    }
    if (opts.failTagWrites) {
      return json(route, { message: "nope" }, 500);
    }
    const body = JSON.parse(route.request().postData() || "{}");
    const created: StoredTag = { uuid: `tag-${++seq}`, name: body.name, collection: body.collection };
    attached.push(created);
    return json(route, created, 201);
  });

  // Asset-scoped tags: detach.
  await page.route(/\/api\/v1\/assets\/[^/]+\/tags\/[^/]+$/, route => {
    if (opts.failTagWrites) {
      return json(route, { message: "nope" }, 500);
    }
    const uuid = decodeURIComponent(route.request().url().split("/tags/")[1].split("?")[0]);
    const idx = attached.findIndex(t => t.uuid === uuid);
    if (idx >= 0) attached.splice(idx, 1);
    return route.fulfill({ status: 204, body: "" });
  });

  // Single asset by id (registered after the tag routes so those win).
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openTagging(page: Page) {
  await page.getByRole("button", { name: "Workflow" }).click();
  await expect(page.getByTestId("workflow-rating-value")).toBeVisible({ timeout: 10_000 });
  // The mode switch labels are substrings of one another elsewhere in the app, so match exactly.
  await page.getByRole("button", { name: "Tagging", exact: true }).click();
  await expect(page.getByTestId("workflow-tag-input")).toBeVisible({ timeout: 10_000 });
}

async function typeTag(page: Page, name: string) {
  const input = page.getByTestId("workflow-tag-input");
  await input.click();
  await input.fill(name);
  await page.keyboard.press("Enter");
}

function chip(page: Page, name: string) {
  return page.locator(`[data-testid="workflow-tag-chip"][data-tag-name="${name}"]`);
}

test.describe("Workflow tagging – mocked e2e", () => {
  test("tag an asset, reload, and the tag persists", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openTagging(page);

    const posted = page.waitForRequest(
      req => req.method() === "POST" && /\/assets\/[^/]+\/tags$/.test(req.url())
    );
    await typeTag(page, "hero");

    expect(JSON.parse((await posted).postData() || "{}")).toEqual({ name: "hero", collection: "default" });
    await expect(chip(page, "hero")).toBeVisible({ timeout: 10_000 });

    // Reload: auth is in-memory so this logs us out. Log back in and reopen the workflow — the
    // chip must come back off the asset response, which is the proof it was written.
    await page.reload();
    await login(page);
    await openTagging(page);

    await expect(chip(page, "hero")).toBeVisible({ timeout: 10_000 });
  });

  test("a failed write removes the chip again instead of leaving it up", async ({ page }) => {
    await installMocks(page, { failTagWrites: true });
    await page.goto("/");
    await login(page);
    await openTagging(page);

    await typeTag(page, "hero");

    // The chip appears optimistically and must then disappear: a chip that survives a failed POST
    // tells the reviewer the decision was recorded when the server never heard about it.
    await expect(chip(page, "hero")).toHaveCount(0, { timeout: 10_000 });
    await expect(page.getByText(/could not add the tag/i)).toBeVisible({ timeout: 10_000 });
  });

  test("removing a tag issues the DELETE and it stays gone after a reload", async ({ page }) => {
    await installMocks(page, { seed: [{ uuid: "tag-seed", name: "archive", collection: "default" }] });
    await page.goto("/");
    await login(page);
    await openTagging(page);

    await expect(chip(page, "archive")).toBeVisible({ timeout: 10_000 });

    const deleted = page.waitForRequest(
      req => req.method() === "DELETE" && /\/assets\/[^/]+\/tags\/tag-seed$/.test(req.url())
    );
    // MUI renders the delete affordance as an svg inside the chip.
    await chip(page, "archive").locator("svg").last().click();
    await deleted;

    await page.reload();
    await login(page);
    await openTagging(page);
    await expect(chip(page, "archive")).toHaveCount(0, { timeout: 10_000 });
  });

  test("a machine tag is shown but cannot be removed from here", async ({ page }) => {
    await installMocks(page, {
      seed: [{ uuid: "tag-machine", name: "cat", collection: "default", nodeKind: "tag", confidence: 0.82 }],
    });
    await page.goto("/");
    await login(page);
    await openTagging(page);

    const machine = chip(page, "cat");
    await expect(machine).toBeVisible({ timeout: 10_000 });
    await expect(machine).toHaveAttribute("data-machine-tag", "true");
    // No delete affordance: undoing what a pipeline decided is an explicit act, and it belongs on
    // the asset detail screen rather than one keystroke away from a review flow.
    await expect(machine.locator("svg")).toHaveCount(0);
  });
});
