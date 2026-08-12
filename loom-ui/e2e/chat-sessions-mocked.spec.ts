import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the chat-sessions feature — management view (create/load/publish/delete),
 * the published library tab, and loading a session's coding-workspace files. All `/api/v1/**`
 * calls are intercepted; sessions live in a small in-memory store.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const OTHER_UUID = "22222222-2222-2222-2222-222222222222";
const SHARED_CHAT_UUID = "chat-shared-1";
const EMPTY_CHAT_UUID = "chat-empty-1";
const FOREIGN_UUID = "session-foreign";

/** One recorded API call, so a test can assert what the UI *asked for*, not only what it rendered. */
interface Call {
  method: string;
  url: string;
  body: any;
}

interface StoredSession {
  uuid: string;
  chatUuid?: string;
  name: string;
  description?: string;
  tags?: string[];
  published: boolean;
  skills?: { skillUuid: string; skillVersion: number }[];
  contextRefs?: unknown[];
  status?: { creator?: { uuid: string; name?: string }; created?: string; edited?: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function sharedSession(): StoredSession {
  return {
    uuid: "session-shared",
    chatUuid: SHARED_CHAT_UUID,
    name: "shared-pipeline",
    description: "A published coding session that tags beach footage",
    tags: ["video", "pipeline"],
    published: true,
    skills: [],
    contextRefs: [],
    status: { creator: { uuid: ME_UUID, name: "admin" }, created: "2026-07-24T09:00:00Z", edited: "2026-07-24T09:00:00Z" },
  };
}

/** Owned but with an empty workspace — drives the "no files" branch of the Files panel. */
function emptyWorkspaceSession(): StoredSession {
  return {
    uuid: "session-empty",
    chatUuid: EMPTY_CHAT_UUID,
    name: "empty-workspace",
    description: "A session whose sandbox has no files",
    tags: [],
    published: false,
    skills: [],
    contextRefs: [],
    status: { creator: { uuid: ME_UUID, name: "admin" }, created: "2026-07-24T10:00:00Z", edited: "2026-07-24T10:00:00Z" },
  };
}

/**
 * Published by *another* user. It must never appear under "My sessions" — that is what makes the
 * mine-tab test meaningful: a UI that filtered one page client-side would still show it.
 */
function foreignSession(): StoredSession {
  return {
    uuid: FOREIGN_UUID,
    chatUuid: "chat-foreign-1",
    name: "foreign-session",
    description: "Published by somebody else",
    tags: ["shared"],
    published: true,
    skills: [],
    contextRefs: [],
    status: { creator: { uuid: OTHER_UUID, name: "bob" }, created: "2026-07-20T09:00:00Z", edited: "2026-07-20T09:00:00Z" },
  };
}

/** Workspace listings keyed by chat uuid. A chat uuid that is absent has no live runner -> 404. */
const WORKSPACE_FILES: Record<string, { name: string; type: string; size: number }[]> = {
  [SHARED_CHAT_UUID]: [
    { name: "report.py", type: "file", size: 2048 },
    { name: "out", type: "dir", size: 0 },
    { name: "data.csv", type: "file", size: 51200 },
  ],
  [EMPTY_CHAT_UUID]: [],
};

async function installMocks(page: Page) {
  const sessions: StoredSession[] = [sharedSession(), emptyWorkspaceSession(), foreignSession()];
  const calls: Call[] = [];
  let seq = 0;

  const record = (route: Route) => {
    const raw = route.request().postData();
    calls.push({ method: route.request().method(), url: route.request().url(), body: raw ? JSON.parse(raw) : null });
  };

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // Collection endpoint: POST creates, GET lists (filtered by ?scope=mine|published).
  await page.route(/\/api\/v1\/chat-sessions(\?.*)?$/, route => {
    record(route);
    const method = route.request().method();
    if (method === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredSession = {
        uuid: `session-${++seq}`,
        chatUuid: body.chatUuid,
        name: body.name,
        description: body.description,
        tags: body.tags ?? [],
        published: false,
        skills: [],
        contextRefs: [],
        status: { creator: { uuid: ME_UUID, name: "admin" }, created: new Date().toISOString(), edited: new Date().toISOString() },
      };
      sessions.unshift(created);
      return json(route, created, 201);
    }
    // The scopes are filtered server-side: "published" is the cross-user library, "mine" is only
    // what this user created. Neither is a subset the client could derive from the other.
    const url = route.request().url();
    const published = /scope=published/.test(url);
    const data = published
      ? sessions.filter(s => s.published)
      : sessions.filter(s => s.status?.creator?.uuid === ME_UUID);
    return json(route, { data });
  });

  // Session files proxy (Phase-2). Register before the generic item route (LIFO -> later = higher prio).
  await page.route(/\/api\/v1\/sessions\/[^/]+\/files(\?.*)?$/, route => {
    record(route);
    const chatUuid = route.request().url().split("/sessions/")[1].split(/[?/]/)[0];
    const entries = WORKSPACE_FILES[chatUuid];
    // No live runner for this chat -> the proxy 404s, which the UI reports as "unavailable".
    if (!entries) return json(route, { message: "no live coding session" }, 404);
    return json(route, { entries });
  });

  // Item sub-routes: publish / unpublish / context (must be registered AFTER the generic item route).
  await page.route(/\/api\/v1\/chat-sessions\/[^/]+\/(publish|unpublish)$/, route => {
    record(route);
    const parts = route.request().url().split("/chat-sessions/")[1].split("/");
    const uuid = parts[0];
    const action = parts[1].split("?")[0];
    const found = sessions.find(s => s.uuid === uuid);
    if (found) found.published = action === "publish";
    return json(route, found ?? {}, found ? 200 : 404);
  });
  await page.route(/\/api\/v1\/chat-sessions\/[^/]+\/context$/, route => {
    record(route);
    const uuid = route.request().url().split("/chat-sessions/")[1].split("/")[0];
    const found = sessions.find(s => s.uuid === uuid);
    if (route.request().method() === "PUT") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (found) found.contextRefs = body.refs ?? [];
      return json(route, { refs: found?.contextRefs ?? [] });
    }
    return json(route, { refs: found?.contextRefs ?? [] });
  });

  // Item endpoint: GET / POST(update) / DELETE. Registered last so it does not shadow the sub-routes
  // — its regex excludes any further path segment.
  await page.route(/\/api\/v1\/chat-sessions\/[^/?]+(\?.*)?$/, route => {
    record(route);
    const uuid = route.request().url().split("/chat-sessions/")[1].split(/[?/]/)[0];
    const found = sessions.find(s => s.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = sessions.findIndex(s => s.uuid === uuid);
      if (idx >= 0) sessions.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (found) Object.assign(found, body);
      return json(route, found ?? {}, found ? 200 : 404);
    }
    return json(route, found ?? {}, found ? 200 : 404);
  });

  return { sessions, calls };
}

/** Calls matching a method + url predicate, newest last. */
function matching(calls: Call[], method: string, re: RegExp): Call[] {
  return calls.filter(c => c.method === method && re.test(c.url));
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openSessionsView(page: Page) {
  await page.getByRole("button", { name: "Chat Sessions" }).first().click();
  await expect(page.getByTestId("chat-sessions-table")).toBeVisible({ timeout: 10_000 });
}

test.describe("Chat sessions – mocked e2e", () => {
  test("create, load, publish and delete a session", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSessionsView(page);

    // Create
    await page.getByTestId("chat-session-create-button").click();
    await page.getByTestId("chat-session-create-name").fill("my-session");
    await page.getByTestId("chat-session-create-description").fill("A hand-made session");
    await page.getByTestId("chat-session-create-save").click();
    await expect(page.getByTestId("chat-session-row-my-session")).toBeVisible({ timeout: 5_000 });

    // Load: open the detail view
    await page.getByTestId("chat-session-row-my-session").click();
    await expect(page.getByTestId("chat-session-name-input")).toHaveValue("my-session", { timeout: 5_000 });

    // Publish from the detail view — the button flips to "Unpublish" and the Published chip appears.
    await expect(page.getByTestId("chat-session-publish-toggle")).toHaveText("Publish");
    await page.getByTestId("chat-session-publish-toggle").click();
    await expect(page.getByTestId("chat-session-publish-toggle")).toHaveText("Unpublish", { timeout: 5_000 });

    // Back to the list and delete with confirmation
    await page.getByRole("button", { name: "Chat Sessions" }).first().click();
    await expect(page.getByTestId("chat-session-row-my-session")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("chat-session-delete-my-session").click();
    await page.getByTestId("chat-session-delete-confirm").click();
    await expect(page.getByTestId("chat-session-row-my-session")).toBeHidden({ timeout: 5_000 });
  });

  test("the library tab lists published sessions", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-sessions-library-tab").click();
    await expect(page.getByTestId("chat-session-row-shared-pipeline")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText("A published coding session that tags beach footage")).toBeVisible();
  });

  test("session workspace files load in the detail view", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSessionsView(page);

    // The seeded session is owned + published, so it shows in the default (mine) tab.
    await page.getByTestId("chat-session-row-shared-pipeline").click();

    // The Files panel loads the coding-session workspace via the /sessions/:chatUuid/files proxy.
    await expect(page.getByTestId("session-files-list")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("session-file-report.py")).toBeVisible();
    await expect(page.getByTestId("session-file-data.csv")).toBeVisible();
  });

  test("the create dialog opens and POSTs a new session", async ({ page }) => {
    const { calls } = await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await expect(page.getByTestId("chat-session-create-dialog")).toBeHidden();
    await page.getByTestId("chat-session-create-button").click();
    await expect(page.getByTestId("chat-session-create-dialog")).toBeVisible({ timeout: 5_000 });

    await page.getByTestId("chat-session-create-name").fill("planner-session");
    await page.getByTestId("chat-session-create-description").fill("Plans the nightly re-tag run");
    await page.getByTestId("chat-session-create-save").click();

    // The dialog closes only after the POST resolved.
    await expect(page.getByTestId("chat-session-create-dialog")).toBeHidden({ timeout: 5_000 });

    const posts = () => matching(calls, "POST", /\/chat-sessions(\?|$)/);
    await expect.poll(() => posts().length, { timeout: 5_000 }).toBe(1);
    expect(posts()[0].body).toMatchObject({
      name: "planner-session",
      description: "Plans the nightly re-tag run",
    });
    await expect(page.getByTestId("chat-session-row-planner-session")).toBeVisible({ timeout: 5_000 });
  });

  test("tags typed in the create dialog travel in the create POST", async ({ page }) => {
    const { calls } = await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-session-create-button").click();
    await page.getByTestId("chat-session-create-name").fill("tagged-session");
    // Sloppy input on purpose: the field is comma separated and must be trimmed, with blanks dropped.
    await page.getByTestId("chat-session-create-tags").fill(" alpha ,beta,  , gamma ");
    await page.getByTestId("chat-session-create-save").click();

    const posts = () => matching(calls, "POST", /\/chat-sessions(\?|$)/);
    await expect.poll(() => posts().length, { timeout: 5_000 }).toBe(1);
    expect(posts()[0].body.tags).toEqual(["alpha", "beta", "gamma"]);

    // ...and they come back as chips on the new row.
    const row = page.getByTestId("chat-session-row-tagged-session");
    await expect(row).toBeVisible({ timeout: 5_000 });
    await expect(row.getByText("alpha", { exact: true })).toBeVisible();
    await expect(row.getByText("gamma", { exact: true })).toBeVisible();
  });

  test("the my-sessions tab re-queries with scope=mine and drops other users' sessions", async ({ page }) => {
    const { calls } = await installMocks(page);
    await login(page);
    await openSessionsView(page);

    const listCalls = (scope: string) => matching(calls, "GET", new RegExp(`/chat-sessions\\?scope=${scope}`));

    // Default tab is "mine": the foreign published session is not ours, so it must not be listed.
    await expect(page.getByTestId("chat-session-row-shared-pipeline")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("chat-session-row-foreign-session")).toBeHidden();

    await page.getByTestId("chat-sessions-library-tab").click();
    await expect(page.getByTestId("chat-session-row-foreign-session")).toBeVisible({ timeout: 5_000 });

    // Switching back must issue a *new* scope=mine request. Filtering the already-loaded published
    // page client-side would render the same rows here and still be wrong.
    const before = listCalls("mine").length;
    await page.getByTestId("chat-sessions-mine-tab").click();
    await expect.poll(() => listCalls("mine").length, { timeout: 5_000 }).toBeGreaterThan(before);

    await expect(page.getByTestId("chat-session-row-foreign-session")).toBeHidden({ timeout: 5_000 });
    await expect(page.getByTestId("chat-session-row-empty-workspace")).toBeVisible();
  });

  test("an edited description is saved from the detail view", async ({ page }) => {
    const { calls, sessions } = await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-session-row-shared-pipeline").click();
    await expect(page.getByTestId("chat-session-description-input")).toHaveValue(
      "A published coding session that tags beach footage", { timeout: 5_000 });

    await page.getByTestId("chat-session-description-input").fill("Re-tags drone footage nightly");
    await page.getByTestId("chat-session-save").click();

    const saves = () => matching(calls, "POST", /\/chat-sessions\/session-shared(\?|$)/);
    await expect.poll(() => saves().length, { timeout: 5_000 }).toBe(1);
    expect(saves()[0].body.description).toBe("Re-tags drone footage nightly");
    expect(sessions.find(s => s.uuid === "session-shared")?.description).toBe("Re-tags drone footage nightly");

    // The reload after the save shows the persisted value, and so does the list.
    await expect(page.getByTestId("chat-session-description-input")).toHaveValue("Re-tags drone footage nightly");
    await page.getByRole("button", { name: "Chat Sessions" }).first().click();
    await expect(page.getByText("Re-tags drone footage nightly")).toBeVisible({ timeout: 5_000 });
  });

  test("a tag added on the detail view is saved with the session", async ({ page }) => {
    const { calls, sessions } = await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-session-row-shared-pipeline").click();
    await expect(page.getByTestId("chat-session-tags-input")).toHaveValue("video, pipeline", { timeout: 5_000 });

    await page.getByTestId("chat-session-tags-input").fill("video, pipeline, drone");
    await page.getByTestId("chat-session-save").click();

    const saves = () => matching(calls, "POST", /\/chat-sessions\/session-shared(\?|$)/);
    await expect.poll(() => saves().length, { timeout: 5_000 }).toBe(1);
    expect(saves()[0].body.tags).toEqual(["video", "pipeline", "drone"]);
    expect(sessions.find(s => s.uuid === "session-shared")?.tags).toEqual(["video", "pipeline", "drone"]);

    await expect(page.getByTestId("chat-session-tags-input")).toHaveValue("video, pipeline, drone");
  });

  test("saving the context is a separate write from saving the details", async ({ page }) => {
    const { calls, sessions } = await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-session-row-shared-pipeline").click();

    // The context editor offers the *other* published sessions.
    const historyBox = page.getByTestId(`chat-session-ctx-${FOREIGN_UUID}-history`);
    await expect(historyBox).toBeVisible({ timeout: 5_000 });
    await historyBox.check();
    await page.getByTestId("chat-session-ctx-save").click();

    const ctxWrites = () => matching(calls, "PUT", /\/chat-sessions\/session-shared\/context$/);
    await expect.poll(() => ctxWrites().length, { timeout: 5_000 }).toBe(1);
    expect(ctxWrites()[0].body.refs).toHaveLength(1);
    expect(ctxWrites()[0].body.refs[0]).toMatchObject({
      sourceSessionUuid: FOREIGN_UUID,
      includeChatHistory: true,
      includeSkills: false,
      includeFilesystem: false,
    });
    expect(sessions.find(s => s.uuid === "session-shared")?.contextRefs).toHaveLength(1);

    // It must NOT piggyback on the details route — that write is a different button.
    expect(matching(calls, "POST", /\/chat-sessions\/session-shared(\?|$)/)).toHaveLength(0);

    // After the reload the checkbox is still ticked, i.e. the refs came back from the server.
    await expect(historyBox).toBeChecked({ timeout: 5_000 });
  });

  test("the files panel lists the session workspace", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-session-row-shared-pipeline").click();

    const panel = page.getByTestId("session-files-panel");
    await expect(panel).toBeVisible({ timeout: 5_000 });
    await expect(panel.getByTestId("session-files-list")).toBeVisible();
    await expect(panel.getByTestId("session-files-empty")).toBeHidden();

    // Files show a rounded KB size, directories are marked with a trailing slash.
    await expect(panel.getByTestId("session-file-report.py")).toContainText("2 KB");
    await expect(panel.getByTestId("session-file-data.csv")).toContainText("50 KB");
    await expect(panel.getByTestId("session-file-out")).toContainText("out/");
  });

  test("a session with an empty workspace says it has no files", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSessionsView(page);

    await page.getByTestId("chat-session-row-empty-workspace").click();

    const panel = page.getByTestId("session-files-panel");
    await expect(panel).toBeVisible({ timeout: 5_000 });
    await expect(panel.getByTestId("session-files-empty")).toBeVisible();
    await expect(panel.getByTestId("session-files-list")).toBeHidden();

    // An empty-but-live workspace must not be reported as a missing sandbox.
    await expect(panel.getByTestId("session-files-empty")).toHaveText("No files in the session workspace.");
  });
});
