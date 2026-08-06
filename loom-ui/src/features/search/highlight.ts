/** One run of characters from a highlight fragment, flagged as a match or not. */
export interface HighlightSegment {
  text: string;
  match: boolean;
}

/**
 * Longest fragment we will render.
 *
 * A fuzzy-only match can produce a snippet from the head of a long document rather than around a
 * term, so there is no natural upper bound on the server side.
 */
export const MAX_HIGHLIGHT_LENGTH = 400;

const B_TAG = /<\/?b\s*>/gi;

/**
 * Split one server-side highlight fragment into plain-text segments.
 *
 * The fragment is **not** sanitised HTML. It comes from `ts_headline`, which wraps matched
 * lexemes in `<b>`/`</b>` and otherwise returns the source document verbatim — Postgres does no
 * HTML escaping. That document is built by triggers from filenames, tag names, annotation bodies
 * and transcripts, all of which are user-supplied, so injecting it as markup would execute
 * whatever an uploader put in a filename for every user who can search.
 *
 * So: scan for the bold markers, treat everything between them as literal text, and let React
 * escape it on render. Any other markup in the document then renders as visible characters, which
 * is the correct failure mode for a search result.
 *
 * The invariant is that concatenating every returned `text` equals the input with only the `<b>`
 * and `</b>` markers removed — no character is ever dropped, however unbalanced the markup is.
 */
export function parseHighlight(fragment: string): HighlightSegment[] {
  if (!fragment) return [];

  const segments: HighlightSegment[] = [];
  let depth = 0;
  let cursor = 0;

  const push = (text: string, match: boolean) => {
    if (!text) return;
    const previous = segments[segments.length - 1];
    // Coalesce runs that ended up with the same flag, e.g. across an unbalanced closing tag.
    if (previous && previous.match === match) {
      previous.text += text;
      return;
    }
    segments.push({ text, match });
  };

  B_TAG.lastIndex = 0;
  let marker: RegExpExecArray | null;
  while ((marker = B_TAG.exec(fragment)) !== null) {
    push(fragment.slice(cursor, marker.index), depth > 0);
    depth = marker[0][1] === "/" ? Math.max(0, depth - 1) : depth + 1;
    cursor = marker.index + marker[0].length;
  }
  push(fragment.slice(cursor), depth > 0);

  return truncate(segments);
}

/** Cut the segment list off at MAX_HIGHLIGHT_LENGTH, on a character boundary, with an ellipsis. */
function truncate(segments: HighlightSegment[]): HighlightSegment[] {
  let remaining = MAX_HIGHLIGHT_LENGTH;
  const kept: HighlightSegment[] = [];

  for (const segment of segments) {
    if (segment.text.length <= remaining) {
      kept.push(segment);
      remaining -= segment.text.length;
      continue;
    }
    if (remaining > 0) {
      kept.push({ text: `${segment.text.slice(0, remaining)}…`, match: segment.match });
    }
    return kept;
  }

  return kept;
}
