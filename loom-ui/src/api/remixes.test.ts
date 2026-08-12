import { afterEach, describe, expect, it, vi } from "vitest";
import {
  addRemixAssets,
  searchRemixes,
  combineIntoRemix,
  createRemix,
  deleteRemix,
  listAssetRemixes,
  listRemixMembers,
  listRemixes,
  loadRemix,
  removeRemixAsset,
  setRemixSource,
  updateRemix,
} from "./remixes";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";
const REMIX = "9d1c1c6a-5b1a-4d33-8f0f-1a2b3c4d5e6f";
const ASSET = "11111111-2222-3333-4444-555555555555";

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

describe("remix API client", () => {
  it("listRemixes hits /remixes with the bearer token", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listRemixes(TOKEN);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("listRemixes appends paging", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listRemixes(TOKEN, { limit: 10, from: "cursor-uuid" });

    expect(fetchMock.mock.calls[0][0]).toContain("limit=10");
    expect(fetchMock.mock.calls[0][0]).toContain("from=cursor-uuid");
  });

  it("loadRemix encodes the uuid into the path", async () => {
    const fetchMock = mockFetchOk({ uuid: REMIX, name: "x", memberCount: 0 });

    await loadRemix(TOKEN, REMIX);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/remixes/${REMIX}`);
  });

  it("createRemix posts the body", async () => {
    const fetchMock = mockFetchOk({ uuid: REMIX, name: "beach", memberCount: 0 }, 201);

    await createRemix(TOKEN, { name: "beach" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes`);
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ name: "beach" });
  });

  /**
   * Update is POST across this API, not PUT. Getting this wrong 405s at runtime and nothing else
   * in the client would catch it.
   */
  it("updateRemix uses POST rather than PUT", async () => {
    const fetchMock = mockFetchOk({ uuid: REMIX, name: "renamed", memberCount: 0 });

    await updateRemix(TOKEN, REMIX, { name: "renamed" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes/${REMIX}`);
    expect(options.method).toBe("POST");
  });

  it("deleteRemix issues a DELETE and expects no body", async () => {
    const fetchMock = mockFetchOk({}, 204);

    await expect(deleteRemix(TOKEN, REMIX)).resolves.toBeUndefined();

    expect(fetchMock.mock.calls[0][1].method).toBe("DELETE");
  });

  it("listRemixMembers hits the nested assets route", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listRemixMembers(TOKEN, REMIX);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/remixes/${REMIX}/assets`);
  });

  /** The whole selection goes in one request; a per-asset loop would be N round trips. */
  it("addRemixAssets posts every uuid in one request", async () => {
    const fetchMock = mockFetchOk({ uuid: REMIX, name: "x", memberCount: 2 });

    await addRemixAssets(TOKEN, REMIX, [ASSET, "another-uuid"]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes/${REMIX}/assets`);
    expect(JSON.parse(options.body)).toEqual({ assetUuids: [ASSET, "another-uuid"] });
  });

  it("removeRemixAsset deletes the membership, keyed by asset uuid", async () => {
    const fetchMock = mockFetchOk({}, 204);

    await removeRemixAsset(TOKEN, REMIX, ASSET);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes/${REMIX}/assets/${ASSET}`);
    expect(options.method).toBe("DELETE");
  });

  it("setRemixSource posts to /source with the asset in sourceAssetUuid", async () => {
    const fetchMock = mockFetchOk({ uuid: REMIX, name: "x", memberCount: 1, sourceAssetUuid: ASSET });

    await setRemixSource(TOKEN, REMIX, ASSET);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes/${REMIX}/source`);
    expect(JSON.parse(options.body).sourceAssetUuid).toBe(ASSET);
  });

  it("listAssetRemixes reads from the asset side", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listAssetRemixes(TOKEN, ASSET);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/assets/${ASSET}/remixes`);
  });

  /**
   * "Combine into remix" must be a single create carrying the members. Two calls would leave a
   * named but empty remix behind whenever the second one failed.
   */
  it("combineIntoRemix creates the remix and its members in one call", async () => {
    const fetchMock = mockFetchOk({ uuid: REMIX, name: "beach", memberCount: 2 }, 201);

    await combineIntoRemix(TOKEN, "beach", [ASSET, "second-uuid"]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/remixes`);
    expect(JSON.parse(options.body)).toEqual({
      name: "beach",
      description: undefined,
      assetUuids: [ASSET, "second-uuid"],
    });
  });

  /**
   * Remix search has to go to /search/results, not /search/assets: the latter forces
   * `types={asset}` server-side and can therefore never return a remix.
   */
  it("searchRemixes queries /search/results with types=remix", async () => {
    const fetchMock = mockFetchOk({ data: [], _metainfo: { totalHits: 0 } });

    await searchRemixes(TOKEN, "coastal");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toContain("/search/results");
    expect(url).not.toContain("/search/assets");
    expect(url).toContain("types=remix");
    expect(url).toContain("q=coastal");
  });

  it("searchRemixes passes a limit through", async () => {
    const fetchMock = mockFetchOk({ data: [], _metainfo: { totalHits: 0 } });

    await searchRemixes(TOKEN, "coastal", { limit: 5 });

    expect(fetchMock.mock.calls[0][0]).toContain("limit=5");
  });

  it("surfaces a failed response as an error carrying the status", async () => {
    mockFetchFailure(403, "Forbidden");

    await expect(listRemixes(TOKEN)).rejects.toThrow(/403/);
  });

  it("surfaces a failed delete too, rather than resolving silently", async () => {
    mockFetchFailure(404, "Not Found");

    await expect(removeRemixAsset(TOKEN, REMIX, ASSET)).rejects.toThrow(/404/);
  });
});
