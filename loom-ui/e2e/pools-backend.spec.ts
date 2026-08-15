import { test, expect, Page } from "@playwright/test";

async function loginAndGoToAssetPools(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: /asset.?pool|pools/i }).first().click();
  await expect(page.getByRole("heading", { name: /asset pools/i })).toBeVisible({ timeout: 10_000 });
}

test.describe("Asset Pools - backend e2e", () => {
  test("list pools loaded from backend", async ({ page }) => {
    await loginAndGoToAssetPools(page);

    // Should show at least the demo pools from the backend
    const cards = page.getByTestId("pool-card").filter({ has: page.locator("text=Filesystem") }).or(
      page.getByTestId("pool-card").filter({ has: page.locator("text=S3 Bucket") })
    );
    await expect(cards.first()).toBeVisible({ timeout: 10_000 });
  });

  test("create, update and delete a filesystem pool", async ({ page }) => {
    await loginAndGoToAssetPools(page);

    const name = `pw-pool-${Date.now()}`;
    const updatedName = `${name}-updated`;

    // Create
    await page.locator("button, .MuiChip-root").filter({ has: page.locator("svg[data-testid='AddOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(name);
    // Type should default to Filesystem
    await page.getByLabel("Filesystem Path").fill("/tmp/pw-test");
    await page.getByRole("button", { name: /^create$/i }).click();

    // Verify the pool card appears
    const createdCard = page.getByTestId("pool-card").filter({ hasText: name });
    await expect(createdCard).toBeVisible({ timeout: 10_000 });

    // Verify it shows as Filesystem type
    await expect(createdCard.locator("text=Filesystem")).toBeVisible();

    // Edit
    await createdCard.hover();
    await createdCard.locator("button").filter({ has: page.locator("svg[data-testid='EditOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(updatedName);
    await page.getByRole("button", { name: /save/i }).click();

    const updatedCard = page.getByTestId("pool-card").filter({ hasText: updatedName });
    await expect(updatedCard).toBeVisible({ timeout: 10_000 });

    // Delete
    await updatedCard.hover();
    await updatedCard.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
    await page.getByRole("button", { name: /^delete$/i }).click();

    await expect(updatedCard).toBeHidden({ timeout: 10_000 });
  });

  test("create and delete an S3 pool", async ({ page }) => {
    await loginAndGoToAssetPools(page);

    const name = `pw-s3-pool-${Date.now()}`;

    // Create S3 pool
    await page.locator("button, .MuiChip-root").filter({ has: page.locator("svg[data-testid='AddOutlinedIcon']") }).first().click();
    await page.getByLabel("Name").fill(name);

    // Switch type to S3
    await page.getByLabel("Type").click();
    await page.getByRole("option", { name: /S3/i }).click();

    // By role, not by label: "S3 Bucket" is also the selected value of the Type combobox, so a plain
    // getByLabel matches the combobox as well as this field.
    await page.getByRole("textbox", { name: "S3 Bucket" }).fill("pw-test-bucket");
    await page.getByLabel("S3 Region").fill("eu-central-1");
    await page.getByLabel("S3 Endpoint").fill("https://s3.eu-central-1.amazonaws.com");
    await page.getByRole("button", { name: /^create$/i }).click();

    const createdCard = page.getByTestId("pool-card").filter({ hasText: name });
    await expect(createdCard).toBeVisible({ timeout: 10_000 });
    await expect(createdCard.locator("text=S3 Bucket")).toBeVisible();

    // Cleanup - delete
    await createdCard.hover();
    await createdCard.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
    await page.getByRole("button", { name: /^delete$/i }).click();
    await expect(createdCard).toBeHidden({ timeout: 10_000 });
  });

  test("search filters pools", async ({ page }) => {
    await loginAndGoToAssetPools(page);

    // Create two pools with different names
    const nameA = `pw-search-alpha-${Date.now()}`;
    const nameB = `pw-search-bravo-${Date.now()}`;

    for (const poolName of [nameA, nameB]) {
      await page.locator("button, .MuiChip-root").filter({ has: page.locator("svg[data-testid='AddOutlinedIcon']") }).first().click();
      await page.getByLabel("Name").fill(poolName);
      await page.getByLabel("Filesystem Path").fill(`/tmp/${poolName}`);
      await page.getByRole("button", { name: /^create$/i }).click();
      await expect(page.getByTestId("pool-card").filter({ hasText: poolName })).toBeVisible({ timeout: 10_000 });
    }

    // Search for alpha
    await page.getByPlaceholder(/search pools/i).fill("alpha");
    await expect(page.getByTestId("pool-card").filter({ hasText: nameA })).toBeVisible();
    await expect(page.getByTestId("pool-card").filter({ hasText: nameB })).toBeHidden();

    // Clear search
    await page.getByPlaceholder(/search pools/i).fill("");

    // Cleanup
    for (const poolName of [nameA, nameB]) {
      const card = page.getByTestId("pool-card").filter({ hasText: poolName });
      await card.hover();
      await card.locator("button").filter({ has: page.locator("svg[data-testid='DeleteOutlinedIcon']") }).first().click();
      await page.getByRole("button", { name: /^delete$/i }).click();
      await expect(card).toBeHidden({ timeout: 10_000 });
    }
  });
});
