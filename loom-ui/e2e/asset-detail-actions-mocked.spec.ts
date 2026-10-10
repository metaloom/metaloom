import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Asset Detail header actions: rename (Save), Process with a pipeline, and
 * Delete. Each of these was reachable only from the backend specs, and none of their failure
 * paths was exercised anywhere.
 *
 * Routes exercised:
 *   POST   /api/v1/assets/:uuid               (rename — body must carry file.filename)
 *   DELETE /api/v1/assets/:uuid
 *   GET    /api/v1/pipelines
 *   POST   /api/v1/pipelines/:uuid/run        (body must carry mediaUuids: [asset])
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const PIPELINE_UUID = "33333333-3333-3333-3333-333333333333";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface Options {
  updateStatus?: number;
  deleteStatus?: number;
  /** What POST /pipelines/:uuid/run answers: a status code, or a 200 with this body. */
  run?: { status: number } | { body: { runUuid: string; dispatched: boolean; message?: string } };
  noPipelines?: boolean;
}

interface Recorder {
  updates: Record<string, unknown>[];
  deletes: string[];
  runs: { url: string; body: Record<string, unknown> }[];
}

async function installMocks(page: Page, options: Options = {}): Promise<Recorder> {
  const rec: Recorder = { updates: [], deletes: [], runs: [] };
  let filename = "holiday.pdf";

  const asset = () => ({
    uuid: ASSET_UUID,
    // A document: no media pane to lay out, so nothing here depends on the player or the image.
    file: { filename, mimeType: "application/pdf", size: 4096 },
    meta: { reviewed: true },
    tags: [],
    status: { creator: { uuid: ME_UUID }, created: new Date(0).toISOString() },
  });

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/pipelines$/, route => json(route, {
    data: options.noPipelines ? [] : [{
      uuid: PIPELINE_UUID, versionUuid: "v-1", versionNumber: 1, name: "Thumbnailer",
      enabled: true, priority: 0, dryRun: false,
    }],
  }));
  await page.route(/\/api\/v1\/pipelines\/[^/]+\/run$/, route => {
    rec.runs.push({ url: route.request().url(), body: route.request().postDataJSON() });
    const run = options.run ?? { body: { runUuid: "run-1", dispatched: true } };
    if ("status" in run) return json(route, { message: "boom" }, run.status);
    return json(route, run.body);
  });

  await page.route(new RegExp(`/api/v1/assets/${ASSET_UUID}$`), route => {
    const method = route.request().method();
    if (method === "DELETE") {
      rec.deletes.push(ASSET_UUID);
      if (options.deleteStatus) return json(route, { message: "nope" }, options.deleteStatus);
      return route.fulfill({ status: 204, body: "" });
    }
    if (method === "POST") {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      rec.updates.push(body);
      if (options.updateStatus) return json(route, { message: "nope" }, options.updateStatus);
      // Mirror the server: only file.filename renames.
      const file = body.file as { filename?: string } | undefined;
      if (file?.filename) filename = file.filename;
      return json(route, asset());
    }
    return json(route, asset());
  });

  return rec;
}

