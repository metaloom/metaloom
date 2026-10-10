import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the shared ReactionsPanel, through both screens that mount it: the Task detail
 * drawer (`tasks-*` test ids, task-scoped routes) and the Asset detail Reactions tab (`assets-*`
 * test ids, asset-scoped routes).
 *
 * The panel itself is dumb - it renders what it is handed and reports clicks. What can break is
 * the wiring around it in each host: which route the click becomes, what the request body says,
 * whether the list (and the tab's count) follows the server's answer, whether only the caller's own
 * reactions can be removed, and whether a failed write is said out loud rather than swallowed.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const OTHER_UUID = "99999999-9999-9999-9999-999999999999";
const TASK_UUID = "33333333-3333-3333-3333-333333333333";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

/** Shape of `ReactionResponseItem` in src/api/reactions.ts. */
interface StoredReaction {
  uuid: string;
  type: string;
  rating?: number;
  status: { creator: { uuid: string; username?: string }; created: string };
}

interface Recorder {
  /** `METHOD path` + parsed body for every write against a reactions route. */
  writes: { method: string; path: string; body: unknown }[];
  /** Answer the next create/delete with this status instead of succeeding. */
  failWith: number | null;
  reactions: StoredReaction[];
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function reaction(uuid: string, type: string, creator: string, extra: Partial<StoredReaction> = {}): StoredReaction {
  return { uuid, type, status: { creator: { uuid: creator }, created: "2026-09-01T10:00:00Z" }, ...extra };
}

function seed(): StoredReaction[] {
  return [
    reaction("r-mine", "THUMBSUP", ME_UUID),
    reaction("r-other", "SATISFIED", OTHER_UUID),
    // Not pickable from the panel, but a workflow rating still lands on the same table.
    reaction("r-rating", "RATING", OTHER_UUID, { rating: 4 }),
  ];
}

/**
 * One in-memory reaction store served on a scope's reactions routes.
 *
 * Registered for one scope (`tasks` or `assets`) so each test proves its host talks to its own
 * routes: the other scope stays on the catch-all and records nothing.
 */
async function installMocks(page: Page, scope: "tasks" | "assets", initial: StoredReaction[]): Promise<Recorder> {
  const rec: Recorder = { writes: [], failWith: null, reactions: [...initial] };
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // Hosts.
  const task = {
    uuid: TASK_UUID, title: "Mock task", priority: "MEDIUM",
    status: { creator: { uuid: ME_UUID }, created: "2026-09-01T10:00:00Z" },
  };
  await page.route(/\/api\/v1\/tasks(\?|$)/, route => json(route, { data: [task], _metainfo: { totalCount: 1 } }));
  await page.route(/\/api\/v1\/tasks\/[^/?]+$/, route => json(route, task));
  const asset = {
    uuid: ASSET_UUID,
    file: { filename: "still.jpg", mimeType: "image/jpeg", size: 1024 },
    tags: [], annotations: [],
    status: { creator: { uuid: ME_UUID }, created: "2026-09-01T10:00:00Z" },
  };
  await page.route(/\/api\/v1\/assets(\?|$)/, route => json(route, { data: [asset], _metainfo: { totalCount: 1 } }));
  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route => json(route, asset));

  // The reactions themselves.
  const base = scope === "tasks" ? `tasks/${TASK_UUID}` : `assets/${ASSET_UUID}`;
  const listOrCreate = new RegExp(`/api/v1/${base}/reactions(\\?|$)`);
  const byUuid = new RegExp(`/api/v1/${base}/reactions/([^/?]+)$`);

  await page.route(listOrCreate, route => {
    const req = route.request();
    if (req.method() === "POST") {
      const body = JSON.parse(req.postData() || "{}");
      rec.writes.push({ method: "POST", path: new URL(req.url()).pathname, body });
      if (rec.failWith) return json(route, { message: "nope" }, rec.failWith);
      const created = reaction(`r-new-${++seq}`, body.type, ME_UUID);
      rec.reactions.unshift(created);
      return json(route, created, 201);
    }
    return json(route, { data: rec.reactions, _metainfo: { totalCount: rec.reactions.length } });
  });
  await page.route(byUuid, route => {
    const req = route.request();
    const uuid = decodeURIComponent(req.url().match(byUuid)![1]);
    if (req.method() === "DELETE") {
      rec.writes.push({ method: "DELETE", path: new URL(req.url()).pathname, body: null });
      if (rec.failWith) return json(route, { message: "nope" }, rec.failWith);
      const idx = rec.reactions.findIndex(r => r.uuid === uuid);
      if (idx < 0) return json(route, { message: "not found" }, 404);
      rec.reactions.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    return json(route, rec.reactions.find(r => r.uuid === uuid) ?? {});
  });

  return rec;
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openTaskDrawer(page: Page) {
  await page.goto("/ui/tasks");
  await login(page);
  await page.getByText("Mock task").first().click();
  await expect(page.getByRole("heading", { name: "Mock task" })).toBeVisible({ timeout: 10_000 });
}

async function openAssetReactionsTab(page: Page) {
  await page.goto(`/ui/assets/${ASSET_UUID}`);
  await login(page);
  await page.getByRole("tab", { name: /^Reactions/ }).click();
  await expect(page.getByTestId("assets-react-button")).toBeVisible({ timeout: 10_000 });
}

const chips = (page: Page, prefix: string) => page.getByTestId(`${prefix}-reaction-chip`);
const toast = (page: Page, text: string) => page.getByRole("alert").filter({ hasText: text });

// The same behaviour is owed by both hosts, so every case runs through both.
const HOSTS = [
  { name: "task drawer", scope: "tasks" as const, prefix: "tasks", open: openTaskDrawer },
  { name: "asset detail", scope: "assets" as const, prefix: "assets", open: openAssetReactionsTab },
];

for (const host of HOSTS) {
  test.describe(`ReactionsPanel in the ${host.name} – mocked`, () => {
    test("lists the reactions the server holds, with a face and a label for each", async ({ page }) => {
      await installMocks(page, host.scope, seed());
      await host.open(page);

      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(3, { timeout: 10_000 });
      await expect(list.nth(0)).toHaveText("👍Thumbs up");
      await expect(list.nth(1)).toHaveText("🤣Satisfied");
      // A rating's meaning is its number; a bare star would say less than the row holds.
      await expect(list.nth(2)).toHaveText("⭐Rating 4");
      await expect(page.getByText("No reactions yet", { exact: true })).toHaveCount(0);
    });

    test("only the caller's own reaction offers a remove button", async ({ page }) => {
      await installMocks(page, host.scope, seed());
      await host.open(page);

      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(3, { timeout: 10_000 });
      await expect(list.nth(0).getByTestId(`${host.prefix}-reaction-delete`)).toHaveCount(1);
      await expect(list.nth(1).getByTestId(`${host.prefix}-reaction-delete`)).toHaveCount(0);
      await expect(list.nth(2).getByTestId(`${host.prefix}-reaction-delete`)).toHaveCount(0);
    });

    test("the picker offers the five pickable types and never RATING", async ({ page }) => {
      await installMocks(page, host.scope, []);
      await host.open(page);

      await expect(page.getByText("No reactions yet", { exact: true })).toBeVisible({ timeout: 10_000 });
      await page.getByTestId(`${host.prefix}-react-button`).click();
      const options = page.locator(`[data-testid^="${host.prefix}-react-option-"]`);
      await expect(options).toHaveCount(5);
      await expect(page.getByTestId(`${host.prefix}-react-option-RATING`)).toHaveCount(0);
    });

    test("adding a reaction posts its type to this host's route and prepends the answer", async ({ page }) => {
      const rec = await installMocks(page, host.scope, seed());
      await host.open(page);
      await expect(chips(page, host.prefix)).toHaveCount(3, { timeout: 10_000 });

      await page.getByTestId(`${host.prefix}-react-button`).click();
      await page.getByTestId(`${host.prefix}-react-option-PLUS_ONE`).click();

      await expect.poll(() => rec.writes.length, { timeout: 10_000 }).toBe(1);
      const base = host.scope === "tasks" ? `/api/v1/tasks/${TASK_UUID}` : `/api/v1/assets/${ASSET_UUID}`;
      expect(rec.writes[0]).toEqual({ method: "POST", path: `${base}/reactions`, body: { type: "PLUS_ONE" } });

      // The created row comes first and is the caller's own, so it is removable at once.
      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(4);
      await expect(list.first()).toHaveText("➕+1");
      await expect(list.first().getByTestId(`${host.prefix}-reaction-delete`)).toHaveCount(1);
    });

    test("removing your reaction deletes it by uuid and drops it from the list", async ({ page }) => {
      const rec = await installMocks(page, host.scope, seed());
      await host.open(page);
      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(3, { timeout: 10_000 });

      await list.nth(0).getByTestId(`${host.prefix}-reaction-delete`).click();

      await expect.poll(() => rec.writes.length, { timeout: 10_000 }).toBe(1);
      const base = host.scope === "tasks" ? `/api/v1/tasks/${TASK_UUID}` : `/api/v1/assets/${ASSET_UUID}`;
      expect(rec.writes[0]).toEqual({ method: "DELETE", path: `${base}/reactions/r-mine`, body: null });
      await expect(list).toHaveCount(2);
      await expect(list.filter({ hasText: "Thumbs up" })).toHaveCount(0);
    });

    test("removing the last reaction falls back to the empty state", async ({ page }) => {
      await installMocks(page, host.scope, [reaction("r-mine", "MINUS_ONE", ME_UUID)]);
      await host.open(page);
      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(1, { timeout: 10_000 });

      await list.first().getByTestId(`${host.prefix}-reaction-delete`).click();

      await expect(list).toHaveCount(0, { timeout: 10_000 });
      await expect(page.getByText("No reactions yet", { exact: true })).toBeVisible();
    });

    test("a failed add says so and leaves the list as it was", async ({ page }) => {
      const rec = await installMocks(page, host.scope, seed());
      await host.open(page);
      await expect(chips(page, host.prefix)).toHaveCount(3, { timeout: 10_000 });

      rec.failWith = 500;
      await page.getByTestId(`${host.prefix}-react-button`).click();
      await page.getByTestId(`${host.prefix}-react-option-THUMBSDOWN`).click();

      await expect(toast(page, "Failed to add reaction")).toBeVisible({ timeout: 10_000 });
      await expect(chips(page, host.prefix)).toHaveCount(3);
      await expect(chips(page, host.prefix).filter({ hasText: "Thumbs down" })).toHaveCount(0);
    });

    test("a failed remove says so and keeps the reaction on screen", async ({ page }) => {
      const rec = await installMocks(page, host.scope, seed());
      await host.open(page);
      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(3, { timeout: 10_000 });

      rec.failWith = 500;
      await list.nth(0).getByTestId(`${host.prefix}-reaction-delete`).click();

      await expect(toast(page, "Failed to delete reaction")).toBeVisible({ timeout: 10_000 });
      await expect(list).toHaveCount(3);
      await expect(list.nth(0)).toHaveText("👍Thumbs up");
    });

    test("a double click on remove sends one delete, not a second one that 404s", async ({ page }) => {
      const rec = await installMocks(page, host.scope, seed());
      // Hold the first DELETE so the second click lands while it is still in flight - the
      // window a real double click falls into.
      let release!: () => void;
      const held = new Promise<void>(r => { release = r; });
      const base = host.scope === "tasks" ? `tasks/${TASK_UUID}` : `assets/${ASSET_UUID}`;
      await page.route(new RegExp(`/api/v1/${base}/reactions/r-mine$`), async route => {
        if (route.request().method() !== "DELETE") return route.fallback();
        await held;
        return route.fallback();
      });
      await host.open(page);
      const list = chips(page, host.prefix);
      await expect(list).toHaveCount(3, { timeout: 10_000 });

      await list.nth(0).getByTestId(`${host.prefix}-reaction-delete`).dblclick();
      release();

      await expect(list).toHaveCount(2, { timeout: 10_000 });
      // Negative check: a second DELETE, if one was sent, was parked behind the same gate and is
      // released together with the first. Give it a moment to reach the recorder before asserting
      // it never came.
      await page.waitForTimeout(300);
      expect(rec.writes.filter(w => w.method === "DELETE")).toHaveLength(1);
      await expect(toast(page, "Failed to delete reaction")).toHaveCount(0);
    });
  });
}

test.describe("ReactionsPanel counts – mocked", () => {
  test("the asset Reactions tab counts what is listed, and follows adds and removes", async ({ page }) => {
    await installMocks(page, "assets", seed());
    await page.goto(`/ui/assets/${ASSET_UUID}`);
    await login(page);

    const tab = page.getByRole("tab", { name: /^Reactions/ });
    await expect(tab).toHaveAttribute("aria-label", "Reactions (3)", { timeout: 10_000 });
    await tab.click();

    await page.getByTestId("assets-react-button").click();
    await page.getByTestId("assets-react-option-SATISFIED").click();
    await expect(tab).toHaveAttribute("aria-label", "Reactions (4)", { timeout: 10_000 });

    // The new one (first) and the seeded own one are removable; take both away.
    await chips(page, "assets").first().getByTestId("assets-reaction-delete").click();
    await expect(tab).toHaveAttribute("aria-label", "Reactions (3)", { timeout: 10_000 });
    await chips(page, "assets").first().getByTestId("assets-reaction-delete").click();
    await expect(tab).toHaveAttribute("aria-label", "Reactions (2)", { timeout: 10_000 });
  });

  test("the task drawer loads reactions from the task's own route, not the asset one", async ({ page }) => {
    await installMocks(page, "tasks", seed());
    const listed: string[] = [];
    page.on("request", r => {
      const path = new URL(r.url()).pathname;
      if (r.method() === "GET" && path.endsWith("/reactions")) listed.push(path);
    });
    await openTaskDrawer(page);

    await expect(chips(page, "tasks")).toHaveCount(3, { timeout: 10_000 });
    expect(listed).toContain(`/api/v1/tasks/${TASK_UUID}/reactions`);
    expect(listed.filter(p => p.startsWith("/api/v1/assets/"))).toEqual([]);
  });
});
