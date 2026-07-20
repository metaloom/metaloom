import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createComment,
  createCommentForAsset,
  updateComment,
  deleteComment,
} from "./comments";
import { API_BASE_URL } from "./config";

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

describe("comments API client", () => {
  it("createCommentForAsset POSTs to the asset-scoped comments route", async () => {
    const fetchMock = mockFetchOk({ uuid: "c1", assetUuid: "a1", text: "hi" });

    await createCommentForAsset(TOKEN, "a1", { text: "hi" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/comments`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify({ text: "hi" }));
  });

  it("createComment POSTs to the global comments route", async () => {
    const fetchMock = mockFetchOk({ uuid: "c1" });

    await createComment(TOKEN, { title: "t", text: "hi" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ title: "t", text: "hi" }));
  });

  it("updateComment POSTs (not PUT) to the comment uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "c1", text: "edited" });

    await updateComment(TOKEN, "c1", { text: "edited" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/c1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ text: "edited" }));
  });

  it("deleteComment DELETEs the comment uuid route", async () => {
    const fetchMock = mockFetchOk();

    await deleteComment(TOKEN, "c1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/c1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes uuids/asset ids into the URL path", async () => {
    const fetchMock = mockFetchOk();

    await updateComment(TOKEN, "a b/c", { text: "x" });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/a%20b%2Fc`);
  });
});
