import { afterEach, describe, expect, it, vi } from "vitest";
import { AgentBusyError, cancelChatStream, createSseParser, streamChatMessage } from "./agent";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";
const CHAT_UUID = "11111111-2222-3333-4444-555555555555";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

type Recorded = { type: string; data: unknown };

function recordingHandler(): { events: Recorded[]; handler: (type: string, data: unknown) => void } {
  const events: Recorded[] = [];
  return { events, handler: (type, data) => events.push({ type, data }) };
}

describe("createSseParser", () => {
  it("parses a complete record", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse('event: text_delta\ndata: {"turn":1,"text":"hi"}\n\n');
    expect(events).toEqual([{ type: "text_delta", data: { turn: 1, text: "hi" } }]);
  });

  it("parses multiple records in one chunk", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse('event: turn_start\ndata: {"turn":1}\n\nevent: turn_end\ndata: {"turn":1}\n\n');
    expect(events.map(e => e.type)).toEqual(["turn_start", "turn_end"]);
  });

  it("handles a record split across chunks mid-line", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse("event: text_de");
    parse('lta\ndata: {"turn":1,"te');
    expect(events).toHaveLength(0);
    parse('xt":"partial"}\n\n');
    expect(events).toEqual([{ type: "text_delta", data: { turn: 1, text: "partial" } }]);
  });

  it("handles the record separator split across chunks", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse('event: turn_start\ndata: {"turn":1}\n');
    expect(events).toHaveLength(0);
    parse("\n");
    expect(events.map(e => e.type)).toEqual(["turn_start"]);
  });

  it("ignores unknown event types and comment/extra fields", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse('event: brand_new_thing\ndata: {"x":1}\n\n: comment\nid: 7\nevent: turn_end\ndata: {"turn":2}\n\n');
    // Unknown types are forwarded (forward compatible) — the consumer decides
    expect(events.map(e => e.type)).toEqual(["brand_new_thing", "turn_end"]);
  });

  it("skips frames with malformed JSON without dying", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse("event: text_delta\ndata: {broken\n\nevent: turn_end\ndata: {\"turn\":1}\n\n");
    expect(events.map(e => e.type)).toEqual(["turn_end"]);
  });

  it("drops a trailing partial record at stream end", () => {
    const { events, handler } = recordingHandler();
    const parse = createSseParser(handler as never);
    parse('event: agent_end\ndata: {"status":"completed"}\n\nevent: text_delta\ndata: {"tru');
    expect(events.map(e => e.type)).toEqual(["agent_end"]);
  });
});

function streamResponse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder();
  let i = 0;
  const body = {
    getReader: () => ({
      read: async () => {
        if (i < chunks.length) {
          return { done: false, value: encoder.encode(chunks[i++]) };
        }
        return { done: true, value: undefined };
      },
    }),
  };
  return {
    ok: status >= 200 && status < 300,
    status,
    body,
    text: async () => "",
  } as unknown as Response;
}

describe("streamChatMessage", () => {
  it("POSTs to the stream route and forwards all events", async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([
      'event: agent_start\ndata: {"chatUuid":"c1"}\n\nevent: text_del',
      'ta\ndata: {"turn":1,"text":"Hello"}\n\n',
      'event: agent_end\ndata: {"status":"completed"}\n\n',
    ]));
    vi.stubGlobal("fetch", fetchMock);

    const { events, handler } = recordingHandler();
    await streamChatMessage(TOKEN, CHAT_UUID, { message: "Hi", skillUuids: ["s1"] }, handler as never);

    expect(fetchMock).toHaveBeenCalledWith(
      `${API_BASE_URL}/chats/${CHAT_UUID}/stream`,
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ Authorization: `Bearer ${TOKEN}` }),
        body: JSON.stringify({ message: "Hi", skillUuids: ["s1"] }),
      }),
    );
    expect(events.map(e => e.type)).toEqual(["agent_start", "text_delta", "agent_end"]);
  });

  it("throws AgentBusyError on 409", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(streamResponse([], 409)));
    const { handler } = recordingHandler();
    await expect(streamChatMessage(TOKEN, CHAT_UUID, { message: "Hi" }, handler as never)).rejects.toBeInstanceOf(AgentBusyError);
  });

  it("throws on other error statuses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(streamResponse([], 404)));
    const { handler } = recordingHandler();
    await expect(streamChatMessage(TOKEN, CHAT_UUID, { message: "Hi" }, handler as never)).rejects.toThrow("API error 404");
  });

  it("resolves silently when aborted", async () => {
    const controller = new AbortController();
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => {
      controller.abort();
      throw new DOMException("Aborted", "AbortError");
    }));
    const { handler } = recordingHandler();
    await expect(streamChatMessage(TOKEN, CHAT_UUID, { message: "Hi" }, handler as never, controller.signal)).resolves.toBeUndefined();
  });
});

describe("cancelChatStream", () => {
  it("DELETEs the stream route", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204, text: async () => "" } as Response);
    vi.stubGlobal("fetch", fetchMock);
    await cancelChatStream(TOKEN, CHAT_UUID);
    expect(fetchMock).toHaveBeenCalledWith(
      `${API_BASE_URL}/chats/${CHAT_UUID}/stream`,
      expect.objectContaining({ method: "DELETE" }),
    );
  });
});
