import { describe, expect, it } from "vitest";
import { filterExpression, pagingQuery, withPaging } from "./paging";

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

  it("serializes the sort column and direction", () => {
    expect(pagingQuery({ sort: "name", dir: "asc" })).toBe("?sort=name&dir=asc");
    expect(pagingQuery({ sort: "edited", dir: "desc" })).toBe("?sort=edited&dir=desc");
  });

  it("drops a direction with no column — on its own it would reverse the default uuid order", () => {
    expect(pagingQuery({ dir: "desc" })).toBe("");
  });

  it("serializes a filter in the server's LHS grammar", () => {
    expect(pagingQuery({ filters: [{ key: "creator", value: "u-1" }] }))
      .toBe("?filter=creator%5Beq%5D%3Du-1");
  });

  it("joins several filters with a comma into one parameter", () => {
    // Repeating the key would earn "Parameter filter was found multiple times"; the parser splits
    // a single value on the comma instead.
    const query = pagingQuery({ filters: [{ key: "creator", value: "u-1" }, { key: "collection", value: "c-9" }] });
    expect(new URLSearchParams(query.slice(1)).get("filter")).toBe("creator[eq]=u-1,collection[eq]=c-9");
  });

  it("omits blank filter values rather than sending an empty term", () => {
    expect(pagingQuery({ filters: [{ key: "creator", value: "" }] })).toBe("");
    expect(pagingQuery({ filters: [] })).toBe("");
  });

  it("carries paging, sorting and filtering together", () => {
    const params = new URLSearchParams(
      pagingQuery({ limit: 25, from: "abc", sort: "created", dir: "desc", filters: [{ key: "creator", value: "u-1" }] }).slice(1),
    );
    expect(params.get("limit")).toBe("25");
    expect(params.get("from")).toBe("abc");
    expect(params.get("sort")).toBe("created");
    expect(params.get("dir")).toBe("desc");
    expect(params.get("filter")).toBe("creator[eq]=u-1");
  });
});

describe("filterExpression", () => {
  it("is empty for nothing to filter on", () => {
    expect(filterExpression()).toBe("");
    expect(filterExpression([])).toBe("");
  });

  it("renders one term", () => {
    expect(filterExpression([{ key: "name", value: "holiday" }])).toBe("name[eq]=holiday");
  });

  it("skips terms with no value, keeping the ones that have one", () => {
    expect(filterExpression([{ key: "creator", value: "" }, { key: "collection", value: "c-1" }]))
      .toBe("collection[eq]=c-1");
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
