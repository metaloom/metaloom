import { afterEach, describe, expect, it, vi } from "vitest";
import {
  deleteDedupGroup,
  listAssetDedupGroups,
  listDedupGroups,
  loadDedupGroup,
  updateDedupGroup,
} from "./dedup";
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

function mockFetchFailure(status: number, text = "nope") {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: false,
    status,
    json: async () => ({}),
    text: async () => text,
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("dedup review API client", () => {
  it("listDedupGroups sends the status filter", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listDedupGroups(TOKEN, { status: "PENDING" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups?status=PENDING`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("listDedupGroups appends paging to the status filter with a single ?", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listDedupGroups(TOKEN, { status: "PENDING", limit: 20, from: "cursor-uuid" });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups?status=PENDING&limit=20&from=cursor-uuid`);
  });

  it("listDedupGroups omits the query entirely when nothing is set", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listDedupGroups(TOKEN);

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups`);
  });

  it("listDedupGroups pages without a status filter", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listDedupGroups(TOKEN, { limit: 5 });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups?limit=5`);
  });

  it("loadDedupGroup encodes the uuid into the path", async () => {
    const fetchMock = mockFetchOk({ uuid: "g1" });

    const result = await loadDedupGroup(TOKEN, "a b/c");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups/a%20b%2Fc`);
    expect(result).toEqual({ uuid: "g1" });
  });

  it("updateDedupGroup PATCHes the decision", async () => {
    const updated = { uuid: "g1", algorithm: "a", status: "CONFIRMED" };
    const fetchMock = mockFetchOk(updated);

    const result = await updateDedupGroup(TOKEN, "g1", { status: "CONFIRMED" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups/g1`);
    // PATCH, not POST - this route deviates from the POST-to-update convention on purpose.
    expect(options.method).toBe("PATCH");
    expect(options.body).toBe(JSON.stringify({ status: "CONFIRMED" }));
    expect(result).toEqual(updated);
  });

  it("updateDedupGroup carries keepAssetUuid when the keep is reassigned", async () => {
    const fetchMock = mockFetchOk({ uuid: "g1", algorithm: "a", status: "PENDING" });

    await updateDedupGroup(TOKEN, "g1", { status: "PENDING", keepAssetUuid: "asset-2" });

    const [, options] = fetchMock.mock.calls[0];
    expect(JSON.parse(options.body)).toEqual({ status: "PENDING", keepAssetUuid: "asset-2" });
  });

  it("deleteDedupGroup DELETEs with no body", async () => {
    const fetchMock = mockFetchOk({}, 204);

    await deleteDedupGroup(TOKEN, "g1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/dedup-groups/g1`);
    expect(options.method).toBe("DELETE");
    expect(options.body).toBeUndefined();
  });

  it("listAssetDedupGroups hits the asset-scoped route", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listAssetDedupGroups(TOKEN, "asset-1");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/asset-1/dedup-groups`);
  });

  it("propagates a failed decision instead of swallowing it", async () => {
    // The caller reverts its optimistic chip on this rejection, so it must actually reject.
    mockFetchFailure(403, "forbidden");

    await expect(updateDedupGroup(TOKEN, "g1", { status: "CONFIRMED" })).rejects.toThrow(/403/);
  });

  it("propagates a failed list", async () => {
    mockFetchFailure(503);

    await expect(listDedupGroups(TOKEN, { status: "PENDING" })).rejects.toThrow(/503/);
  });

  it("propagates a failed delete", async () => {
    mockFetchFailure(404);

    await expect(deleteDedupGroup(TOKEN, "gone")).rejects.toThrow(/404/);
  });
});
