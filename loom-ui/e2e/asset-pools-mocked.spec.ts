import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Asset Pools view — create, edit, delete and what a card says about capacity.
 *
 * Before this file the view was only reached by the generic paging / sort / search sweeps, which
 * assert that rows appear and never open a dialog or read a card. No running backend is needed:
 * every `/api/v1/**` call is intercepted, with a small in-memory pool store.
 */

const GB = 1e9;

interface StoredPool {
  uuid: string;
  name: string;
  fsPath?: string;
  s3Bucket?: string;
  s3Region?: string;
  s3Endpoint?: string;
  freeSpace?: number;
  usedSpace?: number;
  status: { created: string; edited: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function seed(): StoredPool[] {
  const stamp = new Date(0).toISOString();
  return [{
    uuid: "pool-1", name: "Archive disk", fsPath: "/mnt/archive",
    freeSpace: 100 * GB, usedSpace: 50 * GB, status: { created: stamp, edited: stamp },
  }];
}

async function installMocks(page: Page, options: { deleteStatus?: number; pools?: StoredPool[] } = {}) {
  const pools = options.pools ?? seed();
  const captured = { creates: [] as unknown[], updates: [] as unknown[], deletes: [] as string[] };

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));

  await page.route(/\/api\/v1\/pools(\?.*)?$/, route => {
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      captured.creates.push(body);
      const stamp = new Date().toISOString();
      const created: StoredPool = { uuid: `pool-${pools.length + 1}`, ...body, status: { created: stamp, edited: stamp } };
      pools.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: pools, _metainfo: { totalCount: pools.length } });
  });

  await page.route(/\/api\/v1\/pools\/[^/?]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/pools/")[1]);
    const method = route.request().method();
    const idx = pools.findIndex(p => p.uuid === uuid);
    if (method === "DELETE") {
      captured.deletes.push(uuid);
      if (options.deleteStatus) return json(route, { message: "in use" }, options.deleteStatus);
      if (idx >= 0) pools.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    if (method === "POST") {
      const body = route.request().postDataJSON();
      captured.updates.push(body);
      // Like the server: a field left out (or null) keeps its stored value; any string replaces it.
      const set = Object.fromEntries(Object.entries(body).filter(([, v]) => v !== null && v !== undefined));
      pools[idx] = { ...pools[idx], ...set };
      return json(route, pools[idx]);
    }
    return json(route, pools[idx]);
  });

  return captured;
}

function s3Pool(): StoredPool {
  const stamp = new Date(0).toISOString();
  return {
    uuid: "pool-s3", name: "Cloud bucket", s3Bucket: "media-bucket", s3Region: "eu-central-1",
    s3Endpoint: "https://s3.eu-central-1.amazonaws.com", status: { created: stamp, edited: stamp },
  };
}

async function openPools(page: Page) {
  await page.goto("/ui/asset-pools");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("pool-card")).toHaveCount(1, { timeout: 10_000 });
}

