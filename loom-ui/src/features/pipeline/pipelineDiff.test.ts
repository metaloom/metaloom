import { describe, expect, it } from "vitest";
import { diffLines, hasChanges, normalizeDefinition } from "./pipelineDiff";

describe("normalizeDefinition", () => {
  it("ignores key order and node/edge order", () => {
    const a = {
      nodes: [{ id: "b", type: "sha512" }, { id: "a", type: "source", label: "S" }],
      edges: [{ id: "e2", source: "a", target: "b" }, { id: "e1", target: "b", source: "a" }],
    };
    const b = {
      edges: [{ source: "a", id: "e1", target: "b" }, { target: "b", source: "a", id: "e2" }],
      nodes: [{ label: "S", type: "source", id: "a" }, { type: "sha512", id: "b" }],
    };
    expect(normalizeDefinition(a)).toBe(normalizeDefinition(b));
  });

  it("treats a missing definition as an empty object", () => {
    expect(normalizeDefinition(undefined)).toBe("{}");
  });

  it("keeps a real option change visible", () => {
    const a = { nodes: [{ id: "n", options: { chunkSize: 8192 } }] };
    const b = { nodes: [{ id: "n", options: { chunkSize: 4096 } }] };
    expect(normalizeDefinition(a)).not.toBe(normalizeDefinition(b));
  });
});

describe("diffLines", () => {
  it("reports identical input as all-same", () => {
    const rows = diffLines("a\nb\nc", "a\nb\nc");
    expect(rows.map(r => r.kind)).toEqual(["same", "same", "same"]);
    expect(hasChanges(rows)).toBe(false);
  });

  it("folds a removed-then-added line into one changed row", () => {
    const rows = diffLines("a\nold\nc", "a\nnew\nc");
    expect(rows).toEqual([
      { left: "a", right: "a", kind: "same" },
      { left: "old", right: "new", kind: "changed" },
      { left: "c", right: "c", kind: "same" },
    ]);
    expect(hasChanges(rows)).toBe(true);
  });

  it("marks pure insertions and deletions, including at the end", () => {
    expect(diffLines("a", "a\nb")).toEqual([
      { left: "a", right: "a", kind: "same" },
      { left: null, right: "b", kind: "added" },
    ]);
    expect(diffLines("a\nb", "a")).toEqual([
      { left: "a", right: "a", kind: "same" },
      { left: "b", right: null, kind: "removed" },
    ]);
  });

  it("pairs an uneven block positionally and spills the remainder", () => {
    const rows = diffLines("x\ny\nz", "p");
    expect(rows).toEqual([
      { left: "x", right: "p", kind: "changed" },
      { left: "y", right: null, kind: "removed" },
      { left: "z", right: null, kind: "removed" },
    ]);
  });

  it("diffs a normalized node addition as added lines only", () => {
    const base = normalizeDefinition({ nodes: [{ id: "a" }] });
    const current = normalizeDefinition({ nodes: [{ id: "a" }, { id: "b" }] });
    const kinds = new Set(diffLines(base, current).map(r => r.kind));
    expect(kinds.has("removed")).toBe(false);
    expect(kinds.has("added") || kinds.has("changed")).toBe(true);
  });
});
