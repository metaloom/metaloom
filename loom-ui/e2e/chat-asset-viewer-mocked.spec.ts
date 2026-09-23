import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the two asset visuals of the chat (CHAT.md §6.2, §6.3).
 *
 * The chat could name an asset and never show one: references rendered as chips, and the workspace
 * panel beside the transcript listed the newest rows of the catalogue whatever the conversation was
 * about. Two things are pinned here — a `show_asset` result becomes a real embedded player, and a
 * search result set drives the panel, so both halves of the screen are about the same assets.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const EPISODE = "22222222-2222-2222-2222-222222222222";
const CRANE = "33333333-3333-3333-3333-333333333333";

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

const VIEWER_VISUAL = {
  type: "asset-viewer",
  uuid: EPISODE,
  label: "sg1-s03e17.mkv",
  payload: {
    assetUuid: EPISODE,
    filename: "sg1-s03e17.mkv",
    mimeType: "video/x-matroska",
    kind: "video",
    size: 1_500_000_000,
    startSeconds: 604.5,
    caption: "where Atlantis is first mentioned",
  },
};

const RESULTS_VISUAL = {
  type: "asset-results",
  uuid: "99999999-9999-9999-9999-999999999999",
  label: "Atlantis",
  payload: {
    query: "Atlantis",
    criteria: "spoken content",
    total: 35,
    totalExact: true,
    items: [
      { uuid: EPISODE, title: "sg1-s03e17.mkv", mimeType: "video/x-matroska", score: 0.9, timeFromMs: 604_500, snippet: "the Atlantis expedition" },
      { uuid: CRANE, title: "harbour-crane.jpg", mimeType: "image/jpeg", score: 0.4 },
    ],
  },
};

const VIEWER_ANSWER = "That mention is at **10:04** of the third-season episode.";
const SEARCH_ANSWER = "Atlantis comes up in **two** places.";

function runEvents(chatUuid: string, tool: "show_asset" | "search_transcript"): Array<[string, unknown]> {
  const visual = tool === "show_asset" ? VIEWER_VISUAL : RESULTS_VISUAL;
  const answer = tool === "show_asset" ? VIEWER_ANSWER : SEARCH_ANSWER;
  return [
    ["agent_start", { chatUuid, model: "test-model", maxTurns: 8 }],
    ["turn_start", { turn: 1 }],
    ["tool_start", { turn: 1, toolCallId: "c1", name: tool, args: {} }],
    ["tool_end", {
      turn: 1, toolCallId: "c1", name: tool, isError: false,
      summary: "ok",
      references: [{ type: "asset", uuid: EPISODE, label: "sg1-s03e17.mkv" }],
      visuals: [visual],
    }],
    ["turn_end", { turn: 1 }],
    ["turn_start", { turn: 2 }],
    ["text_delta", { turn: 2, text: answer }],
    ["turn_end", { turn: 2 }],
    ["message_end", {
      message: {
        id: `m-assistant-${tool}`, role: "assistant", content: answer,
        references: [{ type: "asset", uuid: EPISODE, label: "sg1-s03e17.mkv" }],
        visuals: [visual],
        createdAt: new Date().toISOString(),
      },
    }],
    ["title", { title: tool === "show_asset" ? "The Atlantis mention" : "Atlantis references" }],
    ["agent_end", { chatUuid, status: "completed" }],
  ];
}

function assetResponse(uuid: string) {
  return uuid === EPISODE
    ? { uuid, file: { filename: "sg1-s03e17.mkv", mimeType: "video/x-matroska", size: 1_500_000_000 }, tags: [] }
    : { uuid, file: { filename: "harbour-crane.jpg", mimeType: "image/jpeg", size: 240_000 }, tags: [] };
}

async function installMocks(page: Page, tool: "show_asset" | "search_transcript") {
  const chats: StoredChat[] = [];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  // Media routes the embedded player needs. None of the bytes have to be decodable — what is under
  // test is that the viewer is built and asks for the right asset.
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-info$/, route =>
    json(route, { duration: 2580, frameRate: 23.976, width: 1920, height: 1080, videoCodec: "h264", audioCodec: "ac3", streamable: true }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/media-token$/, route =>
    json(route, { token: "fake-media-token", expiresIn: 600 }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/poster/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: Buffer.from("") }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream/, route =>
    route.fulfill({ status: 200, contentType: "video/mp4", body: Buffer.from("") }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/stream-start/, route => {
    const requested = Number(new URL(route.request().url()).searchParams.get("t") ?? "0");
    return json(route, { requested, start: requested });
  });
  await page.route(/\/api\/v1\/assets\/[0-9a-f-]{36}$/, route =>
    json(route, assetResponse(route.request().url().split("/assets/")[1].split("?")[0])));

  await page.route(/\/api\/v1\/chats$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredChat = { uuid: `chat-${++seq}`, title: body.title ?? "chat", messages: body.messages ?? [] };
      chats.unshift(created);
      return json(route, created, 201);
    }
    return json(route, { data: chats });
  });

  // Registered before the generic /chats/:uuid matcher, which would otherwise swallow it.
  await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, route => {
    const chatUuid = route.request().url().split("/chats/")[1].split("/")[0];
    const body = JSON.parse(route.request().postData() || "{}");
    const events = runEvents(chatUuid, tool);
    const chat = chats.find(c => c.uuid === chatUuid);
    if (chat) {
      chat.messages.push({ id: `m-user-${++seq}`, role: "user", content: body.message, createdAt: new Date().toISOString() });
      const messageEnd = events.find(([t]) => t === "message_end");
      if (messageEnd) chat.messages.push((messageEnd[1] as { message: Record<string, unknown> }).message);
      chat.title = tool === "show_asset" ? "The Atlantis mention" : "Atlantis references";
    }
    return route.fulfill({ status: 200, contentType: "text/event-stream", body: sse(events) });
  });

  await page.route(/\/api\/v1\/chats\/[^/]+$/, route => {
    const uuid = route.request().url().split("/chats/")[1].split("?")[0];
    return json(route, chats.find(c => c.uuid === uuid) ?? {});
  });
}

