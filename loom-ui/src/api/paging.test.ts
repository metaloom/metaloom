import { describe, expect, it } from "vitest";
import { pagingQuery, withPaging } from "./paging";

describe("pagingQuery", () => {
  it("returns an empty string when no paging is requested", () => {
    expect(pagingQuery()).toBe("");
    expect(pagingQuery({})).toBe("");
  });

  it("serializes limit on its own", () => {
    expect(pagingQuery({ limit: 100 })).toBe("?limit=100");
  });

  it("serializes the seek cursor on its own", () => {
    expect(pagingQuery({ from: "e829f0f1-4775-4857-a326-850440cf9577" }))
      .toBe("?from=e829f0f1-4775-4857-a326-850440cf9577");
  });

  it("serializes both", () => {
    expect(pagingQuery({ limit: 50, from: "abc" })).toBe("?limit=50&from=abc");
  });

  it("keeps limit=0 — it is a value, not an absence", () => {
    expect(pagingQuery({ limit: 0 })).toBe("?limit=0");
  });

  it("never emits an empty from — the server parses `?from=` as a value and 400s", () => {
    expect(pagingQuery({ from: "" })).toBe("");
    expect(pagingQuery({ limit: 10, from: "" })).toBe("?limit=10");
  });

  it("url-encodes the cursor", () => {
    expect(pagingQuery({ from: "a b&c" })).toBe("?from=a+b%26c");
  });
});

describe("withPaging", () => {
  it("leaves the url untouched when there is nothing to add", () => {
    expect(withPaging("/memory?scope=user")).toBe("/memory?scope=user");
  });

  it("starts a query string when the url has none", () => {
    expect(withPaging("/assets", { limit: 25 })).toBe("/assets?limit=25");
  });

  it("appends with & when the url already has a query string", () => {
    expect(withPaging("/memory?scope=user", { limit: 25 }))
      .toBe("/memory?scope=user&limit=25");
  });
});
