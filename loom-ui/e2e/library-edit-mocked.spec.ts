import { test, expect, Page } from "@playwright/test";

/**
 * Mocked request-mapping test for editing a library's description.
 *
 * No running Loom backend is required: all REST calls are intercepted with
 * `page.route`. The test drives the real edit dialog and asserts that the
 * outgoing update request carries the description in `meta.description` while
 * preserving unrelated meta keys (the server replaces the whole meta object).
 */

function libraryResponse(meta: Record<string, unknown>, name = "Main") {
  return {
    uuid: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    name,
    meta,
    status: { created: new Date(0).toISOString() },
  };
}

/** Install the baseline REST mocks. Returns a record of captured update requests. */
async function mockRest(page: Page) {
  const captured: { updates: unknown[] } = { updates: [] };

  // Catch-all first (lowest priority): empty lists for anything unmatched.
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  // Library list with an existing description plus an unrelated meta key.
  await page.route("**/api/v1/libraries", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: [libraryResponse({ description: "Old description", color: "red" })] }),
    })
  );

  // Update by uuid: capture the body and echo back the updated library.
  await page.route(/\/api\/v1\/libraries\/[0-9a-f-]+$/, route => {
    const body = route.request().postDataJSON();
    captured.updates.push(body);
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(libraryResponse(body.meta, body.name)),
    });
  });

  return captured;
}

async function loginAndGoToLibrary(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: /library|libraries|bibliothek/i }).first().click();
  await expect(page.getByRole("heading", { name: /libraries|bibliotheken/i })).toBeVisible({ timeout: 10_000 });
}

test.describe("Library edit – mocked request mapping", () => {
  test("edit dialog pre-fills the description and sends it as meta.description", async ({ page }) => {
    const captured = await mockRest(page);
    await loginAndGoToLibrary(page);

    // Existing description is shown in the detail header.
    await expect(page.getByText("Old description")).toBeVisible({ timeout: 10_000 });

    // Open the edit dialog; both fields are pre-filled.
    await page.locator("button").filter({ has: page.locator("svg[data-testid='EditOutlinedIcon']") }).first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByLabel("Name")).toHaveValue("Main");
    await expect(dialog.getByLabel("Description")).toHaveValue("Old description");

    // Change the description and save.
    await dialog.getByLabel("Description").fill("New description");
    await dialog.getByRole("button", { name: /save/i }).click();
    await expect(dialog).toBeHidden({ timeout: 10_000 });

    // The update request carries meta.description and preserves unrelated meta keys.
    expect(captured.updates).toHaveLength(1);
    expect(captured.updates[0]).toEqual({
      name: "Main",
      meta: { description: "New description", color: "red" },
    });

    // The detail header reflects the updated description.
    await expect(page.getByText("New description")).toBeVisible();
    await expect(page.getByText("Old description")).toBeHidden();
  });
});
