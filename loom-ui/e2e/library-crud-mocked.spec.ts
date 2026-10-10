import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for creating and deleting libraries, success and refusal.
 *
 * `library-edit-mocked` covers the edit dialog's request body; nothing exercised create or delete,
 * and the refusal paths were where the bugs were: create and update had try/finally with no catch
 * and delete had no try at all, so a refused request was an unhandled rejection and a dialog that
 * silently stayed open.
 */

interface StoredLibrary {
  uuid: string;
  name: string;
  meta?: Record<string, unknown>;
  status: { created: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page, options: { createStatus?: number; deleteStatus?: number; updateStatus?: number } = {}) {
  const libraries: StoredLibrary[] = [
    { uuid: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", name: "Main", meta: {}, status: { created: new Date(0).toISOString() } },
  ];
  const captured = { creates: [] as unknown[], deletes: [] as string[] };

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));

  await page.route(/\/api\/v1\/libraries(\?.*)?$/, route => {
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      captured.creates.push(body);
      if (options.createStatus) return json(route, { message: "name taken" }, options.createStatus);
      const created: StoredLibrary = {
        uuid: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", name: body.name, meta: body.meta ?? {},
        status: { created: new Date().toISOString() },
      };
      libraries.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: libraries });
  });

  await page.route(/\/api\/v1\/libraries\/[0-9a-f-]+$/, route => {
    const uuid = route.request().url().split("/libraries/")[1];
    const method = route.request().method();
    if (method === "DELETE") {
      captured.deletes.push(uuid);
      if (options.deleteStatus) return json(route, { message: "not empty" }, options.deleteStatus);
      const idx = libraries.findIndex(l => l.uuid === uuid);
      if (idx >= 0) libraries.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    if (method === "POST" && options.updateStatus) return json(route, { message: "nope" }, options.updateStatus);
    return json(route, libraries.find(l => l.uuid === uuid));
  });

  return captured;
}

async function openLibrary(page: Page) {
  await page.goto("/ui/library");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByRole("heading", { name: "Main", exact: true })).toBeVisible({ timeout: 10_000 });
}

test.describe("Library create / delete – mocked e2e", () => {
  test("create posts name and description and selects the new library", async ({ page }) => {
    const captured = await installMocks(page);
    await openLibrary(page);

    await page.getByRole("button", { name: "New library", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill("Interviews");
    await dialog.getByLabel("Description", { exact: true }).fill("Raw footage");
    await dialog.getByRole("button", { name: "Create", exact: true }).click();

    await expect(dialog).toBeHidden({ timeout: 5_000 });
    expect(captured.creates).toEqual([{ name: "Interviews", meta: { description: "Raw footage" } }]);
    await expect(page.getByRole("heading", { name: "Interviews", exact: true })).toBeVisible();
  });

  test("a refused create is reported and the dialog keeps what was typed", async ({ page }) => {
    await installMocks(page, { createStatus: 409 });
    await openLibrary(page);

    await page.getByRole("button", { name: "New library", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name", { exact: true }).fill("Interviews");
    await dialog.getByRole("button", { name: "Create", exact: true }).click();

    await expect(page.getByText("Failed to create library", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Name", { exact: true })).toHaveValue("Interviews");
    await expect(dialog.getByRole("button", { name: "Create", exact: true })).toBeEnabled();
  });

  test("a refused update is reported and the edit dialog stays open", async ({ page }) => {
    await installMocks(page, { updateStatus: 403 });
    await openLibrary(page);

    await page.getByRole("button", { name: "Edit library", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("heading", { name: "Edit Library", exact: true })).toBeVisible();
    await dialog.getByLabel("Name", { exact: true }).fill("Renamed");
    await dialog.getByRole("button", { name: "Save", exact: true }).click();

    await expect(page.getByText("Failed to update library", { exact: true })).toBeVisible({ timeout: 5_000 });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Name", { exact: true })).toHaveValue("Renamed");
    // The page behind the modal is aria-hidden; close it to read the header.
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Main", exact: true })).toBeVisible();
  });

  test("delete removes the library", async ({ page }) => {
    const captured = await installMocks(page);
    await openLibrary(page);

    await page.getByRole("button", { name: "Delete library", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();

    await expect(page.getByText("Library deleted", { exact: true })).toBeVisible({ timeout: 5_000 });
    expect(captured.deletes).toEqual(["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"]);
    await expect(page.getByTestId("library-empty-state")).toBeVisible();
  });

  test("a refused delete keeps the library and says so", async ({ page }) => {
    await installMocks(page, { deleteStatus: 409 });
    await openLibrary(page);

    await page.getByRole("button", { name: "Delete library", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();

    await expect(page.getByText("Failed to delete library", { exact: true })).toBeVisible({ timeout: 5_000 });
    await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Main", exact: true })).toBeVisible();
    await expect(page.getByTestId("library-empty-state")).toHaveCount(0);
  });
});
