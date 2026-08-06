import { describe, expect, it } from "vitest";
import { MAX_HIGHLIGHT_LENGTH, parseHighlight } from "./highlight";

/** The invariant: no character is ever lost, only the bold markers. */
function plainTextOf(segments: { text: string }[]): string {
  return segments.map((s) => s.text).join("");
}

describe("parseHighlight", () => {
  it("splits a fragment into matched and unmatched segments", () => {
    const segments = parseHighlight("costs by nearly a <b>third</b> this quarter");

    expect(segments).toEqual([
      { text: "costs by nearly a ", match: false },
      { text: "third", match: true },
      { text: " this quarter", match: false },
    ]);
  });

  it("returns a single unmatched segment when there is no markup", () => {
    expect(parseHighlight("no markup here")).toEqual([{ text: "no markup here", match: false }]);
  });

  it("returns nothing for an empty fragment", () => {
    expect(parseHighlight("")).toEqual([]);
  });

  it("handles several matches in one fragment", () => {
    const segments = parseHighlight("<b>quarterly</b> and <b>update</b>");

    expect(segments.filter((s) => s.match).map((s) => s.text)).toEqual(["quarterly", "update"]);
  });

  it("leaves foreign markup as literal text", () => {
    // ts_headline does not escape the source document, and the document is built from filenames.
    // This must come back as characters to render, never as markup to execute.
    const fragment = 'an asset named <img src=x onerror=alert(1)>.jpg with a <b>match</b>';
    const segments = parseHighlight(fragment);

    expect(segments[0].text).toContain("<img src=x onerror=alert(1)>.jpg");
    expect(segments[0].match).toBe(false);
    expect(segments.find((s) => s.match)?.text).toBe("match");
  });

  it("treats a script tag as literal text too", () => {
    const segments = parseHighlight("<script>alert(1)</script>");

    expect(segments).toEqual([{ text: "<script>alert(1)</script>", match: false }]);
  });

  it("never loses characters", () => {
    const fragment = "a <b>b</b> c <b>d</b> e";
    expect(plainTextOf(parseHighlight(fragment))).toBe("a b c d e");
  });

  it("survives an unbalanced closing tag without dropping text", () => {
    const segments = parseHighlight("orphan </b> close");

    expect(plainTextOf(segments)).toBe("orphan  close");
    expect(segments.every((s) => !s.match)).toBe(true);
  });

  it("survives an unclosed opening tag", () => {
    const segments = parseHighlight("start <b>never closed");

    expect(plainTextOf(segments)).toBe("start never closed");
    expect(segments[segments.length - 1].match).toBe(true);
  });

  it("handles nested tags without dropping text", () => {
    const segments = parseHighlight("a <b>b <b>c</b> d</b> e");

    expect(plainTextOf(segments)).toBe("a b c d e");
  });

  it("is case insensitive about the bold markers", () => {
    const segments = parseHighlight("a <B>bold</B> word");

    expect(segments.find((s) => s.match)?.text).toBe("bold");
  });

  it("truncates past the maximum length and marks the cut with an ellipsis", () => {
    const segments = parseHighlight("x".repeat(MAX_HIGHLIGHT_LENGTH + 100));

    const rendered = plainTextOf(segments);
    expect(rendered).toHaveLength(MAX_HIGHLIGHT_LENGTH + 1); // + the ellipsis
    expect(rendered.endsWith("…")).toBe(true);
  });

  it("does not truncate a fragment that fits", () => {
    const fragment = "short enough";
    expect(plainTextOf(parseHighlight(fragment))).toBe(fragment);
  });
});
