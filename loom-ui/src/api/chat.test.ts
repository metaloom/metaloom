import { afterEach, describe, expect, it, vi } from "vitest";
import {
  listChats,
  loadChat,
  createChat,
  updateChat,
  deleteChat,
  ChatCreateRequest,
} from "./chat";
import { API_BASE_URL } from "./config";
import { ChatMessage } from "../types";

const TOKEN = "test-token";

function mockFetchOk(body: unknown = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => "",
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("chat API client", () => {
  it("listChats GETs the chats route", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listChats(TOKEN);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("loadChat GETs the chat uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "s1", title: "t", messages: [] });

    await loadChat(TOKEN, "s1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/s1`);
    expect(options.method).toBe("GET");
  });

  it("createChat POSTs to the chats route and returns the created session", async () => {
    const created = { uuid: "s1", title: "Hello", messages: [] };
    const fetchMock = mockFetchOk(created);
    const request: ChatCreateRequest = { title: "Hello", messages: [] };

    const result = await createChat(TOKEN, request);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify(request));
    expect(result).toEqual(created);
  });

  it("updateChat POSTs (not PUT) to the chat uuid route with the messages body", async () => {
    const fetchMock = mockFetchOk({ uuid: "s1", title: "t", messages: [] });
    const messages: ChatMessage[] = [
      { id: "m1", role: "user", content: "hi", createdAt: "2026-01-01T00:00:00Z" },
    ];

    await updateChat(TOKEN, "s1", { messages });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/s1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ messages }));
  });

  it("deleteChat DELETEs the chat uuid route", async () => {
    const fetchMock = mockFetchOk();

    await deleteChat(TOKEN, "s1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/s1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes uuids into the URL path", async () => {
    const fetchMock = mockFetchOk({ uuid: "x", title: "t", messages: [] });

    await loadChat(TOKEN, "a b/c");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/a%20b%2Fc`);
  });

  it("round-trips ChatReference asset links through the request body unchanged", async () => {
    const fetchMock = mockFetchOk({ uuid: "s1", title: "t", messages: [] });
    const messages: ChatMessage[] = [
      {
        id: "m2",
        role: "assistant",
        content: "Here are the assets.",
        createdAt: "2026-01-01T00:00:00Z",
        references: [{ type: "asset", id: "a1", label: "Hero_Campaign_30s_Final.mp4" }],
      },
    ];

    await createChat(TOKEN, { title: "Campaign", messages });

    const [, options] = fetchMock.mock.calls[0];
    const sent = JSON.parse(options.body);
    expect(sent.messages[0].references[0]).toEqual({
      type: "asset",
      id: "a1",
      label: "Hero_Campaign_30s_Final.mp4",
    });
  });
});
