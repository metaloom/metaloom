import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the agent memory bank (`/memory`).
 *
 * The screen is the human half of a store the agent writes to on its own, so the contract under
 * test is mostly about *not* losing what is already there: a new note must not overwrite an
 * existing one, a rename must not clobber the note it renames onto, and a rejected save must keep
 * the draft. The wire shape matters as much as the pixels — the note id is a nested path and
 * travels as the `id` **query parameter**, never in the route (`entryQuery`, `api/memory.ts`).
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const USER_SCOPE = {
  scope: "user", uuid: "scope-user", label: "user", ref: "user",
  count: 2, bytes: 360, maxEntries: 50, maxBytes: 10240, writable: true,
};

/** A shared scope the deployment keeps human-curated: readable, not writable. */
const GROUP_SCOPE = {
  scope: "group", uuid: "scope-group", label: "editors", ref: "group:editors",
  count: 1, bytes: 90, maxEntries: 50, maxBytes: 10240, writable: false,
};

const USER_ENTRIES = [
  { uuid: "m-1", scope: "user", id: "notes/drone.md", title: "Drone settings", size: 120, version: 3, sessionName: "ingest", edited: "2026-01-02T00:00:00Z", editor: "admin" },
  { uuid: "m-2", scope: "user", id: "notes/colour.md", title: "Colour targets", size: 240, version: 1, sessionName: "grading", edited: "2026-01-01T00:00:00Z", editor: "admin" },
];

const GROUP_ENTRIES = [
  { uuid: "m-9", scope: "group", id: "team/style.md", title: "House style", size: 90, version: 2, sessionName: "onboarding", edited: "2026-01-03T00:00:00Z", editor: "admin" },
];

interface Call {
  method: string;
  url: string;
  body: unknown;
}

interface MockOptions {
  /** Scopes returned by `GET /memory/scopes`; `[]` exercises the no-scopes state. */
  scopes?: unknown[];
  /** Entries returned for the user scope. */
  entries?: unknown[];
  /** Status for the next write (POST/PUT) — everything else stays 200. */
  writeStatus?: number;
  writeBody?: unknown;
  /** 404 every memory route, as the server does when LOOM_AGENT_MEMORY_ENABLED is unset. */
  disabled?: boolean;
}

/**
 * Install the memory routes and return the recorded write calls.
 *
 * Reads are recorded too — the scope tabs are only believable if picking one actually re-requests
 * the list for that scope.
 */
async function installMocks(page: Page, opts: MockOptions = {}): Promise<Call[]> {
  const calls: Call[] = [];

  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  const record = (route: Route) => {
    const req = route.request();
    let body: unknown = null;
    try {
      body = req.postDataJSON();
    } catch {
      body = req.postData();
    }
    calls.push({ method: req.method(), url: req.url(), body });
  };

  await page.route(/\/api\/v1\/memory\/scopes/, route => {
    record(route);
    if (opts.disabled) return json(route, { message: "Not found" }, 404);
    return json(route, { scopes: opts.scopes ?? [USER_SCOPE, GROUP_SCOPE] });
  });

  await page.route(/\/api\/v1\/memory\?/, route => {
    record(route);
    if (opts.disabled) return json(route, { message: "Not found" }, 404);
    const scope = new URL(route.request().url()).searchParams.get("scope");
    const entries = scope === "group" ? GROUP_ENTRIES : (opts.entries ?? USER_ENTRIES);
    return json(route, { scope, entries });
  });

  await page.route(/\/api\/v1\/memory\/entry\?/, route => {
    record(route);
    const method = route.request().method();
    if (opts.disabled) return json(route, { message: "Not found" }, 404);
    if (method === "GET") {
      const id = new URL(route.request().url()).searchParams.get("id") ?? "";
      const summary = [...USER_ENTRIES, ...GROUP_ENTRIES].find(e => e.id === id) ?? USER_ENTRIES[0];
      return json(route, { ...summary, body: `# ${summary.title}\n\nstored body` });
    }
    if (method === "DELETE") {
      return json(route, { deleted: true });
    }
    // POST (create) and PUT (upsert)
    const status = opts.writeStatus ?? 200;
    if (status >= 400) {
      return json(route, opts.writeBody ?? { message: "rejected" }, status);
    }
    return json(route, { ...USER_ENTRIES[0], id: new URL(route.request().url()).searchParams.get("id") });
  });

  return calls;
}

/**
 * Open `/memory` directly.
 *
 * The token is in-memory only, so a `goto` after signing in lands back on the login form. Load the
 * URL first and sign in on it — the router then resolves to the route, not to the root.
 */
