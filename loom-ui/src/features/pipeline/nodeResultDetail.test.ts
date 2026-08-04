import { describe, expect, it } from "vitest";
import { viewersFor } from "./NodeResultDetail";
import type { NodePreviewMeta, PortPayload } from "../../api/pipelines";

/**
 * Which viewers a port offers, and in what order.
 *
 * The order is the offer order — the first one opens — so these tests are really about which
 * rendering wins when several could apply.
 */

/** The i18n stub returns the key, so assertions read against ids rather than translations. */
const t = (key: string) => key;

function payload(contentType: string, cardinality: "ONE" | "MANY", ...values: unknown[]): PortPayload {
  return {
    contentType,
    cardinality,
    elements: values.map((value, seq) => ({ origin: { itemId: "i1", seq, total: values.length }, value })),
  };
}

const ids = (p: PortPayload | null, preview?: NodePreviewMeta) => viewersFor(p, preview, t).map(v => v.id);

describe("viewersFor", () => {
  it("always offers Raw, even for an empty payload", () => {
    // Whatever else failed to apply, the payload itself is still readable. A detail view with
    // no tabs at all would be a dead end.
    expect(ids(payload("scalar/integer", "ONE"))).toEqual(["raw"]);
    expect(ids(null)).toEqual(["raw"]);
  });

  it("offers Value and Raw for an ordinary scalar", () => {
    expect(ids(payload("scalar/integer", "ONE", 7))).toEqual(["json", "raw"]);
  });

  it("offers a table for a MANY payload of records", () => {
    const p = payload("detection/face", "MANY", { score: 0.9 }, { score: 0.7 });
    expect(ids(p)).toEqual(["markdown", "json", "raw"]);
  });

  it("opens on the node's own description when it wrote one", () => {
    // A node knows things the content type does not, so its description outranks every default
    // — including the image it may also have produced.
    const p = payload("detection/face", "MANY", { score: 0.9 });
    const preview: NodePreviewMeta = { markdown: "| # | score |\n|---|---|\n| 0 | 0.9 |" };
    expect(ids(p, preview)[0]).toBe("markdown");
  });

  it("offers the image when the worker sent one back", () => {
    const p = payload("artifact/image", "ONE", "/var/cortex/thumb.jpg");
    expect(ids(p, { url: "/api/v1/x", mimeType: "image/jpeg" })).toContain("image");
  });

  it("puts a node's description ahead of its image, and keeps both", () => {
    const p = payload("artifact/image", "ONE", "/var/cortex/thumb.jpg");
    const viewers = ids(p, { url: "/api/v1/x", markdown: "cropped to the detected face" });
    expect(viewers[0]).toBe("markdown");
    expect(viewers).toContain("image");
  });

  it("offers a player only for a playable media value", () => {
    expect(ids(payload("media/video", "ONE", "/media/clip.mp4"))).toContain("media");
    expect(ids(payload("media/audio", "ONE", "/media/track.wav"))).toContain("media");
    // A media port whose value is not a recognisable media file has nothing to play.
    expect(ids(payload("media/video", "ONE", "/media/notes.txt"))).not.toContain("media");
    // …and an artifact path that happens to end in .mp4 is not a `media` port.
    expect(ids(payload("artifact/file", "ONE", "/var/out.mp4"))).not.toContain("media");
  });

  it("does not offer a table for a payload that would produce none", () => {
    // payloadToMarkdownTable returns "" for an empty payload; offering an empty tab would be a
    // tab that renders nothing.
    expect(ids(payload("detection/face", "MANY"))).toEqual(["raw"]);
  });

  it("keeps a skipped preview from adding an image tab", () => {
    // A capped preview has a reason and no url — there is nothing to show.
    const p = payload("artifact/image", "ONE", "/var/cortex/huge.jpg");
    expect(ids(p, { skippedReason: "Preview exceeds 98304 bytes" })).not.toContain("image");
  });
});
