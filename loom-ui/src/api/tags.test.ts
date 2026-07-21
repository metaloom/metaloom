import { afterEach, describe, expect, it, vi } from "vitest";
import { tagAsset, untagAsset } from "./tags";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";

function mockFetchOk(body: unknown = {}, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status,
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

describe("asset tagging API client", () => {
  it("tagAsset POSTs to the asset-scoped tags route and returns the created tag", async () => {
    const created = { uuid: "t1", name: "sunset", collection: "default" };
    const fetchMock = mockFetchOk(created, 201);
    const request = { name: "sunset", collection: "default" };

    const result = await tagAsset(TOKEN, "a1", request);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/tags`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify(request));
    expect(result).toEqual(created);
  });

  it("tagAsset serializes an area (region tag) into the body", async () => {
    const fetchMock = mockFetchOk({ uuid: "t2", name: "region", collection: "default" }, 201);
    const request = { name: "region", collection: "default", area: { from: 1000, to: 2000 } };

    await tagAsset(TOKEN, "a1", request);

    const [, options] = fetchMock.mock.calls[0];
    expect(options.body).toBe(JSON.stringify(request));
  });

  it("untagAsset DELETEs the asset-scoped tag route with no body", async () => {
    const fetchMock = mockFetchOk({}, 204);

    await untagAsset(TOKEN, "a1", "t1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/tags/t1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.body).toBeUndefined();
  });

  it("encodes the asset uuid into the tagAsset URL path", async () => {
    const fetchMock = mockFetchOk({ uuid: "t1", name: "x", collection: "default" }, 201);

    await tagAsset(TOKEN, "a b/c", { name: "x", collection: "default" });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a%20b%2Fc/tags`);
  });

  it("encodes both the asset uuid and tag uuid into the untagAsset URL path", async () => {
    const fetchMock = mockFetchOk({}, 204);

    await untagAsset(TOKEN, "a b/c", "x/y");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a%20b%2Fc/tags/x%2Fy`);
  });

  it("tagAsset throws on a non-ok response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({}),
      text: async () => "forbidden",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(tagAsset(TOKEN, "a1", { name: "x", collection: "default" })).rejects.toThrow(/403/);
  });

  it("untagAsset throws on a non-ok response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({}),
      text: async () => "not found",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(untagAsset(TOKEN, "a1", "missing")).rejects.toThrow(/404/);
  });
});
