import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the chat agent streaming UI — no running Loom backend and no
 * LLM required. The stream route is fulfilled with a scripted SSE event body
 * (matching the backend wire format, CHAT.md §4.2); chat session CRUD runs
 * against a small in-memory store so persistence round-trips.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "33333333-3333-3333-3333-333333333333";

interface StoredChat {
  uuid: string;
  title: string;
  messages: Record<string, unknown>[];
  meta?: Record<string, unknown>;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function sse(events: Array<[string, unknown]>): string {
  return events.map(([type, data]) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

const REASONING_TEXT = "The user wants beach footage. I should search the assets.";
const ANSWER_MD = "I found **beach.mp4** for you:\n\n- 4k resolution\n- 42 seconds long";

function agentRunEvents(chatUuid: string): Array<[string, unknown]> {
  return [
    ["agent_start", { chatUuid, model: "test-model", maxTurns: 8 }],
    ["turn_start", { turn: 1 }],
    ["reasoning_delta", { turn: 1, text: "The user wants beach footage. " }],
    ["reasoning_delta", { turn: 1, text: "I should search the assets." }],
    ["tool_start", { turn: 1, toolCallId: "c1", name: "search_assets", args: { query: "beach" } }],
    ["tool_end", {
      turn: 1, toolCallId: "c1", name: "search_assets", isError: false,
      summary: "Found 1 asset",
      references: [{ type: "asset", uuid: ASSET_UUID, label: "beach.mp4" }],
    }],
    ["turn_end", { turn: 1 }],
    ["turn_start", { turn: 2 }],
    ["text_delta", { turn: 2, text: "I found **beach.mp4**" }],
    ["text_delta", { turn: 2, text: " for you:\n\n- 4k resolution" }],
    ["text_delta", { turn: 2, text: "\n- 42 seconds long" }],
    ["turn_end", { turn: 2 }],
    ["message_end", {
      message: {
        id: "m-assistant-1", role: "assistant", content: ANSWER_MD,
        reasoning: REASONING_TEXT,
        toolCalls: [{ id: "c1", name: "search_assets", resultSummary: "Found 1 asset", isError: false, durationMs: 12 }],
        references: [{ type: "asset", uuid: ASSET_UUID, label: "beach.mp4" }],
        createdAt: new Date().toISOString(),
      },
    }],
    ["title", { title: "Beach footage search" }],
    ["agent_end", { chatUuid, status: "completed" }],
  ];
}

async function installMocks(page: Page, opts: { streamStatus?: number } = {}) {
  const chats: StoredChat[] = [];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // Asset detail for the reference chip click
  await page.route(new RegExp(`/api/v1/assets/${ASSET_UUID}$`), route =>
    json(route, { uuid: ASSET_UUID, file: { filename: "beach.mp4", mimeType: "video/mp4", size: 12_000_000 } }));

  // Chat session CRUD against the in-memory store
  await page.route(/\/api\/v1\/chats$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredChat = { uuid: `chat-${++seq}`, title: body.title ?? "chat", messages: body.messages ?? [], meta: body.meta };
      chats.unshift(created);
      return json(route, created, 201);
    }
    return json(route, { data: chats });
  });

  // Stream route MUST be registered before the generic /chats/:uuid matcher
  await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, async route => {
    const chatUuid = route.request().url().split("/chats/")[1].split("/")[0];
    if (opts.streamStatus && opts.streamStatus !== 200) {
      return route.fulfill({ status: opts.streamStatus, contentType: "application/json", body: "{}" });
    }
    const body = JSON.parse(route.request().postData() || "{}");
    // Emulate server-side persistence so reloading the session round-trips
    const chat = chats.find(c => c.uuid === chatUuid);
    const events = agentRunEvents(chatUuid);
    if (chat) {
      chat.messages.push({ id: `m-user-${++seq}`, role: "user", content: body.message, createdAt: new Date().toISOString() });
      const messageEnd = events.find(([t]) => t === "message_end");
      if (messageEnd) chat.messages.push((messageEnd[1] as { message: Record<string, unknown> }).message);
      chat.title = "Beach footage search";
    }
    return route.fulfill({ status: 200, contentType: "text/event-stream", body: sse(events) });
  });

