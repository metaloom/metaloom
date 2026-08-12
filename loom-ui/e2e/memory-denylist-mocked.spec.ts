import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the memory denylist admin (`/admin/memory-denylist`).
 *
 * These rules are a safety control: each one is a regular expression the chat agent may never
 * store, and a rule that silently stops applying is exactly the failure nobody notices. So the
 * assertions are about the wire and about the feedback — the REST path is `memory-deny-rules`
 * (the testids use the other spelling), updates go over **POST /:uuid** rather than PUT, and a
 * pattern the server refuses to compile has to be visible next to the field that caused it.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const RULES = [
  {
    uuid: "rule-1", name: "api-keys", pattern: "(?i)\\b(sk-[a-z0-9]{20,})\\b",
    message: "Never store credentials. Note where the key lives instead.", enabled: true,
  },
  {
    uuid: "rule-2", name: "home-address", pattern: "(?i)\\bMusterstrasse\\b",
    message: "Personal addresses do not belong in memory.", enabled: false,
  },
];

interface Call {
  method: string;
  url: string;
  body: unknown;
}

interface MockOptions {
  rules?: unknown[];
  /** Status for POST (create and update) — 400 exercises the invalid-regex path. */
  writeStatus?: number;
  writeBody?: unknown;
}

async function installMocks(page: Page, opts: MockOptions = {}): Promise<Call[]> {
  const calls: Call[] = [];

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  const rules = opts.rules ?? RULES;

  await page.route(/\/api\/v1\/memory-deny-rules/, route => {
    const req = route.request();
    let body: unknown = null;
    try {
      body = req.postDataJSON();
    } catch {
      body = req.postData();
    }
    calls.push({ method: req.method(), url: req.url(), body });

    if (req.method() === "GET") {
      return json(route, { data: rules, _metainfo: { perPage: 100, totalCount: rules.length } });
    }
    if (req.method() === "DELETE") {
      return route.fulfill({ status: 204, body: "" });
    }
    const status = opts.writeStatus ?? 200;
    if (status >= 400) {
      return json(route, opts.writeBody ?? { message: "rejected" }, status);
    }
    return json(route, { uuid: "rule-new", name: "new", pattern: ".", message: "no", enabled: true });
  });

  return calls;
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/** Deep-link first, then sign in — the token is in-memory, so the reverse order loses it. */
async function open(page: Page, path = "/admin/memory-denylist") {
  await page.goto(`/ui${path}`);
  await login(page);
}

function writes(calls: Call[]): Call[] {
  return calls.filter(c => c.method !== "GET");
}

test.describe("Memory denylist admin – mocked e2e", () => {

  test("the rules render, one row each", async ({ page }) => {
    await installMocks(page);
    await open(page);

    await expect(page.getByTestId("memory-denylist-admin")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-denylist-row-api-keys")).toBeVisible();
    await expect(page.getByTestId("memory-denylist-row-home-address")).toBeVisible();
    // The pattern is shown verbatim — an admin has to be able to read what is actually enforced.
    await expect(page.getByTestId("memory-denylist-row-api-keys")).toContainText("sk-[a-z0-9]{20,}");
    await expect(page.getByTestId("memory-denylist-empty")).toBeHidden();
  });

  test("no rules at all is stated", async ({ page }) => {
    await installMocks(page, { rules: [] });
    await open(page);

    await expect(page.getByTestId("memory-denylist-empty")).toBeVisible({ timeout: 10_000 });
  });

  test("the search box narrows on name and on pattern", async ({ page }) => {
    await installMocks(page);
    await open(page);

    const search = page.getByPlaceholder("Search rules");
    await expect(search).toBeVisible({ timeout: 10_000 });

    await search.fill("address");
    await expect(page.getByTestId("memory-denylist-row-home-address")).toBeVisible();
    await expect(page.getByTestId("memory-denylist-row-api-keys")).toBeHidden();

    // Searching the pattern text finds the rule whose name says nothing about it.
    await search.fill("sk-");
    await expect(page.getByTestId("memory-denylist-row-api-keys")).toBeVisible();
    await expect(page.getByTestId("memory-denylist-row-home-address")).toBeHidden();

    await search.fill("");
    await expect(page.getByTestId("memory-denylist-row-home-address")).toBeVisible();
  });

  test("adding a rule POSTs name, pattern and message", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    await page.getByTestId("memory-denylist-add").click();
    await page.getByTestId("memory-denylist-name").locator("input").fill("bank-details");
    await page.getByTestId("memory-denylist-pattern").locator("input").fill("(?i)\\bIBAN\\b");
    await page.getByTestId("memory-denylist-message").locator("textarea").first()
      .fill("Bank details must not be remembered.");
    await page.getByTestId("memory-denylist-save").click();

    await expect(page.getByTestId("memory-denylist-save")).toBeHidden();

    const written = writes(calls);
    expect(written).toHaveLength(1);
    expect(written[0].method).toBe("POST");
    expect(new URL(written[0].url).pathname).toMatch(/\/memory-deny-rules$/);
    expect(written[0].body).toEqual({
      name: "bank-details",
      pattern: "(?i)\\bIBAN\\b",
      message: "Bank details must not be remembered.",
    });
  });

  test("editing a rule POSTs to the rule's uuid — the loom convention, not PUT", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    const row = page.getByTestId("memory-denylist-row-api-keys");
    await expect(row).toBeVisible({ timeout: 10_000 });
    // The row's first icon button is edit; delete is the second.
    await row.getByRole("button").first().click();

    await expect(page.getByTestId("memory-denylist-name").locator("input")).toHaveValue("api-keys");
    await page.getByTestId("memory-denylist-message").locator("textarea").first().fill("Store the vault path instead.");
    await page.getByTestId("memory-denylist-save").click();
    await expect(page.getByTestId("memory-denylist-save")).toBeHidden();

    const written = writes(calls);
    expect(written).toHaveLength(1);
    expect(written[0].method).toBe("POST");
    expect(new URL(written[0].url).pathname).toMatch(/\/memory-deny-rules\/rule-1$/);
    expect(written[0].body).toMatchObject({ name: "api-keys", message: "Store the vault path instead." });
  });

  test("the toggle flips `enabled` on that rule alone", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    const toggle = page.getByTestId("memory-denylist-toggle-home-address");
    await expect(toggle).toBeVisible({ timeout: 10_000 });
    await expect(toggle.locator("input")).not.toBeChecked();

    await toggle.click();

    await expect.poll(() => writes(calls).length).toBe(1);
    const written = writes(calls)[0];
    expect(written.method).toBe("POST");
    expect(new URL(written.url).pathname).toMatch(/\/memory-deny-rules\/rule-2$/);
    // Only the flag travels — an enable must not rewrite the pattern from stale dialog state.
    expect(written.body).toEqual({ enabled: true });
  });

  test("a pattern the server cannot compile is reported inline, next to the field", async ({ page }) => {
    await installMocks(page, {
      writeStatus: 400,
      writeBody: { message: "The pattern is not a valid regular expression: Unclosed group" },
    });
    await open(page);

    await page.getByTestId("memory-denylist-add").click();
    await page.getByTestId("memory-denylist-name").locator("input").fill("broken");
    await page.getByTestId("memory-denylist-pattern").locator("input").fill("(unclosed");
    await page.getByTestId("memory-denylist-message").locator("textarea").first().fill("nope");
    await page.getByTestId("memory-denylist-save").click();

    const error = page.getByTestId("memory-denylist-error");
    await expect(error).toBeVisible({ timeout: 10_000 });
    await expect(error).toContainText("Unclosed group");
    // The dialog stays open with the broken pattern in place so it can be fixed.
    await expect(page.getByTestId("memory-denylist-pattern").locator("input")).toHaveValue("(unclosed");
  });

  test("deleting a rule DELETEs it and refreshes the list", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    const row = page.getByTestId("memory-denylist-row-home-address");
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByRole("button").nth(1).click();

    await expect.poll(() => writes(calls).length).toBe(1);
    const written = writes(calls)[0];
    expect(written.method).toBe("DELETE");
    expect(new URL(written.url).pathname).toMatch(/\/memory-deny-rules\/rule-2$/);
  });

  test("the screen is reachable from the sidebar and from the admin tabs", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await login(page);

    // A MANAGEMENT entry in its own right — it is not filed under the ACL sub-group.
    await page.getByTestId("sidebar-item-/admin/memory-denylist").click();
    await expect(page).toHaveURL(/\/ui\/admin\/memory-denylist$/);
    await expect(page.getByTestId("memory-denylist-admin")).toBeVisible({ timeout: 10_000 });

    // …and as an admin tab, from a sibling admin screen.
    await page.getByTestId("sidebar-item-/admin/spaces").click();
    await expect(page.getByTestId("memory-denylist-admin")).toBeHidden();
    await page.getByRole("tab", { name: "Memory Denylist", exact: true }).click();
    await expect(page).toHaveURL(/\/ui\/admin\/memory-denylist$/);
    await expect(page.getByTestId("memory-denylist-admin")).toBeVisible();
  });
});
