import { test, expect, Page, Route } from "@playwright/test";

/**
 * What the chat workspace does with the stream events that are not the answer itself — `error`,
 * an aborted `agent_end`, `title` — plus the conversation rail's delete, the markdown the answer
 * renders as, and the reasoning section's live/settled states.
 *
 * Whole streams are fulfilled with `page.route`. The reasoning tests need a stream that has said
 * something and is still open, which `page.route` cannot script; {@link installControllableStream}
 * replaces `fetch` for the stream POST with a body the test feeds event by event.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

interface StoredChat {
  uuid: string;
  title: string;
  messages: Record<string, unknown>[];
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function sse(events: Array<[string, unknown]>): string {
  return events.map(([type, data]) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

function message(content: string, extra: Record<string, unknown> = {}) {
  return { message: { id: `m-${Math.random()}`, role: "assistant", content, createdAt: new Date().toISOString(), ...extra } };
}

async function installMocks(page: Page, opts: { chats?: StoredChat[]; streamBody?: string; deleteStatus?: number } = {}) {
  const chats: StoredChat[] = [...(opts.chats ?? [])];
  const deletes: string[] = [];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/chats$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredChat = { uuid: `chat-new-${++seq}`, title: body.title ?? "chat", messages: [] };
      chats.unshift(created);
      return json(route, created, 201);
    }
    return json(route, { data: chats });
  });

  await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, route => {
    if (route.request().method() === "DELETE") return route.fulfill({ status: 204, body: "" });
    return route.fulfill({ status: 200, contentType: "text/event-stream", body: opts.streamBody ?? "" });
  });

  await page.route(/\/api\/v1\/chats\/[^/]+$/, route => {
    const uuid = route.request().url().split("/chats/")[1].split("?")[0];
    if (route.request().method() === "DELETE") {
      deletes.push(uuid);
      if (opts.deleteStatus && opts.deleteStatus >= 400) return json(route, { message: "no" }, opts.deleteStatus);
      const index = chats.findIndex(c => c.uuid === uuid);
      if (index >= 0) chats.splice(index, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    const found = chats.find(c => c.uuid === uuid);
    return json(route, found ?? {}, found ? 200 : 404);
  });

  return { deletes };
}

/**
 * Replace fetch for the stream POST with a body the test writes to through `window.__stream`.
 * `push(type, data)` emits one SSE event; `close()` ends the response.
 */