test.describe("Asset pools – mocked e2e", () => {
  test("the capacity ring reads used / (free + used), and no unmeasured counts are printed", async ({ page }) => {
    await installMocks(page);
    await openPools(page);

    const card = page.getByTestId("pool-card").first();
    await expect(card).toContainText("Archive disk");
    await expect(card).toContainText("/mnt/archive");
    // 50 GB used of 150 GB. Before the fix the ring divided by the free figure alone and read 50%.
    await expect(card.getByTestId("pool-capacity")).toHaveAttribute("data-used-pct", "33");
    await expect(card).toContainText("100.0 GB free");
    await expect(card).toContainText("50.0 GB used");
    // `/pools` reports no asset count; the card used to print "0 assets" and "0 KB" regardless.
    await expect(card).not.toContainText("0 assets");
    await expect(card).not.toContainText("0 KB");
    await expect(card.getByText("Filesystem", { exact: true })).toBeVisible();
  });

  test("create sends the name and filesystem path and shows the new pool", async ({ page }) => {
    const captured = await installMocks(page);
    await openPools(page);

    await page.getByRole("button", { name: "New Pool", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill("Scratch");
    await dialog.getByLabel("Filesystem Path", { exact: true }).fill("/mnt/scratch");
    await dialog.getByRole("button", { name: "Create", exact: true }).click();

    await expect(dialog).toBeHidden({ timeout: 5_000 });
    expect(captured.creates).toEqual([{ name: "Scratch", fsPath: "/mnt/scratch" }]);
    await expect(page.getByTestId("pool-card")).toHaveCount(2);
    await expect(page.getByText("Asset pool created", { exact: true })).toBeVisible();
  });

  test("edit pre-fills the form and posts the change", async ({ page }) => {
    const captured = await installMocks(page);
    await openPools(page);

    await page.getByTestId("pool-edit").first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByLabel("Name", { exact: true })).toHaveValue("Archive disk");
    await expect(dialog.getByLabel("Filesystem Path", { exact: true })).toHaveValue("/mnt/archive");
    await dialog.getByLabel("Name", { exact: true }).fill("Archive disk 2");
    await dialog.getByRole("button", { name: "Save", exact: true }).click();

    await expect(dialog).toBeHidden({ timeout: 5_000 });
    expect(captured.updates).toHaveLength(1);
    expect(captured.updates[0]).toMatchObject({ name: "Archive disk 2", fsPath: "/mnt/archive" });
    await expect(page.getByTestId("pool-card").first()).toContainText("Archive disk 2");
  });

  test("switching an S3 pool to filesystem clears the bucket, so it really becomes a filesystem pool", async ({ page }) => {
    const captured = await installMocks(page, { pools: [s3Pool()] });
    await openPools(page);
    const card = page.getByTestId("pool-card").first();
    await expect(card).toContainText("media-bucket");

    await page.getByTestId("pool-edit").first().click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("combobox", { name: "Type" }).click();
    await page.getByRole("option", { name: "Filesystem", exact: true }).click();
    await dialog.getByLabel("Filesystem Path", { exact: true }).fill("/mnt/migrated");
    await dialog.getByRole("button", { name: "Save", exact: true }).click();

    await expect(dialog).toBeHidden({ timeout: 5_000 });
    // Leaving the S3 fields out would have kept them server-side, and the pool would still be S3.
    expect(captured.updates[0]).toMatchObject({ fsPath: "/mnt/migrated", s3Bucket: "", s3Region: "", s3Endpoint: "" });
    await expect(card.getByText("Filesystem", { exact: true })).toBeVisible();
    await expect(card).toContainText("/mnt/migrated");
    await expect(card).not.toContainText("media-bucket");
  });

  test("switching a filesystem pool to S3 sends the bucket and clears the path", async ({ page }) => {
    const captured = await installMocks(page);
    await openPools(page);

    await page.getByTestId("pool-edit").first().click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("combobox", { name: "Type" }).click();
    await page.getByRole("option", { name: "S3 Bucket", exact: true }).click();
    await dialog.getByRole("textbox", { name: "S3 Bucket", exact: true }).fill("new-bucket");
    await dialog.getByLabel("S3 Region", { exact: true }).fill("us-east-1");
    await dialog.getByRole("button", { name: "Save", exact: true }).click();

    await expect(dialog).toBeHidden({ timeout: 5_000 });
    expect(captured.updates[0]).toMatchObject({ fsPath: "", s3Bucket: "new-bucket", s3Region: "us-east-1" });
    await expect(page.getByTestId("pool-card").first()).toContainText("new-bucket");
    await expect(page.getByTestId("pool-card").first().getByText("Filesystem", { exact: true })).toHaveCount(0);
  });

  test("delete removes the card", async ({ page }) => {
    const captured = await installMocks(page);
    await openPools(page);

    await page.getByTestId("pool-delete").first().click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();

    await expect(page.getByTestId("pool-card")).toHaveCount(0, { timeout: 5_000 });
    expect(captured.deletes).toEqual(["pool-1"]);
    await expect(page.getByTestId("asset-pools-empty-state")).toBeVisible();
  });

  test("a refused delete keeps the pool and says so", async ({ page }) => {
    await installMocks(page, { deleteStatus: 409 });
    await openPools(page);

    await page.getByTestId("pool-delete").first().click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();

    await expect(page.getByText("Failed to delete pool", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pool-card")).toHaveCount(1);
  });

  test("the card actions have accessible names", async ({ page }) => {
    await installMocks(page);
    await openPools(page);

    await expect(page.getByRole("button", { name: "Edit pool", exact: true })).toHaveCount(1);
    await expect(page.getByRole("button", { name: "Delete pool", exact: true })).toHaveCount(1);
  });
});
