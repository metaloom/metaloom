import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createAssetReaction,
  updateAssetReaction,
  deleteAssetReaction,
  listCommentReactions,
  createCommentReaction,
  updateCommentReaction,
  deleteCommentReaction,
  listAnnotationReactions,
  loadAnnotationReaction,
  createAnnotationReaction,
  updateAnnotationReaction,
  deleteAnnotationReaction,
} from "./reactions";
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

describe("asset reactions API client", () => {
  it("createAssetReaction POSTs to the asset-scoped reactions route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSUP" });

    await createAssetReaction(TOKEN, "a1", { type: "THUMBSUP" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/reactions`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify({ type: "THUMBSUP" }));
  });

  it("updateAssetReaction POSTs (not PUT) to the reaction uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSDOWN" });

    await updateAssetReaction(TOKEN, "a1", "r1", { type: "THUMBSDOWN" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/reactions/r1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ type: "THUMBSDOWN" }));
  });

  it("deleteAssetReaction DELETEs the reaction uuid route", async () => {
    const fetchMock = mockFetchOk();

    await deleteAssetReaction(TOKEN, "a1", "r1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/reactions/r1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes asset and reaction uuids into the URL path", async () => {
    const fetchMock = mockFetchOk();

    await deleteAssetReaction(TOKEN, "a b/c", "r d/e");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a%20b%2Fc/reactions/r%20d%2Fe`);
  });
});

describe("comment reactions API client", () => {
  it("listCommentReactions GETs the comment-scoped reactions route", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listCommentReactions(TOKEN, "c1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/c1/reactions`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("createCommentReaction POSTs to the comment-scoped reactions route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSUP" });

    await createCommentReaction(TOKEN, "c1", { type: "THUMBSUP" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/c1/reactions`);
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify({ type: "THUMBSUP" }));
  });

  it("updateCommentReaction POSTs (not PUT) to the reaction uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSDOWN" });

    await updateCommentReaction(TOKEN, "c1", "r1", { type: "THUMBSDOWN" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/c1/reactions/r1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ type: "THUMBSDOWN" }));
  });

  it("deleteCommentReaction DELETEs the reaction uuid route", async () => {
    const fetchMock = mockFetchOk();

    await deleteCommentReaction(TOKEN, "c1", "r1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/c1/reactions/r1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes comment and reaction uuids into the URL path", async () => {
    const fetchMock = mockFetchOk();

    await deleteCommentReaction(TOKEN, "a b/c", "r x");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/comments/a%20b%2Fc/reactions/r%20x`);
  });
});

describe("annotation reactions API client", () => {
  it("listAnnotationReactions GETs the annotation-scoped reactions route", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listAnnotationReactions(TOKEN, "n1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1/reactions`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("loadAnnotationReaction GETs the reaction uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSUP" });

    await loadAnnotationReaction(TOKEN, "n1", "r1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1/reactions/r1`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("createAnnotationReaction POSTs to the annotation-scoped reactions route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSUP" });

    await createAnnotationReaction(TOKEN, "n1", { type: "THUMBSUP" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1/reactions`);
    expect(options.method).toBe("POST");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify({ type: "THUMBSUP" }));
  });

  it("updateAnnotationReaction POSTs (not PUT) to the reaction uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "r1", type: "THUMBSDOWN" });

    await updateAnnotationReaction(TOKEN, "n1", "r1", { type: "THUMBSDOWN" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1/reactions/r1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ type: "THUMBSDOWN" }));
  });

  it("deleteAnnotationReaction DELETEs the reaction uuid route", async () => {
    const fetchMock = mockFetchOk();

    await deleteAnnotationReaction(TOKEN, "n1", "r1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1/reactions/r1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes annotation and reaction uuids into the URL path", async () => {
    const fetchMock = mockFetchOk();

    await deleteAnnotationReaction(TOKEN, "a b/c", "r d/e");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/a%20b%2Fc/reactions/r%20d%2Fe`);
  });
});