async function installControllableStream(page: Page) {
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (/\/chats\/[^/]+\/stream$/.test(url) && (init?.method ?? "GET").toUpperCase() === "POST") {
        const encoder = new TextEncoder();
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            (window as unknown as { __stream: unknown }).__stream = {
              push: (type: string, data: unknown) =>
                controller.enqueue(encoder.encode(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`)),
              close: () => controller.close(),
            };
          },
        });
        return Promise.resolve(new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } }));
      }
      return original(input, init);
    };
  });
}

async function push(page: Page, type: string, data: unknown) {
  await page.waitForFunction(() => !!(window as unknown as { __stream?: unknown }).__stream);
  await page.evaluate(([t, d]) => (window as unknown as { __stream: { push: (t: string, d: unknown) => void } }).__stream.push(t as string, d), [type, data] as const);
}

async function closeStream(page: Page) {
  await page.evaluate(() => (window as unknown as { __stream: { close: () => void } }).__stream.close());
}

async function login(page: Page) {
  await page.goto("/");
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function send(page: Page, text: string) {
  const input = page.getByPlaceholder(/Ask about assets/i);
  await expect(input).toBeVisible({ timeout: 10_000 });
  await input.fill(text);
  await input.press("Enter");
}

/** The toast carrying `text`. Toasts are filled MUI Alerts; the severity is in the class name. */
function toast(page: Page, text: string) {
  return page.getByRole("alert").filter({ hasText: text });
}

test.describe("Chat workspace stream events – mocked", () => {
  test("a terminal error event raises the generic failure toast as an error", async ({ page }) => {
    await installMocks(page, {
      streamBody: sse([
        ["error", { code: "LLM_UNAVAILABLE", message: "upstream 502 from the model host", terminal: true }],
        ["agent_end", { chatUuid: "chat-new-1", status: "error" }],
      ]),
    });
    await login(page);
    await send(page, "what is in the library");

    const alert = toast(page, "The agent request failed");
    await expect(alert).toBeVisible({ timeout: 10_000 });
    await expect(alert).toHaveClass(/MuiAlert-filledError/);
    // The raw upstream detail is not what a terminal failure shows.
    await expect(page.getByText("upstream 502 from the model host")).toHaveCount(0);
    // The question stays: the stream answered, it just answered with a failure.
    await expect(page.getByTestId("chat-column").getByText("what is in the library", { exact: true })).toBeVisible();
  });

  test("a non-terminal error event shows the server's message as a warning", async ({ page }) => {
    await installMocks(page, {
      streamBody: sse([
        ["error", { code: "TOOL_FAILED", message: "The search index is rebuilding", terminal: false }],
        ["text_delta", { turn: 1, text: "Here is what I found anyway." }],
        ["message_end", message("Here is what I found anyway.")],
        ["agent_end", { chatUuid: "chat-new-1", status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "find the beach clip");

    const alert = toast(page, "The search index is rebuilding");
    await expect(alert).toBeVisible({ timeout: 10_000 });
    await expect(alert).toHaveClass(/MuiAlert-filledWarning/);
    await expect(toast(page, "The agent request failed")).toHaveCount(0);
    await expect(page.getByTestId("markdown-content").filter({ hasText: "Here is what I found anyway." })).toBeVisible();
  });

  test("an aborted agent_end says generation stopped and keeps the partial answer", async ({ page }) => {
    await installMocks(page, {
      streamBody: sse([
        ["text_delta", { turn: 1, text: "PARTIAL-BEFORE-ABORT" }],
        ["agent_end", { chatUuid: "chat-new-1", status: "aborted" }],
      ]),
    });
    await login(page);
    await send(page, "a long question");

    const alert = toast(page, "Generation stopped");
    await expect(alert).toBeVisible({ timeout: 10_000 });
    await expect(alert).toHaveClass(/MuiAlert-filledInfo/);
    await expect(page.getByTestId("markdown-content").filter({ hasText: "PARTIAL-BEFORE-ABORT" })).toBeVisible();
  });

  test("a completed agent_end raises no toast at all", async ({ page }) => {
    await installMocks(page, {
      streamBody: sse([
        ["text_delta", { turn: 1, text: "All done." }],
        ["message_end", message("All done.")],
        ["agent_end", { chatUuid: "chat-new-1", status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "quick question");

    await expect(page.getByTestId("markdown-content").filter({ hasText: "All done." })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("alert")).toHaveCount(0);
  });

  test("a title event renames the open conversation in the rail", async ({ page }) => {
    await installMocks(page, {
      chats: [{ uuid: "chat-old", title: "Older conversation", messages: [] }],
      streamBody: sse([
        ["title", { title: "Beach footage search" }],
        ["text_delta", { turn: 1, text: "Found it." }],
        ["message_end", message("Found it.")],
        ["agent_end", { chatUuid: "chat-new-1", status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "where is the beach clip from last summer");

    const row = page.getByTestId("chat-rail-session-chat-new-1");
    await expect(row).toHaveText("Beach footage search", { timeout: 10_000 });
    // Only the conversation the event belongs to is renamed.
    await expect(page.getByTestId("chat-rail-session-chat-old")).toHaveText("Older conversation");
  });
});

test.describe("Chat rail – deleting a conversation", () => {
  const seeded = (): StoredChat[] => [
    { uuid: "chat-a", title: "Alpha conversation", messages: [
      { id: "a1", role: "assistant", content: "ALPHA-CONTENT", createdAt: new Date().toISOString() },
    ] },
    { uuid: "chat-b", title: "Beta conversation", messages: [] },
  ];

  test("the delete button is labelled, sends DELETE for that chat and drops only its row", async ({ page }) => {
    const { deletes } = await installMocks(page, { chats: seeded() });
    await login(page);

    const row = page.getByTestId("chat-rail-session-chat-b");
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.hover();
    await row.getByRole("button", { name: "Delete chat", exact: true }).click();

    await expect.poll(() => deletes).toEqual(["chat-b"]);
    await expect(row).toHaveCount(0);
    await expect(page.getByTestId("chat-rail-session-chat-a")).toBeVisible();
  });

  test("deleting the open conversation clears the transcript", async ({ page }) => {
    const { deletes } = await installMocks(page, { chats: seeded() });
    await login(page);

    await page.getByTestId("chat-rail-session-chat-a").click();
    await expect(page.getByText("ALPHA-CONTENT")).toBeVisible({ timeout: 10_000 });

    const row = page.getByTestId("chat-rail-session-chat-a");
    await row.hover();
    await row.getByRole("button", { name: "Delete chat", exact: true }).click();

    await expect.poll(() => deletes).toEqual(["chat-a"]);
    await expect(page.getByText("ALPHA-CONTENT")).toHaveCount(0);
    await expect(row).toHaveCount(0);
  });

  test("a refused delete keeps the row", async ({ page }) => {
    const { deletes } = await installMocks(page, { chats: seeded(), deleteStatus: 500 });
    await login(page);

    const row = page.getByTestId("chat-rail-session-chat-b");
    await row.hover();
    await row.getByRole("button", { name: "Delete chat", exact: true }).click();

    await expect.poll(() => deletes).toEqual(["chat-b"]);
    await expect(row).toBeVisible();
  });
});

test.describe("Chat answer markdown", () => {
  test("a GFM table renders as a table and a fenced block as pre > code", async ({ page }) => {
    const answer = [
      "Here are the counts:",
      "",
      "| Library | Assets |",
      "| ------- | -----: |",
      "| Main    | 42     |",
      "| Archive | 7      |",
      "",
      "```ts",
      "const total = 42 + 7;",
      "```",
      "",
      "Inline `code` stays inline.",
    ].join("\n");
    await installMocks(page, {
      streamBody: sse([
        ["text_delta", { turn: 1, text: answer }],
        ["message_end", message(answer)],
        ["agent_end", { chatUuid: "chat-new-1", status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "how many assets per library");

    const md = page.getByTestId("markdown-content").filter({ hasText: "Here are the counts:" }).last();
    await expect(md).toBeVisible({ timeout: 10_000 });

    const table = md.locator("table");
    await expect(table).toHaveCount(1);
    await expect(table.locator("thead th")).toHaveText(["Library", "Assets"]);
    await expect(table.locator("tbody tr")).toHaveCount(2);
    await expect(table.locator("tbody tr").nth(0).locator("td")).toHaveText(["Main", "42"]);
    await expect(table.locator("tbody tr").nth(1).locator("td")).toHaveText(["Archive", "7"]);
    // No pipe characters leaking through as text means the table syntax was parsed.
    await expect(md).not.toContainText("| Library");

    const block = md.locator("pre > code");
    await expect(block).toHaveCount(1);
    await expect(block).toHaveText("const total = 42 + 7;");
    await expect(block).toHaveClass(/language-ts/);
    // The inline span is a code element, but not inside a pre.
    await expect(md.locator("p code")).toHaveText("code");
  });
});

test.describe("Chat reasoning section", () => {
  test("streams as a live indicator with a running timer, then settles into a collapsed toggle", async ({ page }) => {
    await installControllableStream(page);
    await installMocks(page);
    await login(page);
    await send(page, "think hard about this");

    await push(page, "reasoning_delta", { turn: 1, text: "SECRET-CHAIN-OF-THOUGHT" });

    const indicator = page.getByTestId("chat-reasoning-indicator");
    await expect(indicator).toBeVisible({ timeout: 10_000 });
    await expect(indicator).toHaveText(/thinking… \(\d+s\)/);
    // The timer advances while the model is still reasoning.
    await expect(indicator).toHaveText(/thinking… \([1-9]\d*s\)/, { timeout: 5_000 });
    // The reasoning text itself is hidden while it streams, until the user explicitly opens it.
    await expect(page.getByText("SECRET-CHAIN-OF-THOUGHT")).toBeHidden();
    await indicator.click();
    await expect(page.getByText("SECRET-CHAIN-OF-THOUGHT")).toBeVisible();
    // More reasoning arrives into the opened section.
    await push(page, "reasoning_delta", { turn: 1, text: " AND-MORE" });
    await expect(page.getByTestId("chat-reasoning-content")).toContainText("SECRET-CHAIN-OF-THOUGHT AND-MORE");

    // The answer starts: reasoning is over.
    await push(page, "text_delta", { turn: 1, text: "The answer." });
    await push(page, "message_end", message("The answer.", { reasoning: "SECRET-CHAIN-OF-THOUGHT" }));
    await push(page, "agent_end", { chatUuid: "chat-new-1", status: "completed" });
    await closeStream(page);

    await expect(page.getByTestId("markdown-content").filter({ hasText: "The answer." })).toBeVisible({ timeout: 10_000 });
    await expect(indicator).toHaveCount(0);
    const toggle = page.getByTestId("chat-reasoning-toggle");
    await expect(toggle).toHaveText("Show reasoning");
    await expect(page.getByText("SECRET-CHAIN-OF-THOUGHT")).toBeHidden();

    await toggle.click();
    await expect(toggle).toHaveText("Hide reasoning");
    await expect(page.getByTestId("chat-reasoning-content")).toContainText("SECRET-CHAIN-OF-THOUGHT");
    await expect(page.getByText("SECRET-CHAIN-OF-THOUGHT")).toBeVisible();

    await toggle.click();
    await expect(toggle).toHaveText("Show reasoning");
    await expect(page.getByText("SECRET-CHAIN-OF-THOUGHT")).toBeHidden();
  });

  test("an answer without reasoning leaves no reasoning toggle behind", async ({ page }) => {
    await installMocks(page, {
      streamBody: sse([
        ["text_delta", { turn: 1, text: "Plain answer." }],
        ["message_end", message("Plain answer.")],
        ["agent_end", { chatUuid: "chat-new-1", status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "a simple one");

    await expect(page.getByTestId("markdown-content").filter({ hasText: "Plain answer." })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-reasoning-toggle")).toHaveCount(0);
    await expect(page.getByTestId("chat-reasoning-indicator")).toHaveCount(0);
  });
});
