import { describe, expect, it } from "vitest";
import type { SearchHitResponse } from "../../api/search";
import { SEARCH_MAX_OFFSET, SEARCH_PAGE_SIZE } from "../../types";
import { clampOffset, formatTimecode, hasNextPage, hitTarget, pageRange } from "./searchHits";

function hit(overrides: Partial<SearchHitResponse>): SearchHitResponse {
  return { type: "asset", uuid: "u1", score: 1, title: "t", ...overrides };
}

describe("hitTarget", () => {
  it("sends an asset hit to its detail route", () => {
    expect(hitTarget(hit({ type: "asset", uuid: "a1" }))).toBe("/assets/a1");
  });

  it("sends asset-owned hits to the owning asset", () => {
    expect(hitTarget(hit({ type: "transcript", uuid: "t1", assetUuid: "a1" }))).toBe("/assets/a1");
    expect(hitTarget(hit({ type: "annotation", uuid: "n1", assetUuid: "a1" }))).toBe("/assets/a1");
    expect(hitTarget(hit({ type: "segment", uuid: "s1", assetUuid: "a1" }))).toBe("/assets/a1");
  });

  it("returns null when an asset-owned hit has no asset to go to", () => {
    expect(hitTarget(hit({ type: "transcript", uuid: "t1" }))).toBeNull();
  });

  it("sends the remaining types to their management screens", () => {
    expect(hitTarget(hit({ type: "tag" }))).toBe("/tags");
    expect(hitTarget(hit({ type: "collection" }))).toBe("/collections");
    expect(hitTarget(hit({ type: "library" }))).toBe("/library");
    expect(hitTarget(hit({ type: "person" }))).toBe("/detection");
    expect(hitTarget(hit({ type: "cluster" }))).toBe("/detection");
  });

  it("encodes the uuid into the route", () => {
    expect(hitTarget(hit({ type: "asset", uuid: "a/1" }))).toBe("/assets/a%2F1");
  });
});

describe("formatTimecode", () => {
  it("formats under an hour as m:ss", () => {
    expect(formatTimecode(0)).toBe("0:00");
    expect(formatTimecode(9_000)).toBe("0:09");
    expect(formatTimecode(72_000)).toBe("1:12");
  });

  it("formats an hour or more as h:mm:ss", () => {
    expect(formatTimecode(3_600_000)).toBe("1:00:00");
    expect(formatTimecode(3_754_000)).toBe("1:02:34");
  });

  it("floors a negative offset to zero", () => {
    expect(formatTimecode(-5)).toBe("0:00");
  });
});

describe("clampOffset", () => {
  it("floors at zero", () => {
    expect(clampOffset(-1)).toBe(0);
    expect(clampOffset(0)).toBe(0);
  });

  it("caps at the deep-paging limit so a stale URL never 400s", () => {
    expect(clampOffset(SEARCH_MAX_OFFSET + 5_000)).toBe(SEARCH_MAX_OFFSET);
  });

  it("passes a valid offset through", () => {
    expect(clampOffset(50)).toBe(50);
  });

  it("treats a non-numeric offset as zero", () => {
    expect(clampOffset(Number.NaN)).toBe(0);
  });
});

describe("hasNextPage", () => {
  it("is true when more hits remain", () => {
    expect(hasNextPage(0, SEARCH_PAGE_SIZE, 100)).toBe(true);
  });

  it("is false on the last page", () => {
    expect(hasNextPage(75, 25, 100)).toBe(false);
  });

  it("is false for an empty page", () => {
    expect(hasNextPage(0, 0, 0)).toBe(false);
  });

  it("is false at the deep-paging cap even when more hits exist", () => {
    // The provider answers 400 past the cap, so Next must be dead before the request goes out.
    expect(hasNextPage(SEARCH_MAX_OFFSET, SEARCH_PAGE_SIZE, 100_000)).toBe(false);
  });
});

describe("pageRange", () => {
  it("reports a one-based inclusive range", () => {
    expect(pageRange(0, 25)).toEqual({ from: 1, to: 25 });
    expect(pageRange(25, 25)).toEqual({ from: 26, to: 50 });
  });

  it("reports an empty range for an empty page", () => {
    expect(pageRange(0, 0)).toEqual({ from: 0, to: 0 });
  });

  it("uses the actual page length for a short last page", () => {
    expect(pageRange(50, 7)).toEqual({ from: 51, to: 57 });
  });
});
