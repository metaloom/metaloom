import { describe, expect, it } from "vitest";

import { acceptFiles, dragCarriesFiles, formatBytes, kindOf, replaceItem, toItem } from "./attachmentState";

function fileOf(name: string, size: number, type = "text/plain"): File {
  const file = new File(["x"], name, { type });
  // File.size is derived from the parts and is read-only, so it is overridden for the size checks.
  Object.defineProperty(file, "size", { value: size });
  return file;
}

const LIMITS = { maxFiles: 3, maxBytes: 1000 };

describe("acceptFiles", () => {
  it("accepts files that fit", () => {
    const { accepted, rejected } = acceptFiles([fileOf("a.txt", 10)], 0, LIMITS);

    expect(accepted.map(f => f.name)).toEqual(["a.txt"]);
    expect(rejected).toEqual([]);
  });

  it("refuses a file over the size limit, naming the limit", () => {
    const { accepted, rejected } = acceptFiles([fileOf("big.txt", 5000)], 0, LIMITS);

    expect(accepted).toEqual([]);
    // The user has to be able to tell what would fit, not just that this did not.
    expect(rejected[0]).toContain("big.txt");
    expect(rejected[0]).toContain("1000 B");
  });

  it("counts the files already attached against the limit", () => {
    const { accepted, rejected } = acceptFiles([fileOf("a.txt", 10), fileOf("b.txt", 10)], 2, LIMITS);

    expect(accepted.map(f => f.name)).toEqual(["a.txt"]);
    expect(rejected).toHaveLength(1);
    expect(rejected[0]).toContain("b.txt");
  });

  it("accepts nothing once the chat is already full", () => {
    const { accepted, rejected } = acceptFiles([fileOf("a.txt", 10)], 3, LIMITS);

    expect(accepted).toEqual([]);
    expect(rejected).toHaveLength(1);
  });

  it("refuses an empty file, which is how a dropped folder arrives", () => {
    // Uploading it would produce an attachment the agent is then told it can read.
    const { accepted, rejected } = acceptFiles([fileOf("photos", 0, "")], 0, LIMITS);

    expect(accepted).toEqual([]);
    expect(rejected[0]).toContain("empty");
  });

  it("reports one message per refused file and keeps the rest", () => {
    const { accepted, rejected } = acceptFiles(
      [fileOf("ok.txt", 10), fileOf("big.txt", 5000), fileOf("empty.txt", 0)],
      0,
      LIMITS
    );

    expect(accepted.map(f => f.name)).toEqual(["ok.txt"]);
    expect(rejected).toHaveLength(2);
  });
});

describe("dragCarriesFiles", () => {
  it("is true for a file drag", () => {
    expect(dragCarriesFiles(["Files"])).toBe(true);
  });

  it("is false for a text selection, so selecting a word does not flash the overlay", () => {
    expect(dragCarriesFiles(["text/plain"])).toBe(false);
  });

  it("is false when the drag exposes no types at all", () => {
    expect(dragCarriesFiles(undefined)).toBe(false);
  });
});

describe("kindOf", () => {
  it("calls the text family readable", () => {
    expect(kindOf("text/markdown")).toBe("text");
    expect(kindOf("application/json")).toBe("text");
    expect(kindOf("application/vnd.api+json")).toBe("text");
    // Parameters on the content type must not defeat the match.
    expect(kindOf("text/csv; charset=utf-8")).toBe("text");
  });

  it("calls pictures images", () => {
    expect(kindOf("image/jpeg")).toBe("image");
  });

  it("calls everything else opaque, including PDF", () => {
    // Deliberate: Tika is not wired in, so promising a PDF is readable would be a lie.
    expect(kindOf("application/pdf")).toBe("opaque");
    expect(kindOf(undefined)).toBe("opaque");
  });

  it("classifies SVG as text, which is what can actually be done with it", () => {
    // image/svg+xml matches the +xml structured suffix first, and that is the useful answer: the
    // markup is readable, while the image sidecar decodes raster formats only. Matches the server,
    // where PlainTextExtractor.supports is consulted before the image check for the same reason.
    expect(kindOf("image/svg+xml")).toBe("text");
  });
});

describe("formatBytes", () => {
  it("matches what the server writes into the agent's prompt", () => {
    // AttachmentPromptBuilder.humanSize produces these exact strings; a chip and the agent quoting
    // the same file must not disagree about its size.
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(4096)).toBe("4 KB");
    expect(formatBytes(1_258_291)).toBe("1.2 MB");
    expect(formatBytes(2_147_483_648)).toBe("2.0 GB");
  });
});

describe("toItem", () => {
  it("carries the server uuid into both id and uuid", () => {
    // The uuid is what the agent is handed and what generate_image resolves, so a settled chip
    // must expose it.
    const item = toItem({ uuid: "a-1", filename: "brief.md", mimeType: "text/markdown", size: 12 });

    expect(item.uuid).toBe("a-1");
    expect(item.status).toBe("ready");
  });
});

describe("replaceItem", () => {
  it("patches one item and leaves the others identical", () => {
    const items = [toItem({ uuid: "a", filename: "a.txt", size: 1 }), toItem({ uuid: "b", filename: "b.txt", size: 2 })];

    const next = replaceItem(items, "a", { status: "failed", error: "nope" });

    expect(next[0].status).toBe("failed");
    expect(next[1]).toBe(items[1]);
  });

  it("is a no-op for an id that is not there", () => {
    const items = [toItem({ uuid: "a", filename: "a.txt", size: 1 })];

    expect(replaceItem(items, "missing", { status: "failed" })).toEqual(items);
  });
});