async function open(page: Page, path = "/memory") {
  await page.goto(`/ui${path}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/** Every write (POST/PUT/DELETE) against `/memory/entry`, in the order the app issued them. */
function writes(calls: Call[]): Call[] {
  return calls.filter(c => c.method !== "GET" && c.url.includes("/memory/entry"));
}

function paramsOf(call: Call): URLSearchParams {
  return new URL(call.url).searchParams;
}

test.describe("Agent memory – mocked e2e", () => {

  test("scope tabs come from the server and switching one re-lists that scope", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    await expect(page.getByTestId("memory-view")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-scope-tab-user")).toBeVisible();
    await expect(page.getByTestId("memory-scope-tab-group")).toBeVisible();
    // The user scope is selected first, so its notes are the ones on screen.
    await expect(page.getByTestId("memory-table")).toBeVisible();
    await expect(page.getByTestId("memory-row-notes/drone.md")).toBeVisible();

    await page.getByTestId("memory-scope-tab-group").click();
    await expect(page.getByTestId("memory-row-team/style.md")).toBeVisible();
    await expect(page.getByTestId("memory-row-notes/drone.md")).toBeHidden();

    // A shared scope is addressed by label, so the list call carries both scope and ref.
    const listCalls = calls.filter(c => c.url.includes("/memory?"));
    const groupList = listCalls.find(c => paramsOf(c).get("scope") === "group");
    expect(groupList).toBeTruthy();
    expect(paramsOf(groupList!).get("ref")).toBe("editors");
    // The private scope has no ref to send.
    expect(paramsOf(listCalls[0]).get("scope")).toBe("user");
    expect(paramsOf(listCalls[0]).get("ref")).toBeNull();
  });

  test("no scopes at all is stated, not left blank", async ({ page }) => {
    await installMocks(page, { scopes: [] });
    await open(page);

    await expect(page.getByTestId("memory-empty-scopes")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-table")).toBeHidden();
  });

  test("an empty scope and a filtered-out scope are different states", async ({ page }) => {
    await installMocks(page, { scopes: [USER_SCOPE], entries: [] });
    await open(page);

    // Nothing stored yet.
    await expect(page.getByTestId("memory-empty")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-no-match")).toBeHidden();
  });

  test("a search that matches nothing shows the no-match hint, not the empty state", async ({ page }) => {
    await installMocks(page);
    await open(page);

    await expect(page.getByTestId("memory-row-notes/drone.md")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("memory-search").locator("input").fill("nothingmatchesthis");

    await expect(page.getByTestId("memory-no-match")).toBeVisible();
    await expect(page.getByTestId("memory-empty")).toBeHidden();
  });

  test("a read-only scope offers no way to write", async ({ page }) => {
    await installMocks(page);
    await open(page);

    await expect(page.getByTestId("memory-new")).toBeEnabled({ timeout: 10_000 });

    // The group scope is human-curated (writable: false) — this button is the only thing
    // enforcing that client-side.
    await page.getByTestId("memory-scope-tab-group").click();
    await expect(page.getByTestId("memory-row-team/style.md")).toBeVisible();
    await expect(page.getByTestId("memory-new")).toBeDisabled();
    await expect(page.getByTestId("memory-delete-team/style.md")).toBeDisabled();
  });

  test("creating a note POSTs it with the id as a query parameter", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    await page.getByTestId("memory-new").click();
    await page.getByTestId("memory-editor-id").locator("input").fill("projects/loom-db.md");
    await page.getByTestId("memory-editor-title").locator("input").fill("Loom DB");
    await page.getByTestId("memory-editor-body").locator("textarea").first().fill("Schema notes");
    await page.getByTestId("memory-editor-save").click();

    await expect(page.getByTestId("memory-editor-id")).toBeHidden();

    const write = writes(calls)[0];
    // A create is a POST — a PUT here would silently overwrite a note the user was never shown.
    expect(write.method).toBe("POST");
    expect(new URL(write.url).pathname).toMatch(/\/memory\/entry$/);
    expect(paramsOf(write).get("scope")).toBe("user");
    expect(paramsOf(write).get("id")).toBe("projects/loom-db.md");
    expect(write.body).toEqual({ body: "Schema notes", title: "Loom DB" });
  });

  test("a taken id is reported in the editor and the draft survives", async ({ page }) => {
    const calls = await installMocks(page, { writeStatus: 409, writeBody: { message: "A memory entry with this id already exists." } });
    await open(page);

    await page.getByTestId("memory-new").click();
    await page.getByTestId("memory-editor-id").locator("input").fill("notes/drone.md");
    await page.getByTestId("memory-editor-body").locator("textarea").first().fill("second opinion");
    await page.getByTestId("memory-editor-save").click();

    await expect(page.getByTestId("memory-editor-error")).toBeVisible({ timeout: 10_000 });
    // The dialog stays open with the draft intact so the id can be corrected.
    await expect(page.getByTestId("memory-editor-id").locator("input")).toHaveValue("notes/drone.md");
    await expect(page.getByTestId("memory-editor-body").locator("textarea").first()).toHaveValue("second opinion");
    // Exactly one attempt, and nothing was deleted on the way.
    expect(writes(calls).map(c => c.method)).toEqual(["POST"]);

    // Correcting the id clears the complaint.
    await page.getByTestId("memory-editor-id").locator("input").fill("notes/drone-2.md");
    await expect(page.getByTestId("memory-editor-error")).toBeHidden();
  });

  test("editing an existing note upserts it with PUT", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    await page.getByTestId("memory-edit-notes/drone.md").click();
    await expect(page.getByTestId("memory-editor-body").locator("textarea").first()).toHaveValue(/stored body/);

    await page.getByTestId("memory-editor-body").locator("textarea").first().fill("revised body");
    await page.getByTestId("memory-editor-save").click();
    await expect(page.getByTestId("memory-editor-id")).toBeHidden();

    const written = writes(calls);
    expect(written.map(c => c.method)).toEqual(["PUT"]);
    expect(paramsOf(written[0]).get("id")).toBe("notes/drone.md");
    expect(written[0].body).toEqual({ body: "revised body", title: "Drone settings" });
  });

  test("renaming writes the new id first and only then drops the old one", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    await page.getByTestId("memory-edit-notes/drone.md").click();
    await page.getByTestId("memory-editor-id").locator("input").fill("notes/drone-v2.md");
    await page.getByTestId("memory-editor-save").click();
    await expect(page.getByTestId("memory-editor-id")).toBeHidden();

    const written = writes(calls);
    // The order is load-bearing: the delete must not run before the new note exists, and the
    // write must be a POST so a rename onto an occupied id is refused rather than silently
    // overwriting it.
    expect(written.map(c => c.method)).toEqual(["POST", "DELETE"]);
    expect(paramsOf(written[0]).get("id")).toBe("notes/drone-v2.md");
    expect(paramsOf(written[1]).get("id")).toBe("notes/drone.md");
  });

  test("a rename onto an occupied id leaves both notes alone", async ({ page }) => {
    const calls = await installMocks(page, { writeStatus: 409 });
    await open(page);

    await page.getByTestId("memory-edit-notes/drone.md").click();
    await page.getByTestId("memory-editor-id").locator("input").fill("notes/colour.md");
    await page.getByTestId("memory-editor-save").click();

    await expect(page.getByTestId("memory-editor-error")).toBeVisible({ timeout: 10_000 });
    // Crucially no DELETE — the note being renamed is still there.
    expect(writes(calls).map(c => c.method)).toEqual(["POST"]);
  });

  test("a rejected save keeps the editor open and says why", async ({ page }) => {
    await installMocks(page, { writeStatus: 400, writeBody: { message: "The scope is full." } });
    await open(page);

    await page.getByTestId("memory-edit-notes/colour.md").click();
    await page.getByTestId("memory-editor-body").locator("textarea").first().fill("too much");
    await page.getByTestId("memory-editor-save").click();

    // Not a conflict, so it is a toast — but the draft is still on screen either way.
    await expect(page.getByText(/The scope is full/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-editor-body").locator("textarea").first()).toHaveValue("too much");
  });

  test("deleting asks first, then issues the DELETE for that id", async ({ page }) => {
    const calls = await installMocks(page);
    await open(page);

    await page.getByTestId("memory-delete-notes/colour.md").click();
    await page.getByTestId("memory-delete-confirm").click();
    await expect(page.getByTestId("memory-delete-confirm")).toBeHidden();

    const written = writes(calls);
    expect(written.map(c => c.method)).toEqual(["DELETE"]);
    expect(paramsOf(written[0]).get("id")).toBe("notes/colour.md");
  });

  test("the feature being switched off reads as such, not as a blank screen", async ({ page }) => {
    // LOOM_AGENT_MEMORY_ENABLED unset — the routes are never registered, so every call 404s.
    await installMocks(page, { disabled: true });
    await open(page);

    await expect(page.getByTestId("memory-view")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("memory-empty-scopes")).toBeVisible();
  });
});
