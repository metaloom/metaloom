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
    // Spelled out rather than via the constant: the whole point of the change is which
    // string goes on the wire, and asserting the constant against itself proves nothing.
    expect(JSON.parse(options.body as string)).toEqual({ type: "RATING", rating: 5 });
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
  const ME = "u-me";
  const mine = (uuid: string, rating: number, type = RATING_REACTION_TYPE) => ({
    uuid,
    type,
    rating,
    status: { creator: { uuid: ME } },
  });

  it("lists each asset's reactions and picks the one carrying a numeric rating", async () => {
    const store: Record<string, unknown> = {
      a1: { data: [mine("r1", 7)] },
      a2: { data: [{ uuid: "r2", type: "THUMBSUP", status: { creator: { uuid: ME } } }] }, // no rating → ignored
      a3: { data: [] },
    };
    mockFetch(url => {
      const asset = url.split("/assets/")[1].split("/")[0];
      return store[asset] ?? { data: [] };
    });

    const { ratings, reactionUuids } = await hydrateAssetRatings(TOKEN, ["a1", "a2", "a3"], ME);

    expect(ratings).toEqual({ a1: 7 });
    expect(reactionUuids).toEqual({ a1: "r1" });
  });

  it("ignores another reviewer's rating, so a later edit cannot overwrite their row", async () => {
    mockFetch(() => ({
      data: [
        { uuid: "r-theirs", type: RATING_REACTION_TYPE, rating: 2, status: { creator: { uuid: "u-someone-else" } } },
        mine("r-mine", 9),
      ],
    }));

    const { ratings, reactionUuids } = await hydrateAssetRatings(TOKEN, ["a1"], ME);

    expect(ratings).toEqual({ a1: 9 });
    expect(reactionUuids).toEqual({ a1: "r-mine" });
  });

  it("shows nothing when only other reviewers have rated the asset", async () => {
    mockFetch(() => ({
      data: [{ uuid: "r-theirs", type: RATING_REACTION_TYPE, rating: 4, status: { creator: { uuid: "u-other" } } }],
    }));

    const { ratings, reactionUuids } = await hydrateAssetRatings(TOKEN, ["a1"], ME);

    expect(ratings).toEqual({});
    expect(reactionUuids).toEqual({});
  });

  it("still reads a legacy SATISFIED rating, but prefers a RATING row on the same asset", async () => {
    const store: Record<string, unknown> = {
      legacy: { data: [mine("r-legacy", 6, "SATISFIED")] },
      both: { data: [mine("r-legacy", 6, "SATISFIED"), mine("r-new", 8)] },
    };
    mockFetch(url => {
      const asset = url.split("/assets/")[1].split("/")[0];
      return store[asset] ?? { data: [] };
    });

    const { ratings, reactionUuids } = await hydrateAssetRatings(TOKEN, ["legacy", "both"], ME);

    expect(ratings).toEqual({ legacy: 6, both: 8 });
    expect(reactionUuids).toEqual({ legacy: "r-legacy", both: "r-new" });
  });

  it("ignores per-asset failures without rejecting the whole hydration", async () => {
    mockFetch(url => {
      if (url.includes("/assets/bad/")) throw new Error("boom");
      return { data: [mine("r1", 3)] };
    });

    const { ratings } = await hydrateAssetRatings(TOKEN, ["bad", "good"], ME);

    expect(ratings).toEqual({ good: 3 });
  });
});