/** Deep-link first, then sign in: auth is in-memory, so a goto after signing in would drop it. */
async function openAssetDetail(page: Page) {
  await page.goto(`/ui/assets/${ASSET_UUID}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("asset-name")).toHaveValue("holiday.pdf", { timeout: 10_000 });
}

async function openProcessMenu(page: Page) {
  await page.getByTestId("asset-actions-menu-button").click();
  await page.getByRole("menuitem", { name: /^Process/ }).click();
}

test.describe("Asset detail – header actions (mocked)", () => {
  test("rename sends the new name as file.filename, without clobbering meta, and confirms", async ({ page }) => {
    const rec = await installMocks(page);
    await openAssetDetail(page);

    const save = page.getByRole("button", { name: "Save", exact: true });
    // Nothing to save until the name actually changes.
    await expect(save).toBeDisabled();

    await page.getByTestId("asset-name").fill("holiday-final.pdf");
    await expect(save).toBeEnabled();
    await save.click();

    await expect.poll(() => rec.updates.length, { timeout: 5_000 }).toBe(1);
    // `file.filename` is the only place the server reads a rename from. The view used to send a
    // top-level `filename` (dropped server side) plus `meta: {}` (which replaced the asset's real
    // meta with nothing), so a rename silently reverted and wiped the meta on the way.
    expect(rec.updates[0]).toEqual({ file: { filename: "holiday-final.pdf" } });

    await expect(page.getByText("Asset saved", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("asset-name")).toHaveValue("holiday-final.pdf");
    // Saved, so clean again.
    await expect(save).toBeDisabled();
  });

  test("a refused rename is reported and the typed name is kept", async ({ page }) => {
    const rec = await installMocks(page, { updateStatus: 403 });
    await openAssetDetail(page);

    await page.getByTestId("asset-name").fill("forbidden.pdf");
    await page.getByRole("button", { name: "Save", exact: true }).click();

    await expect.poll(() => rec.updates.length, { timeout: 5_000 }).toBe(1);
    await expect(page.getByText("Failed to save asset", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("asset-name")).toHaveValue("forbidden.pdf");
    // Still dirty, so the reviewer can try again.
    await expect(page.getByRole("button", { name: "Save", exact: true })).toBeEnabled();
  });

  test("Process runs the chosen pipeline on this asset and says it started", async ({ page }) => {
    const rec = await installMocks(page);
    await openAssetDetail(page);

    await openProcessMenu(page);
    await page.getByRole("menuitem", { name: "Thumbnailer", exact: true }).click();

    await expect.poll(() => rec.runs.length, { timeout: 5_000 }).toBe(1);
    expect(new URL(rec.runs[0].url).pathname).toBe(`/api/v1/pipelines/${PIPELINE_UUID}/run`);
    expect(rec.runs[0].body).toEqual({ mediaUuids: [ASSET_UUID] });
    await expect(page.getByText("Processing started", { exact: true })).toBeVisible({ timeout: 5_000 });
    // Both menus close once a pipeline is picked.
    await expect(page.getByRole("menu")).toHaveCount(0);
  });

  test("a run the server did not dispatch shows the server's reason as an error", async ({ page }) => {
    const rec = await installMocks(page, {
      run: { body: { runUuid: "run-1", dispatched: false, message: "No processor is connected" } },
    });
    await openAssetDetail(page);

    await openProcessMenu(page);
    await page.getByRole("menuitem", { name: "Thumbnailer", exact: true }).click();

    await expect.poll(() => rec.runs.length, { timeout: 5_000 }).toBe(1);
    await expect(page.getByText("No processor is connected", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("Processing started", { exact: true })).toHaveCount(0);
  });

  test("a failed run request is reported", async ({ page }) => {
    const rec = await installMocks(page, { run: { status: 500 } });
    await openAssetDetail(page);

    await openProcessMenu(page);
    await page.getByRole("menuitem", { name: "Thumbnailer", exact: true }).click();

    await expect.poll(() => rec.runs.length, { timeout: 5_000 }).toBe(1);
    await expect(page.getByText("Failed to start processing", { exact: true })).toBeVisible({ timeout: 5_000 });
  });

  test("with no pipelines the Process submenu says so and offers nothing to click", async ({ page }) => {
    const rec = await installMocks(page, { noPipelines: true });
    await openAssetDetail(page);

    await openProcessMenu(page);
    const empty = page.getByRole("menuitem", { name: "No pipelines available", exact: true });
    await expect(empty).toBeVisible();
    await expect(empty).toHaveAttribute("aria-disabled", "true");
    expect(rec.runs).toHaveLength(0);
  });

  test("delete asks first; cancelling sends nothing", async ({ page }) => {
    const rec = await installMocks(page);
    await openAssetDetail(page);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByRole("menuitem", { name: "Delete", exact: true }).click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("Delete holiday.pdf? This cannot be undone.");
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(dialog).toBeHidden();

    await page.waitForLoadState("networkidle");
    expect(rec.deletes).toHaveLength(0);
    await expect(page).toHaveURL(new RegExp(`/assets/${ASSET_UUID}$`));
  });

  test("confirming delete issues DELETE, says so and returns to the asset list", async ({ page }) => {
    const rec = await installMocks(page);
    await openAssetDetail(page);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByRole("menuitem", { name: "Delete", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();

    await expect.poll(() => rec.deletes.length, { timeout: 5_000 }).toBe(1);
    await expect(page).toHaveURL(/\/ui\/assets$/, { timeout: 5_000 });
    await expect(page.getByText("Asset deleted", { exact: true })).toBeVisible({ timeout: 5_000 });
  });

  test("a refused delete is reported and the asset stays open", async ({ page }) => {
    const rec = await installMocks(page, { deleteStatus: 409 });
    await openAssetDetail(page);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByRole("menuitem", { name: "Delete", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();

    await expect.poll(() => rec.deletes.length, { timeout: 5_000 }).toBe(1);
    await expect(page.getByText("Failed to delete asset", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page).toHaveURL(new RegExp(`/assets/${ASSET_UUID}$`));
    await expect(page.getByTestId("asset-name")).toHaveValue("holiday.pdf");
  });
});
