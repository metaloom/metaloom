import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the chat-sessions feature — management view (create/load/publish/delete),
 * the published library tab, and loading a session's coding-workspace files. All `/api/v1/**`
 * calls are intercepted; sessions live in a small in-memory store.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const SHARED_CHAT_UUID = "chat-shared-1";

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

async function installMocks(page: Page) {
  const sessions: StoredSession[] = [sharedSession()];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // Collection endpoint: POST creates, GET lists (filtered by ?scope=mine|published).
  await page.route(/\/api\/v1\/chat-sessions(\?.*)?$/, route => {
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
    const url = route.request().url();
    const published = /scope=published/.test(url);
    const data = published ? sessions.filter(s => s.published) : sessions;
    return json(route, { data });
  });

  // Session files proxy (Phase-2). Register before the generic item route (LIFO -> later = higher prio).
  await page.route(/\/api\/v1\/sessions\/[^/]+\/files(\?.*)?$/, route =>
    json(route, {
      entries: [
        { name: "report.py", type: "file", size: 2048 },
        { name: "out", type: "dir", size: 0 },
        { name: "data.csv", type: "file", size: 51200 },
      ],
    }));

  // Item sub-routes: publish / unpublish / context (must be registered AFTER the generic item route).
  await page.route(/\/api\/v1\/chat-sessions\/[^/]+\/(publish|unpublish)$/, route => {
    const parts = route.request().url().split("/chat-sessions/")[1].split("/");
    const uuid = parts[0];
    const action = parts[1].split("?")[0];
    const found = sessions.find(s => s.uuid === uuid);
    if (found) found.published = action === "publish";
    return json(route, found ?? {}, found ? 200 : 404);
  });
  await page.route(/\/api\/v1\/chat-sessions\/[^/]+\/context$/, route => {
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

  return { sessions };
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
});
