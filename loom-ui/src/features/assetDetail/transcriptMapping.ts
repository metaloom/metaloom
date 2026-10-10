import { TranscriptSection } from "../../types";
import { TranscriptResponse } from "../../api/transcripts";
import { formatDuration } from "./helpers";

/**
 * How long a run of ASR utterances to gather into one section, in seconds.
 *
 * An episode comes back as ~900 sentences. One section each would give the section bar 900 slivers
 * and the boundary arrows 900 places to move nothing useful, so they are folded into chapter-sized
 * blocks. A minute is short enough to scrub with and long enough that the bar stays readable.
 */
const ASR_SECTION_SECONDS = 60;

/**
 * Fold ASR utterances into the sections this view renders.
 *
 * <p>Whisper writes `transcriptJson.segments` — sentences with a start and an end — while this
 * view (and the hand-authored transcripts the Add Transcript dialog creates) speak in `sections`
 * of timed *words*. Nothing read `segments`, so every machine transcript on the deployment
 * rendered as an empty panel while its text sat in the database.</p>
 *
 * <p>Each utterance becomes one "word" entry, because that is the smallest thing whisper actually
 * timed: pretending to word-level timing by dividing a sentence over its span would put a seek
 * target on a moment nothing measured.</p>
 */
export function asrSegmentsToSections(segments: NonNullable<TranscriptResponse["transcriptJson"]>["segments"]): TranscriptSection[] {
  const sections: TranscriptSection[] = [];
  for (const seg of segments ?? []) {
    const startTime = (seg.from ?? 0) / 1000;
    const endTime = (seg.to ?? seg.from ?? 0) / 1000;
    const text = (seg.text ?? "").trim();
    if (!text) continue;
    const last = sections[sections.length - 1];
    if (last && startTime - last.startTime < ASR_SECTION_SECONDS) {
      last.endTime = Math.max(last.endTime, endTime);
      last.words.push({ word: text, startTime, endTime, confidence: 1 });
    } else {
      sections.push({
        id: `asr-${sections.length}`,
        title: formatDuration(Math.round(startTime)),
        startTime,
        endTime,
        words: [{ word: text, startTime, endTime, confidence: 1 }],
      });
    }
  }
  return sections;
}


/** How far one press of a boundary arrow moves it, in seconds. */
export const BOUNDARY_STEP_SECONDS = 0.5;

/** The shortest a section may be squeezed to by a boundary move, in seconds. */
const MIN_SECTION_SECONDS = 0.5;

/**
 * Move the boundary between `sections[idx - 1]` and `sections[idx]` one step earlier ("up") or
 * later ("down").
 *
 * <p>Both arrows sit on that one boundary, above section `idx`. "down" used to move the boundary
 * *below* section `idx` instead, so it nudged the wrong pair and did nothing on the last section.
 * Words that land on the other side of the new boundary change section; they used to be filtered
 * out of the later section and never handed to the earlier one, and since the change is saved at
 * once, every nudge deleted words from the stored transcript.</p>
 *
 * Returns the input unchanged when the boundary cannot move (first section, or a neighbour would
 * shrink below the minimum length).
 */
export function moveSectionBoundary(
  sections: TranscriptSection[],
  idx: number,
  direction: "up" | "down",
  step = BOUNDARY_STEP_SECONDS,
): TranscriptSection[] {
  if (idx <= 0 || idx >= sections.length) return sections;
  const prev = sections[idx - 1];
  const cur = sections[idx];
  const wanted = direction === "up" ? cur.startTime - step : cur.startTime + step;
  const boundary = Math.min(cur.endTime - MIN_SECTION_SECONDS, Math.max(prev.startTime + MIN_SECTION_SECONDS, wanted));
  if (boundary === cur.startTime) return sections;

  const words = [...prev.words, ...cur.words];
  const updated = [...sections];
  updated[idx - 1] = { ...prev, endTime: boundary, words: words.filter(w => w.startTime < boundary) };
  updated[idx] = { ...cur, startTime: boundary, words: words.filter(w => w.startTime >= boundary) };
  return updated;
}
