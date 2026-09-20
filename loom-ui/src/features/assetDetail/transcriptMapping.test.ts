import { describe, expect, it } from "vitest";
import { asrSegmentsToSections } from "./transcriptMapping";

/** One utterance, in the shape the whisper node writes: milliseconds into the asset. */
function seg(from: number, to: number, text: string) {
  return { from, to, text };
}

describe("asrSegmentsToSections", () => {
  it("turns whisper utterances into seekable lines", () => {
    const sections = asrSegmentsToSections([seg(0, 7000, " Good afternoon,"), seg(7000, 8000, " and welcome.")]);

    expect(sections).toHaveLength(1);
    expect(sections[0].startTime).toBe(0);
    expect(sections[0].endTime).toBe(8);
    // Milliseconds in, seconds out: the timeline, the seek and formatDuration all speak seconds.
    expect(sections[0].words.map(w => w.word)).toEqual(["Good afternoon,", "and welcome."]);
    expect(sections[0].words[1].startTime).toBe(7);
  });

  it("starts a new section once a minute has passed", () => {
    // 900 sentences in one section would give the section bar 900 slivers and the boundary
    // arrows nothing useful to move. Chapter-sized blocks are what make the bar readable.
    const sections = asrSegmentsToSections([seg(0, 1000, "a"), seg(59_000, 60_000, "b"), seg(61_000, 62_000, "c")]);

    expect(sections).toHaveLength(2);
    expect(sections[0].words).toHaveLength(2);
    expect(sections[1].words).toHaveLength(1);
    expect(sections[1].startTime).toBe(61);
  });

  it("titles each section with where it starts", () => {
    const sections = asrSegmentsToSections([seg(0, 1000, "a"), seg(125_000, 126_000, "b")]);
    expect(sections.map(s => s.title)).toEqual(["0:00", "2:05"]);
  });

  it("drops empty utterances rather than rendering blank lines", () => {
    // Whisper emits these at the end of a chunk; they are a seek target with nothing to read.
    expect(asrSegmentsToSections([seg(0, 100, "  "), seg(100, 200, "real")])).toHaveLength(1);
    expect(asrSegmentsToSections([])).toEqual([]);
    expect(asrSegmentsToSections(undefined)).toEqual([]);
  });

  it("survives an utterance with no end", () => {
    const sections = asrSegmentsToSections([{ from: 5000, text: "orphan" }]);
    expect(sections[0].startTime).toBe(5);
    expect(sections[0].endTime).toBe(5);
  });
});