  await page.route(/\/api\/v1\/chats\/[^/]+$/, route => {
    const uuid = route.request().url().split("/chats/")[1].split("?")[0];
    const found = chats.find(c => c.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = chats.findIndex(c => c.uuid === uuid);
      if (idx >= 0) chats.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (found) {
        if (body.title != null) found.title = body.title;
        if (body.meta != null) found.meta = body.meta;
      }
      return json(route, found ?? {});
    }
    return json(route, found ?? {}, found ? 200 : 404);
  });
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function sendChatMessage(page: Page, text: string) {
  const input = page.getByPlaceholder(/Ask about assets/i);
  await expect(input).toBeVisible({ timeout: 10_000 });
  await input.fill(text);
  await input.press("Enter");
}

test.describe("Chat agent – mocked e2e", () => {
  test("streamed answer renders markdown, tools, chips and persists", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await sendChatMessage(page, "find beach videos");

    // Markdown rendered — bold element and list items, no raw ** markers
    const answer = page.getByTestId("markdown-content").filter({ hasText: "beach.mp4" }).last();
    await expect(answer).toBeVisible({ timeout: 10_000 });
    await expect(answer.locator("strong", { hasText: "beach.mp4" })).toBeVisible();
    await expect(answer.locator("li")).toHaveCount(2);
    await expect(answer).not.toContainText("**");

    // Tool action row settled to done with its summary
    await expect(page.getByText("search_assets")).toBeVisible();
    await expect(page.getByText(/Found 1 asset/)).toBeVisible();

    // Reasoning is hidden by default behind a toggle
    await expect(page.getByText(REASONING_TEXT)).toBeHidden();
    const toggle = page.getByTestId("chat-reasoning-toggle");
    await expect(toggle).toBeVisible();
    await toggle.click();
    await expect(page.getByTestId("chat-reasoning-content")).toContainText("beach footage");

    // The auto-title updated the session rail
    await expect(page.getByText("Beach footage search")).toBeVisible();

    // Reference chip renders and opens the asset inline in the workspace panel
    // (target the MUI chip, not the bold "beach.mp4" inside the markdown answer)
    const chip = page.locator(".MuiChip-root", { hasText: "beach.mp4" }).first();
    await expect(chip).toBeVisible();
    await chip.click();
    await expect(page.getByText("video/mp4")).toBeVisible({ timeout: 5_000 });

    // Persistence round-trip: a fresh chat, then reopening the session restores the transcript
    await page.getByText("New chat").click();
    await expect(answer).toBeHidden();
    await page.getByText("Beach footage search").click();
    await expect(page.getByTestId("markdown-content").filter({ hasText: "beach.mp4" }).last()).toBeVisible({ timeout: 10_000 });
    // Reasoning survived persistence and stays hidden by default
    await expect(page.getByTestId("chat-reasoning-toggle")).toBeVisible();
    await expect(page.getByText(REASONING_TEXT)).toBeHidden();
  });

  test("busy agent (409) surfaces a toast and keeps the input", async ({ page }) => {
    await installMocks(page, { streamStatus: 409 });
    await login(page);
    await sendChatMessage(page, "find beach videos");

    await expect(page.getByText(/still working on this chat/i)).toBeVisible({ timeout: 10_000 });
    // The input is restored for a retry
    await expect(page.getByPlaceholder(/Ask about assets/i)).toHaveValue("find beach videos");
  });

  test("stop button is offered while the agent is streaming", async ({ page }) => {
    const chats: StoredChat[] = [];
    let releaseStream: () => void = () => {};
    const streamGate = new Promise<void>(resolve => { releaseStream = resolve; });

    await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
    await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
    await page.route(/\/api\/v1\/chats$/, route => {
      if (route.request().method() === "POST") {
        const created: StoredChat = { uuid: "chat-slow", title: "slow", messages: [] };
        chats.push(created);
        return json(route, created, 201);
      }
      return json(route, { data: chats });
    });
    await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, async route => {
      if (route.request().method() === "DELETE") {
        return route.fulfill({ status: 204, body: "" });
      }
      await streamGate;
      return route.fulfill({ status: 200, contentType: "text/event-stream", body: sse(agentRunEvents("chat-slow")) });
    });
    await page.route(/\/api\/v1\/chats\/[^/]+$/, route => json(route, chats[0] ?? {}));

    await login(page);
    await sendChatMessage(page, "slow question");

    // While the request is pending the send button becomes a stop button
    await expect(page.getByTestId("chat-stop-button")).toBeVisible({ timeout: 10_000 });
    releaseStream();
    await expect(page.getByTestId("chat-stop-button")).toBeHidden({ timeout: 10_000 });
  });
});
