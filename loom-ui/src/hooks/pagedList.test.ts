import { describe, expect, it } from "vitest";
import { PAGE_SIZE, displayCount, hasMorePages, isTruncated, mergePage, nextPaging } from "./pagedList";

const key = (r: { uuid: string }) => r.uuid;

describe("mergePage", () => {
  it("appends a page in order", () => {
    const merged = mergePage([{ uuid: "a" }, { uuid: "b" }], [{ uuid: "c" }], key);
    expect(merged.map(key)).toEqual(["a", "b", "c"]);
  });

  it("drops the repeated seek boundary row instead of duplicating it", () => {
    // `?from=b` may or may not exclude b depending on the route — never assume.
    const merged = mergePage([{ uuid: "a" }, { uuid: "b" }], [{ uuid: "b" }, { uuid: "c" }], key);
    expect(merged.map(key)).toEqual(["a", "b", "c"]);
  });

  it("keeps the first occurrence when a row is re-sent with new content", () => {
    const merged = mergePage(
      [{ uuid: "a", name: "old" }],
      [{ uuid: "a", name: "new" }],
      (r: { uuid: string; name: string }) => r.uuid,
    );
    expect(merged).toEqual([{ uuid: "a", name: "old" }]);
  });

  it("handles an empty incoming page", () => {
    expect(mergePage([{ uuid: "a" }], [], key).map(key)).toEqual(["a"]);
  });
});

describe("hasMorePages", () => {
  it("uses totalCount when the server sends it", () => {
    expect(hasMorePages(25, { lastUuid: "u25", totalCount: 300 }, 25)).toBe(true);
    expect(hasMorePages(300, { lastUuid: "u300", totalCount: 300 }, 25)).toBe(false);
  });

  it("is false once every row is loaded, even on an exactly-full page", () => {
    expect(hasMorePages(100, { lastUuid: "u100", totalCount: 100 }, 100, 100)).toBe(false);
  });

  it("falls back to a full last page when totalCount is absent", () => {
    expect(hasMorePages(100, { lastUuid: "u100" }, 100, 100)).toBe(true);
    expect(hasMorePages(40, { lastUuid: "u40" }, 40, 100)).toBe(false);
  });

  it("is false without a cursor — a 'load more' that cannot seek is a button that does nothing", () => {
    expect(hasMorePages(100, { totalCount: 300 }, 100, 100)).toBe(false);
    expect(hasMorePages(100, undefined, 100, 100)).toBe(false);
    expect(hasMorePages(0, {}, 0, 100)).toBe(false);
  });
});

describe("nextPaging", () => {
  it("seeks from the last uuid", () => {
    expect(nextPaging({ lastUuid: "u9", totalCount: 300 })).toEqual({ limit: PAGE_SIZE, from: "u9" });
  });

  it("honours a custom page size", () => {
    expect(nextPaging({ lastUuid: "u9" }, 25)).toEqual({ limit: 25, from: "u9" });
  });

  it("returns null without a cursor — refetching page one would duplicate rows", () => {
    expect(nextPaging(undefined)).toBeNull();
    expect(nextPaging({ totalCount: 300 })).toBeNull();
    expect(nextPaging({ lastUuid: "" })).toBeNull();
  });
});

describe("isTruncated / displayCount", () => {
  it("reports truncation only when rows are actually missing", () => {
    expect(isTruncated(25, { totalCount: 300 })).toBe(true);
    expect(isTruncated(300, { totalCount: 300 })).toBe(false);
    expect(isTruncated(12, undefined)).toBe(false);
  });

  it("prefers the server total over the loaded length", () => {
    expect(displayCount(25, { totalCount: 300 })).toBe(300);
  });

  it("falls back to the loaded length when the server sends no total", () => {
    expect(displayCount(25, undefined)).toBe(25);
    expect(displayCount(25, { lastUuid: "x" })).toBe(25);
  });
});