async function login(page: Page) {
  await page.goto("/");
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function ask(page: Page, text: string) {
  const input = page.getByPlaceholder(/Ask about assets/i);
  await expect(input).toBeVisible({ timeout: 10_000 });
  await input.fill(text);
  await input.press("Enter");
}

test.describe("Chat asset viewer – mocked e2e", () => {
  test("a show_asset result embeds a real player in the transcript", async ({ page }) => {
    await installMocks(page, "show_asset");
    await login(page);
    await ask(page, "show me where Atlantis is mentioned");

    const card = page.getByTestId("chat-asset-viewer").first();
    await expect(card).toBeVisible({ timeout: 10_000 });
    await expect(card).toHaveAttribute("data-asset-uuid", EPISODE);
    await expect(card.getByTestId("chat-asset-viewer-name")).toHaveText("sg1-s03e17.mkv");

    // The position the model asked for, shown and carried — not a player that opens at the top of a
    // 43-minute episode when the answer is about minute ten.
    await expect(card.getByTestId("chat-asset-viewer-start")).toHaveText("10:04");
    await expect(card.getByTestId("chat-asset-viewer-caption")).toContainText("Atlantis is first mentioned");

    // The embedded player, not a thumbnail: the same component the asset detail view uses.
    await expect(card.getByTestId("chat-asset-video")).toBeVisible();

    // The answer text is rendered alongside the player, not replaced by it
    await expect(page.getByTestId("markdown-content").filter({ hasText: "10:04" }).last()).toBeVisible();
  });

  test("the viewer links into the asset view at the position it opened", async ({ page }) => {
    await installMocks(page, "show_asset");
    await login(page);
    await ask(page, "show me the episode");

    await expect(page.getByTestId("chat-asset-viewer").first()).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("chat-asset-viewer-open").first().click();
    await expect(page).toHaveURL(new RegExp(`/assets/${EPISODE}\\?t=604.5$`));
  });

  test("a search result set drives the strip and the workspace panel together", async ({ page }) => {
    await installMocks(page, "search_transcript");
    await login(page);
    await ask(page, "where is Atlantis mentioned?");

    // Inline: a receipt for what the agent looked at
    const strip = page.getByTestId("chat-asset-results").first();
    await expect(strip).toBeVisible({ timeout: 10_000 });
    await expect(strip.getByTestId("chat-asset-result")).toHaveCount(2);
    await expect(strip.getByTestId("chat-asset-results-count")).toHaveText("2 of 35");

    // Beside it: the panel switched to the same result set by itself. Before this it listed the
    // newest assets in the catalogue no matter what the conversation was about.
    const panel = page.getByTestId("chat-results-panel");
    await expect(panel).toBeVisible();
    const rows = panel.getByTestId("chat-result-row");
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText("sg1-s03e17.mkv");
    await expect(rows.nth(0)).toContainText("the Atlantis expedition");
    await expect(rows.nth(1)).toContainText("harbour-crane.jpg");
    await expect(page.getByTestId("chat-tab-results")).toContainText("2");
  });

  test("picking a result opens it in the panel at the moment it matched", async ({ page }) => {
    await installMocks(page, "search_transcript");
    await login(page);
    await ask(page, "where is Atlantis mentioned?");

    await expect(page.getByTestId("chat-results-panel")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("chat-result-row").first().click();

    const viewer = page.getByTestId("chat-panel-asset-viewer");
    await expect(viewer).toBeVisible();
    await expect(viewer).toHaveAttribute("data-asset-uuid", EPISODE);
    // 604500 ms is the transcript hit's own offset, carried from the search into the player.
    await expect(viewer.getByTestId("chat-asset-viewer-start")).toHaveText("10:04");
  });

  test("the panel belongs to the conversation and is restored with it", async ({ page }) => {
    await installMocks(page, "search_transcript");
    await login(page);
    await ask(page, "where is Atlantis mentioned?");
    await expect(page.getByTestId("chat-results-panel")).toBeVisible({ timeout: 10_000 });

    // A new conversation is not about that search any more
    await page.getByText("New chat").click();
    await expect(page.getByTestId("chat-results-panel")).toHaveCount(0);
    await expect(page.getByTestId("chat-tab-results")).toHaveCount(0);

    // Reopening it puts the panel back, off the persisted visual rather than a second search
    await page.getByText("Atlantis references").click();
    await expect(page.getByTestId("chat-results-panel")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-result-row")).toHaveCount(2);
  });
});
