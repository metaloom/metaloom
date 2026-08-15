import { describe, expect, it } from "vitest";
import { sortLocally, type SortState } from "./ListControls";

/**
 * `sortLocally` is the comparator used by the screens that are not backed by a Loom list route —
 * the Cortex worker registry and agent memory. Everywhere else sorting is a query parameter, so
 * this covers the exception rather than the rule.
 */

const asc = (sort: SortState["sort"]): SortState => ({ sort, dir: "asc" });
const desc = (sort: SortState["sort"]): SortState => ({ sort, dir: "desc" });

const ROWS = [
  { name: "delta", created: "2026-01-04T00:00:00Z", edited: "2026-02-01T00:00:00Z" },
  { name: "alpha", created: "2026-01-02T00:00:00Z", edited: "2026-02-04T00:00:00Z" },
  { name: "charlie", created: "2026-01-01T00:00:00Z", edited: "2026-02-02T00:00:00Z" },
  { name: "bravo", created: "2026-01-03T00:00:00Z", edited: "2026-02-03T00:00:00Z" },
];

const names = (rows: Array<{ name?: string | null }>) => rows.map(r => r.name);

describe("sortLocally", () => {
  it("orders by name", () => {
    expect(names(sortLocally(ROWS, asc("name")))).toEqual(["alpha", "bravo", "charlie", "delta"]);
  });

  it("orders by name descending", () => {
    expect(names(sortLocally(ROWS, desc("name")))).toEqual(["delta", "charlie", "bravo", "alpha"]);
  });

  it("orders by creation date, which is a different order than by name", () => {
    expect(names(sortLocally(ROWS, asc("created")))).toEqual(["charlie", "alpha", "bravo", "delta"]);
  });

  it("orders by edit date, which is a different order again", () => {
    expect(names(sortLocally(ROWS, asc("edited")))).toEqual(["delta", "charlie", "bravo", "alpha"]);
  });

  it("does not mutate the input", () => {
    const input = [...ROWS];
    sortLocally(input, desc("name"));
    expect(names(input)).toEqual(["delta", "alpha", "charlie", "bravo"]);
  });

  it("compares epoch numbers numerically, not as strings", () => {
    // "9" > "10" lexically, which is the bug this guards.
    const rows = [{ name: "ten", created: 10 }, { name: "nine", created: 9 }];
    expect(names(sortLocally(rows, asc("created")))).toEqual(["nine", "ten"]);
  });

  it("uses locale collation for names", () => {
    const rows = [{ name: "Zebra" }, { name: "Äpfel" }, { name: "Banane" }];
    // Code-point order would put "Äpfel" (U+00C4) after "Zebra".
    expect(names(sortLocally(rows, asc("name")))).toEqual(["Äpfel", "Banane", "Zebra"]);
  });

  it("keeps rows with no value at the bottom in both directions", () => {
    const rows = [{ name: "b" }, { name: undefined }, { name: "a" }];
    // A missing name is unknown, not "first alphabetically" — flipping the direction must not
    // promote it to the top of the list.
    expect(names(sortLocally(rows, asc("name")))).toEqual(["a", "b", undefined]);
    expect(names(sortLocally(rows, desc("name")))).toEqual(["b", "a", undefined]);
  });

  it("leaves an empty list alone", () => {
    expect(sortLocally([], asc("name"))).toEqual([]);
  });
});
