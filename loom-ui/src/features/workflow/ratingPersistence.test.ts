import { afterEach, describe, expect, it, vi } from "vitest";
import { persistAssetRating, hydrateAssetRatings, RATING_REACTION_TYPE } from "./ratingPersistence";
import { API_BASE_URL } from "../../api/config";

const TOKEN = "test-token";
const ASSET = "a1";

function mockFetch(handler: (url: string, options: RequestInit) => unknown) {
  const fetchMock = vi.fn(async (url: string, options: RequestInit) => ({
    ok: true,
    status: 200,
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

describe("persistAssetRating (workflow rating → asset reaction)", () => {
  it("POSTs a create when the asset has no reaction yet, carrying the rating", async () => {
    const fetchMock = mockFetch(() => ({ uuid: "r-new" }));

    const uuid = await persistAssetRating(TOKEN, ASSET, 5);

    expect(uuid).toBe("r-new");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/${ASSET}/reactions`);
    expect(options.method).toBe("POST");
    expect(options.headers).toMatchObject({ Authorization: `Bearer ${TOKEN}` });
    expect(JSON.parse(options.body as string)).toEqual({ type: RATING_REACTION_TYPE, rating: 5 });
  });

  it("updates the existing reaction (POST to its uuid) instead of creating a duplicate", async () => {
    const fetchMock = mockFetch(() => ({ uuid: "r-old" }));

    const uuid = await persistAssetRating(TOKEN, ASSET, 8, "r-old");

    expect(uuid).toBe("r-old");
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/${ASSET}/reactions/r-old`);
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body as string)).toEqual({ type: RATING_REACTION_TYPE, rating: 8 });
  });
});

describe("hydrateAssetRatings", () => {
  it("lists each asset's reactions and picks the one carrying a numeric rating", async () => {
    const store: Record<string, unknown> = {
      a1: { data: [{ uuid: "r1", rating: 7 }] },
      a2: { data: [{ uuid: "r2", type: "THUMBSUP" }] }, // no rating → ignored
      a3: { data: [] },
    };
    mockFetch(url => {
      const asset = url.split("/assets/")[1].split("/")[0];
      return store[asset] ?? { data: [] };
    });

    const { ratings, reactionUuids } = await hydrateAssetRatings(TOKEN, ["a1", "a2", "a3"]);

    expect(ratings).toEqual({ a1: 7 });
    expect(reactionUuids).toEqual({ a1: "r1" });
  });

  it("ignores per-asset failures without rejecting the whole hydration", async () => {
    mockFetch(url => {
      if (url.includes("/assets/bad/")) throw new Error("boom");
      return { data: [{ uuid: "r1", rating: 3 }] };
    });

    const { ratings } = await hydrateAssetRatings(TOKEN, ["bad", "good"]);

    expect(ratings).toEqual({ good: 3 });
  });
});
