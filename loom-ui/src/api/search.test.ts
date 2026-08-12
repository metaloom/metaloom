import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SearchApiError,
  buildSearchQuery,
  searchAssets,
  searchResults,
  searchStatus,
  searchSuggestions,
} from "./search";
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

function mockFetchError(status: number, body: string) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: false,
    status,
    json: async () => ({}),
    text: async () => body,
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** The query string of the nth fetch call, as a URLSearchParams. */
function queryOf(fetchMock: ReturnType<typeof vi.fn>, call = 0): URLSearchParams {
  const url: string = fetchMock.mock.calls[call][0];
  return new URLSearchParams(url.slice(url.indexOf("?")));
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("buildSearchQuery", () => {
  it("emits only the term when nothing else is set", () => {
    expect(buildSearchQuery({ q: "aurora" })).toBe("?q=aurora");
  });

  it("never emits a bare mode= when the mode is unset", () => {
    // A stray `?mode=` is the exact thing the task guards against; an unknown value earns a 400.
    expect(buildSearchQuery({ q: "a" })).not.toContain("mode=");
    expect(buildSearchQuery({ q: "a", mode: "LEXICAL" })).toContain("mode=LEXICAL");
  });

  it("omits empty lists entirely rather than sending an empty value", () => {
    const query = buildSearchQuery({ q: "a", types: [], tag: [], facets: [] });
    expect(query).toBe("?q=a");
  });

  it("joins types, tag and facets as comma separated single keys", () => {
    const params = new URLSearchParams(
      buildSearchQuery({
        q: "a",
        types: ["asset", "transcript"],
        tag: ["nature", "city"],
        facets: ["mime_type", "entity_type"],
      }).slice(1),
    );
    // `set` not `append`: a repeated scalar key is a 400 on the server.
    expect(params.getAll("types")).toEqual(["asset,transcript"]);
    expect(params.getAll("tag")).toEqual(["nature,city"]);
    expect(params.getAll("facets")).toEqual(["mime_type,entity_type"]);
  });

  it("drops blank entries from a list", () => {
    const params = new URLSearchParams(buildSearchQuery({ q: "a", tag: ["nature", "  ", ""] }).slice(1));
    expect(params.get("tag")).toBe("nature");
  });

  it("emits offset=0 because zero is a meaningful offset", () => {
    const params = new URLSearchParams(buildSearchQuery({ q: "a", offset: 0, limit: 25 }).slice(1));
    expect(params.get("offset")).toBe("0");
    expect(params.get("limit")).toBe("25");
  });

  it("emits highlight only when it is true", () => {
    expect(buildSearchQuery({ q: "a", highlight: true })).toContain("highlight=true");
    // String(false) is non-blank, so this is the case a naive serializer gets wrong.
    expect(buildSearchQuery({ q: "a", highlight: false })).not.toContain("highlight");
    expect(buildSearchQuery({ q: "a" })).not.toContain("highlight");
  });

  it("round-trips phrase, negation and or syntax intact", () => {
    const term = '"quarterly update" or championship -podcast';
    const params = new URLSearchParams(buildSearchQuery({ q: term }).slice(1));
    expect(params.get("q")).toBe(term);
  });

  it("carries every remaining parameter through", () => {
    const params = new URLSearchParams(
      buildSearchQuery({
        q: "a",
        sort: "NEWEST",
        cursor: "c1",
        mime: "image/",
        library: "lib-1",
        space: "space-1",
        collection: "col-1",
        from: "2026-01-01T00:00:00Z",
        to: "2026-12-31T23:59:59Z",
        lang: "en",
        profile: "default",
      }).slice(1),
    );
    expect(params.get("sort")).toBe("NEWEST");
    expect(params.get("cursor")).toBe("c1");
    expect(params.get("mime")).toBe("image/");
    expect(params.get("library")).toBe("lib-1");
    expect(params.get("space")).toBe("space-1");
    expect(params.get("collection")).toBe("col-1");
    expect(params.get("from")).toBe("2026-01-01T00:00:00Z");
    expect(params.get("to")).toBe("2026-12-31T23:59:59Z");
    expect(params.get("lang")).toBe("en");
    expect(params.get("profile")).toBe("default");
  });
});

describe("search API client", () => {
  it("searchResults GETs the results route with the auth header", async () => {
    const body = { data: [], _metainfo: { totalHits: 0, provider: "postgres" } };
    const fetchMock = mockFetchOk(body);

    const result = await searchResults(TOKEN, { q: "aurora", highlight: true });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/search/results?q=aurora&highlight=true`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(result._metainfo.provider).toBe("postgres");
  });

  it("maps a hit response through unchanged", async () => {
    const hit = {
      type: "transcript",
      uuid: "t1",
      assetUuid: "a1",
      score: 0.42,
      title: "team-meeting.mp4",
      subtitle: "Campaign Media",
      matchedIn: "body",
      highlights: ["… reduced processing costs by nearly a <b>third</b> …"],
      timeFromMs: 42_000,
      mimeType: "video/mp4",
      size: 1024,
    };
    mockFetchOk({ data: [hit], _metainfo: { totalHits: 1, totalExact: true, provider: "postgres" } });

    const result = await searchResults(TOKEN, { q: "third" });

    expect(result.data[0].type).toBe("transcript");
    expect(result.data[0].uuid).toBe("t1");
    expect(result.data[0].highlights).toEqual(["… reduced processing costs by nearly a <b>third</b> …"]);
    expect(result.data[0].timeFromMs).toBe(42_000);
  });

  it("parses the facet map keyed by the requested spelling", async () => {
    mockFetchOk({
      data: [],
      _metainfo: { totalHits: 0, provider: "postgres" },
      facets: { mime_type: [{ value: "image/jpeg", count: 3 }] },
    });

    const result = await searchResults(TOKEN, { q: "a", facets: ["mime_type"] });

    expect(result.facets?.mime_type).toEqual([{ value: "image/jpeg", count: 3 }]);
  });

  it("refuses a blank query without touching the network", async () => {
    const fetchMock = mockFetchOk();

    await expect(searchResults(TOKEN, { q: "   " })).rejects.toBeInstanceOf(SearchApiError);
    await expect(searchAssets(TOKEN, { q: "" })).rejects.toBeInstanceOf(SearchApiError);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports a locally refused blank query as a 400, matching the server", async () => {
    mockFetchOk();
    await expect(searchResults(TOKEN, { q: "" })).rejects.toMatchObject({ status: 400 });
  });

  it("searchAssets targets the assets route", async () => {
    const fetchMock = mockFetchOk({ data: [], _metainfo: { totalHits: 0, provider: "postgres" } });

    await searchAssets(TOKEN, { q: "sunset" });

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/search/assets?q=sunset`);
  });

  it("searchSuggestions sends the prefix as q and returns the data array", async () => {
    const fetchMock = mockFetchOk({
      data: [{ text: "team-meeting.mp4", type: "asset", uuid: "a1", score: 0.9 }],
    });

    const result = await searchSuggestions(TOKEN, "dro", { limit: 8 });

    const query = queryOf(fetchMock);
    expect(fetchMock.mock.calls[0][0]).toContain(`${API_BASE_URL}/search/suggestions?`);
    expect(query.get("q")).toBe("dro");
    expect(query.get("limit")).toBe("8");
    expect(result).toHaveLength(1);
    expect(result[0].text).toBe("team-meeting.mp4");
  });

  it("searchSuggestions returns an empty array when the response omits data", async () => {
    // The list envelope only creates `data` lazily, so zero suggestions means the key is absent.
    mockFetchOk({});

    await expect(searchSuggestions(TOKEN, "zzz")).resolves.toEqual([]);
  });

  it("searchSuggestions refuses a blank prefix without touching the network", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await expect(searchSuggestions(TOKEN, "  ")).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("searchSuggestions narrows by type when asked", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await searchSuggestions(TOKEN, "dro", { types: ["asset", "tag"] });

    expect(queryOf(fetchMock).get("types")).toBe("asset,tag");
  });

  it("searchStatus parses the provider, availability and capabilities", async () => {
    const fetchMock = mockFetchOk({
      provider: "postgres",
      available: true,
      capabilities: ["LEXICAL", "PHRASE", "FACETS", "SUGGEST"],
      documentCount: 42,
      dirtyCount: 0,
    });

    const status = await searchStatus(TOKEN);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/search/status`);
    expect(status.available).toBe(true);
    expect(status.provider).toBe("postgres");
    expect(status.capabilities).toContain("FACETS");
    expect(status.documentCount).toBe(42);
  });

  it("searchStatus reads an unavailable provider with its reason", async () => {
    mockFetchOk({
      provider: "none",
      available: false,
      reason: "Search is disabled on this deployment.",
      capabilities: [],
      documentCount: 0,
      dirtyCount: 0,
    });

    const status = await searchStatus(TOKEN);

    expect(status.available).toBe(false);
    expect(status.provider).toBe("none");
    expect(status.reason).toBe("Search is disabled on this deployment.");
  });

  it("throws a typed error carrying the status for a 503", async () => {
    // The server discards its error code, so the status is the only machine-readable signal.
    mockFetchError(503, JSON.stringify({ message: "Search is unavailable: Search is disabled on this deployment." }));

    const error = await searchResults(TOKEN, { q: "a" }).catch((e) => e);

    expect(error).toBeInstanceOf(SearchApiError);
    expect(error.status).toBe(503);
    expect(error.body).toBe("Search is unavailable: Search is disabled on this deployment.");
    expect(error.message).toContain("API error 503");
  });

  it("distinguishes 400 and 403 by status alone", async () => {
    mockFetchError(400, JSON.stringify({ message: "The postgres search provider supports only LEXICAL mode. Requested: SEMANTIC." }));
    const badRequest = await searchResults(TOKEN, { q: "a", mode: "SEMANTIC" }).catch((e) => e);
    expect(badRequest.status).toBe(400);
    expect(badRequest.body).toContain("only LEXICAL mode");

    mockFetchError(403, JSON.stringify({ message: "Missing permission READ_SEARCH" }));
    const forbidden = await searchStatus(TOKEN).catch((e) => e);
    expect(forbidden).toBeInstanceOf(SearchApiError);
    expect(forbidden.status).toBe(403);
    expect(forbidden.body).toBe("Missing permission READ_SEARCH");
  });

  it("falls back to the raw body when the error is not JSON", async () => {
    mockFetchError(500, "Internal Server Error");

    const error = await searchResults(TOKEN, { q: "a" }).catch((e) => e);

    expect(error.status).toBe(500);
    expect(error.body).toBe("Internal Server Error");
  });

  it("sends the bearer token on every route", async () => {
    const fetchMock = mockFetchOk({ data: [], _metainfo: { totalHits: 0, provider: "postgres" } });

    await searchResults(TOKEN, { q: "a" });
    await searchAssets(TOKEN, { q: "a" });
    await searchSuggestions(TOKEN, "a");
    await searchStatus(TOKEN);

    expect(fetchMock).toHaveBeenCalledTimes(4);
    for (const call of fetchMock.mock.calls) {
      expect(call[1].headers.Authorization).toBe(`Bearer ${TOKEN}`);
    }
  });
});
