import { describe, expect, it } from "vitest";
import { moveSectionBoundary } from "./transcriptMapping";
import type { TranscriptSection, TranscriptWord } from "../../types";

function w(word: string, startTime: number): TranscriptWord {
  return { word, startTime, endTime: startTime + 0.3, confidence: 1 };
}

function sections(): TranscriptSection[] {
  return [
    { id: "a", title: "A", startTime: 0, endTime: 10, words: [w("one", 1), w("two", 9.6)] },
    { id: "b", title: "B", startTime: 10, endTime: 20, words: [w("three", 10.2), w("four", 15)] },
    { id: "c", title: "C", startTime: 20, endTime: 30, words: [w("five", 25)] },
  ];
}

const allWords = (s: TranscriptSection[]) => s.flatMap(x => x.words.map(y => y.word));

describe("moveSectionBoundary", () => {
  it("up moves the boundary above the section earlier and hands the crossed words down", () => {
    const out = moveSectionBoundary(sections(), 1, "up");
    expect(out[0].endTime).toBe(9.5);
    expect(out[1].startTime).toBe(9.5);
    expect(out[0].words.map(x => x.word)).toEqual(["one"]);
    expect(out[1].words.map(x => x.word)).toEqual(["two", "three", "four"]);
    // The boundary below is untouched.
    expect(out[1].endTime).toBe(20);
    expect(out[2].startTime).toBe(20);
  });

  it("down moves the same boundary later, not the one below the section", () => {
    const out = moveSectionBoundary(sections(), 1, "down");
    expect(out[0].endTime).toBe(10.5);
    expect(out[1].startTime).toBe(10.5);
    expect(out[1].endTime).toBe(20);
    expect(out[2].startTime).toBe(20);
    expect(out[0].words.map(x => x.word)).toEqual(["one", "two", "three"]);
    expect(out[1].words.map(x => x.word)).toEqual(["four"]);
  });

  it("never loses a word, whichever way and however often it moves", () => {
    let s = sections();
    const before = allWords(s).sort();
    for (const d of ["down", "down", "up", "down", "up", "up", "up"] as const) {
      s = moveSectionBoundary(s, 1, d);
      s = moveSectionBoundary(s, 2, d);
      expect(allWords(s).sort()).toEqual(before);
    }
  });

  it("works on the last section's boundary in both directions", () => {
    expect(moveSectionBoundary(sections(), 2, "down")[2].startTime).toBe(20.5);
    expect(moveSectionBoundary(sections(), 2, "up")[2].startTime).toBe(19.5);
  });

  it("stops short of collapsing a neighbour and returns the input when it cannot move", () => {
    const tight: TranscriptSection[] = [
      { id: "a", title: "A", startTime: 0, endTime: 0.5, words: [] },
      { id: "b", title: "B", startTime: 0.5, endTime: 1, words: [] },
    ];
    expect(moveSectionBoundary(tight, 1, "up")).toBe(tight);
    expect(moveSectionBoundary(tight, 1, "down")).toBe(tight);
  });

  it("ignores the first section, which has no boundary above it", () => {
    const s = sections();
    expect(moveSectionBoundary(s, 0, "up")).toBe(s);
  });
});
