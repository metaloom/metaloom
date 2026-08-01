import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the inline pipeline diagram (CHAT.md §6.1): a `get_pipeline` tool result carries a
 * `pipeline-graph` visual, and the chat draws it as a compact card in the transcript — live while
 * streaming and again after the session is reloaded.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_UUID = "44444444-4444-4444-4444-444444444444";

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

const GRAPH = {
  pipelineUuid: PIPELINE_UUID,
  name: "Media Transcription",
  description: "Transcribes speech in audio and video with Whisper.",
  enabled: true,
  versionNumber: 3,
  nodes: [
    { id: "pn1", kind: "filesystem-source", label: "Media Source", category: "SOURCE" },
    { id: "pn2", kind: "filter-mimetype", label: "Audio/Video Filter", category: "FILTER" },
    { id: "pn3", kind: "whisper", label: "Transcribe", category: "ANALYSIS" },
    { id: "pn4", kind: "sentiment", label: "Transcript Sentiment", category: "ANALYSIS" },
  ],
  edges: [
    { source: "pn1", sourcePort: "media", target: "pn2", targetPort: "media" },
    { source: "pn2", sourcePort: "media", target: "pn3", targetPort: "video", branch: "PASS" },
    { source: "pn3", sourcePort: "transcript", target: "pn4", targetPort: "text" },
  ],
};

const VISUAL = { type: "pipeline-graph", uuid: PIPELINE_UUID, label: "Media Transcription", payload: GRAPH };
const ANSWER_MD = "The **Media Transcription** pipeline runs Whisper over audio and video.";

function agentRunEvents(chatUuid: string): Array<[string, unknown]> {
  return [
    ["agent_start", { chatUuid, model: "test-model", maxTurns: 8 }],
    ["turn_start", { turn: 1 }],
    ["tool_start", { turn: 1, toolCallId: "c1", name: "get_pipeline", args: { pipelineId: "media transcription" } }],
    ["tool_end", {
      turn: 1, toolCallId: "c1", name: "get_pipeline", isError: false,
      summary: "Pipeline: Media Transcription",
      references: [{ type: "pipeline", uuid: PIPELINE_UUID, label: "Media Transcription" }],
      visuals: [VISUAL],
    }],
    ["turn_end", { turn: 1 }],
    ["turn_start", { turn: 2 }],
    ["text_delta", { turn: 2, text: ANSWER_MD }],
    ["turn_end", { turn: 2 }],
    ["message_end", {
      message: {
        id: "m-assistant-1", role: "assistant", content: ANSWER_MD,
        toolCalls: [{ id: "c1", name: "get_pipeline", resultSummary: "Pipeline: Media Transcription", isError: false, durationMs: 9 }],
        references: [{ type: "pipeline", uuid: PIPELINE_UUID, label: "Media Transcription" }],
        visuals: [VISUAL],
        createdAt: new Date().toISOString(),
      },
    }],
    ["title", { title: "Transcription pipeline" }],
    ["agent_end", { chatUuid, status: "completed" }],
  ];
}

async function installMocks(page: Page) {
  const chats: StoredChat[] = [];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

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
  await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, route => {
    const chatUuid = route.request().url().split("/chats/")[1].split("/")[0];
    const body = JSON.parse(route.request().postData() || "{}");
    const events = agentRunEvents(chatUuid);
    const chat = chats.find(c => c.uuid === chatUuid);
    if (chat) {
      chat.messages.push({ id: `m-user-${++seq}`, role: "user", content: body.message, createdAt: new Date().toISOString() });
      const messageEnd = events.find(([t]) => t === "message_end");
      if (messageEnd) chat.messages.push((messageEnd[1] as { message: Record<string, unknown> }).message);
      chat.title = "Transcription pipeline";
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
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Chat pipeline visualization – mocked e2e", () => {
  test("a get_pipeline tool result renders as a compact graph in the transcript", async ({ page }) => {
    await installMocks(page);
    await login(page);

    const input = page.getByPlaceholder(/Ask about assets/i);
    await expect(input).toBeVisible({ timeout: 10_000 });
    await input.fill("show me the current pipeline for media transcription");
    await input.press("Enter");

    const card = page.getByTestId("chat-pipeline-graph").first();
    await expect(card).toBeVisible({ timeout: 10_000 });
    await expect(card).toHaveAttribute("data-pipeline-uuid", PIPELINE_UUID);

    // Header: name and version of the graph that was returned
    await expect(card).toContainText("Media Transcription");
    await expect(card).toContainText("v3");

    // One box per node, drawn in graph order with its kind
    const nodes = card.getByTestId("chat-pipeline-graph-node");
    await expect(nodes).toHaveCount(4);
    await expect(nodes.nth(0)).toContainText("Media Source");
    await expect(nodes.nth(0)).toContainText("filesystem-source");
    await expect(nodes.nth(3)).toContainText("Transcript Sentiment");

    // One connector per edge, and the branch label of the filter edge
    const edges = card.getByTestId("chat-pipeline-graph-edges");
    await expect(edges.locator("path")).toHaveCount(3);
    await expect(edges.locator("text")).toHaveText(["PASS"]);

    // The layout runs left to right: each node sits right of the previous one
    const boxes = await nodes.all();
    const xs = await Promise.all(boxes.map(async b => (await b.boundingBox())!.x));
    expect([...xs].sort((a, b) => a - b)).toEqual(xs);

    // The answer text is rendered alongside the diagram, not replaced by it
    await expect(page.getByTestId("markdown-content").filter({ hasText: "Media Transcription" }).last()).toBeVisible();

    // Persistence round-trip: the diagram comes back with the reloaded session
    await page.getByText("New chat").click();
    await expect(page.getByTestId("chat-pipeline-graph")).toHaveCount(0);
    await page.getByText("Transcription pipeline").click();
    await expect(page.getByTestId("chat-pipeline-graph").first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-pipeline-graph-node")).toHaveCount(4);
  });

  test("the card links into the pipeline editor", async ({ page }) => {
    await installMocks(page);
    await login(page);

    const input = page.getByPlaceholder(/Ask about assets/i);
    await expect(input).toBeVisible({ timeout: 10_000 });
    await input.fill("show me the transcription pipeline");
    await input.press("Enter");

    await expect(page.getByTestId("chat-pipeline-graph").first()).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("chat-pipeline-graph-open").first().click();
    await expect(page).toHaveURL(/\/pipelines$/);
  });
});
