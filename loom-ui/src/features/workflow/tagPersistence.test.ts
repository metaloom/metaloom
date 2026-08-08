import { afterEach, describe, expect, it, vi } from "vitest";
import {
  addAssetTag, isCurated, isPending, loadTagVocabulary, pendingTag, removeAssetTag, toWorkflowTags,
} from "./tagPersistence";
import { API_BASE_URL } from "../../api/config";

const TOKEN = "test-token";
const ASSET = "a1";

function mockFetch(handler: (url: string, options: RequestInit) => unknown, status = 200) {
  const fetchMock = vi.fn(async (url: string, options: RequestInit) => ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => handler(url, options),
    text: async () => "",
  }) as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("addAssetTag (workflow tagging → tag placement)", () => {
  it("POSTs the asset-scoped tag route with the default namespace", async () => {
    const fetchMock = mockFetch(() => ({ uuid: "t-new", name: "hero", collection: "default" }), 201);

    const tag = await addAssetTag(TOKEN, ASSET, "hero");

    expect(tag).toEqual({ uuid: "t-new", name: "hero", nodeKind: "manual" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/${ASSET}/tags`);
    expect(options.method).toBe("POST");
    expect(options.headers).toMatchObject({ Authorization: `Bearer ${TOKEN}` });
    expect(JSON.parse(options.body as string)).toEqual({ name: "hero", collection: "default" });
  });

  it("never creates a tag row itself — the asset route resolves an existing tag", async () => {
    // `tag` is UNIQUE (name, collection); an endpoint that INSERTed a tag per asset broke on the
    // second asset. Coining a tag from here must still go through the asset route, which resolves.
    const fetchMock = mockFetch(() => ({ uuid: "t-existing", name: "archive", collection: "default" }), 201);

    const tag = await addAssetTag(TOKEN, ASSET, "archive");

    expect(tag.uuid).toBe("t-existing");
    const urls = fetchMock.mock.calls.map(([url]) => url);
    expect(urls).toEqual([`${API_BASE_URL}/assets/${ASSET}/tags`]);
    expect(urls).not.toContain(`${API_BASE_URL}/tags`);
  });

  it("rejects on a non-2xx so the caller can roll its optimistic chip back", async () => {
    mockFetch(() => ({}), 500);
    await expect(addAssetTag(TOKEN, ASSET, "hero")).rejects.toThrow(/500/);
  });
});

describe("removeAssetTag", () => {
  it("DELETEs the tag from the asset and tolerates a 204 with no JSON body", async () => {
    const fetchMock = mockFetch(() => {
      throw new Error("a 204 has no body to parse");
    }, 204);

    await expect(removeAssetTag(TOKEN, ASSET, "t-1")).resolves.toBeUndefined();

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/${ASSET}/tags/t-1`);
    expect(options.method).toBe("DELETE");
  });

  it("rejects on a non-2xx so the caller can restore the chip", async () => {
    mockFetch(() => ({}), 403);
    await expect(removeAssetTag(TOKEN, ASSET, "t-1")).rejects.toThrow(/403/);
  });
});

describe("loadTagVocabulary", () => {
  it("de-duplicates and sorts the names, and asks for more than the server default page", async () => {
    const fetchMock = mockFetch(() => ({
      data: [
        { uuid: "1", name: "hero", collection: "default" },
        { uuid: "2", name: "archive", collection: "default" },
        { uuid: "3", name: "hero", collection: "category" }, // same word, other namespace
      ],
    }));

    const names = await loadTagVocabulary(TOKEN);

    expect(names).toEqual(["archive", "hero"]);
    // The server pages at 25 by default, which would silently truncate the vocabulary.
    expect(fetchMock.mock.calls[0][0]).toContain("limit=200");
  });
});

describe("tag provenance", () => {
  it("treats an absent nodeKind as curated, matching the column's 'manual' default", () => {
    // The default is deliberate: a machine tag mislabelled human is merely not filtered out,
    // while a human tag mislabelled machine could be deleted by a reconciling node.
    expect(isCurated({ uuid: "1", name: "hero" })).toBe(true);
    expect(isCurated({ uuid: "2", name: "hero", nodeKind: "manual" })).toBe(true);
    expect(isCurated({ uuid: "3", name: "cat", nodeKind: "tag" })).toBe(false);
  });

  it("carries nodeKind and confidence across from the asset response", () => {
    expect(toWorkflowTags([
      { uuid: "1", name: "hero", collection: "default" },
      { uuid: "2", name: "cat", collection: "default", nodeKind: "tag", confidence: 0.82 },
    ])).toEqual([
      { uuid: "1", name: "hero", nodeKind: undefined, confidence: undefined },
      { uuid: "2", name: "cat", nodeKind: "tag", confidence: 0.82 },
    ]);
    expect(toWorkflowTags(undefined)).toEqual([]);
  });

  it("marks an in-flight chip as pending, and a saved one as not", () => {
    expect(isPending(pendingTag("hero"))).toBe(true);
    expect(isPending({ uuid: "t-1", name: "hero" })).toBe(false);
    // A pending chip is curated - it is a person's tag that has simply not landed yet.
    expect(isCurated(pendingTag("hero"))).toBe(true);
  });
});
