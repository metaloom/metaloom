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

