import { describe, expect, it } from "vitest";
import {
  payloadToMarkdownTable,
  previewKind,
  stringifyValue,
  summarizePayload,
} from "./resultRenderers";
import type { PortPayload } from "../../api/pipelines";

function payload(contentType: string, cardinality: "ONE" | "MANY", ...values: unknown[]): PortPayload {
  return {
    contentType,
    cardinality,
    elements: values.map((value, seq) => ({ origin: { itemId: "i1", seq, total: values.length }, value })),
  };
}

describe("previewKind", () => {
  // One case per family in the served vocabulary. If the server grows a ninth family this
  // test is what notices that the renderer silently fell through to "unknown".
  it.each([
    ["media/video", "media"],
    ["text/transcript", "text"],
    ["hash/sha512", "hash"],
    ["detection/face", "detection"],
    ["scalar/integer", "scalar"],
    ["struct/embedding", "struct"],
    ["control/branch", "control"],
    ["artifact/image", "artifact"],
  ])("maps %s to %s", (contentType, expected) => {
    expect(previewKind(contentType)).toBe(expected);
  });

  it("maps a family wildcard by its family", () => {
    expect(previewKind("media/*")).toBe("media");
  });

  it("falls back to unknown for a missing or malformed type", () => {
    expect(previewKind(undefined)).toBe("unknown");
    expect(previewKind("")).toBe("unknown");
    expect(previewKind("nofamily")).toBe("unknown");
  });
});

describe("summarizePayload", () => {
  it("labels a single value verbatim", () => {
    const summary = summarizePayload(payload("scalar/integer", "ONE", 7));
    expect(summary).toMatchObject({ kind: "scalar", label: "7", count: 1, many: false, empty: false });
  });

  it("truncates a hash to a recognisable head", () => {
    const digest = "0f8ef1c9ab34de5678901234567890";
    expect(summarizePayload(payload("hash/sha512", "ONE", digest)).label).toBe("0f8ef1c9ab…");
  });

  it("truncates a media path from the left, keeping the filename", () => {
    const long = "/very/long/library/path/that/goes/on/and/on/clip.mp4";
    const label = summarizePayload(payload("media/video", "ONE", long)).label;
    expect(label.startsWith("…")).toBe(true);
    expect(label.endsWith("clip.mp4")).toBe(true);
  });

  it("leads a detection with its score, which every detector means the same way", () => {
    const box = { box: [10, 20, 64, 64], score: 0.9312, landmarks: [] };
    expect(summarizePayload(payload("detection/face", "ONE", box)).label).toBe("score 0.93");
  });

  it("summarises a MANY payload as its first element plus a count", () => {
    const summary = summarizePayload(payload("scalar/integer", "MANY", 1, 2, 3));
    expect(summary.label).toBe("1 +2");
    expect(summary.count).toBe(3);
    expect(summary.many).toBe(true);
  });

  it("reports an unwritten selective port as empty rather than as an error", () => {
    // A filter leaves its non-matching buckets unwritten on most items; that is routing
    // working, not a failure, and it must not read as one.
    const summary = summarizePayload(payload("media/image", "ONE"));
    expect(summary).toMatchObject({ empty: true, count: 0, label: "∅" });
  });

  it("survives a null payload", () => {
    expect(summarizePayload(null)).toMatchObject({ kind: "unknown", empty: true });
  });

  it("keeps every label within the node-card width", () => {
    const long = "x".repeat(500);
    for (const type of ["text/plain", "struct/embedding", "detection/face", "control/branch"]) {
      expect(summarizePayload(payload(type, "ONE", long)).label.length).toBeLessThanOrEqual(40);
    }
  });
});

describe("stringifyValue", () => {
  it("renders primitives without quoting them", () => {
    expect(stringifyValue("abc")).toBe("abc");
    expect(stringifyValue(3)).toBe("3");
    expect(stringifyValue(false)).toBe("false");
  });

  it("renders an absent value as the empty marker", () => {
    expect(stringifyValue(null)).toBe("∅");
    expect(stringifyValue(undefined)).toBe("∅");
  });
});

describe("payloadToMarkdownTable", () => {
  it("returns an empty string for an empty payload, so the caller can fall back", () => {
    expect(payloadToMarkdownTable(payload("detection/face", "MANY"))).toBe("");
    expect(payloadToMarkdownTable(null)).toBe("");
  });

  it("builds a column per key when every element is a record", () => {
    const table = payloadToMarkdownTable(payload(
      "detection/face", "MANY",
      { score: 0.9, label: "face" },
      { score: 0.7, label: "face" },
    ));
    expect(table.split("\n")).toEqual([
      "| # | score | label |",
      "| --- | --- | --- |",
      "| 0 | 0.9 | face |",
      "| 1 | 0.7 | face |",
    ]);
  });

  it("unions keys across elements so a partly-present field still lines up", () => {
    // A detector that only emits landmarks for some faces must not shear the table.
    const table = payloadToMarkdownTable(payload(
      "detection/face", "MANY",
      { score: 0.9 },
      { score: 0.7, landmarks: 5 },
    ));
    const lines = table.split("\n");
    expect(lines[0]).toBe("| # | score | landmarks |");
    expect(lines[2]).toBe("| 0 | 0.9 |  |");
    expect(lines[3]).toBe("| 1 | 0.7 | 5 |");
  });

  it("falls back to a value column for non-record elements", () => {
    const table = payloadToMarkdownTable(payload("scalar/integer", "MANY", 1, 2));
    expect(table.split("\n")).toEqual([
      "| # | value |",
      "| --- | --- |",
      "| 0 | 1 |",
      "| 1 | 2 |",
    ]);
  });

  it("escapes a pipe so a value cannot break the table apart", () => {
    const table = payloadToMarkdownTable(payload("text/plain", "ONE", "a|b"));
    expect(table).toContain("a\\|b");
  });

  it("flattens a newline, which would otherwise end the row early", () => {
    const table = payloadToMarkdownTable(payload("text/plain", "ONE", "line1\nline2"));
    expect(table).toContain("line1 line2");
    expect(table.split("\n")).toHaveLength(3);
  });

  it("numbers rows by their origin seq, not by array position", () => {
    // A fanned-out branch can arrive out of order or partially; the origin is what ties an
    // element back to the element of the upstream sequence it came from.
    const p: PortPayload = {
      contentType: "scalar/integer",
      cardinality: "MANY",
      elements: [
        { origin: { itemId: "i1", seq: 4, total: 5 }, value: "d" },
        { origin: { itemId: "i1", seq: 2, total: 5 }, value: "b" },
      ],
    };
    const lines = payloadToMarkdownTable(p).split("\n");
    expect(lines[2]).toBe("| 4 | d |");
    expect(lines[3]).toBe("| 2 | b |");
  });
});
