import { family } from "./contentTypes";
import type { PortPayload, PortDataElement } from "../../api/pipelines";

/**
 * Turning a node's raw output into something a human can read at a glance.
 *
 * The switch is on the **content-type family**, not on the node kind. That is the whole point:
 * the vocabulary (`media`, `text`, `hash`, `detection`, `scalar`, `struct`, `control`, `artifact`)
 * is a closed set the server publishes, so a node nobody has written yet still renders correctly
 * the day it is announced — provided it declares its ports honestly.
 *
 * A node that wants better than the family default can say so itself; see the `preview` hook on
 * the Cortex node API. This module is the floor, not the ceiling.
 *
 * Pure and DOM-free on purpose, so it can be unit-tested in the node environment the way
 * `contentTypes.ts` and `portResolvers.ts` are.
 */

/** How a payload should be presented, beyond its one-line label. */
export type PreviewKind =
  /** A file reference — a path or URI. Worth truncating from the left, not the right. */
  | "media"
  /** Prose or a transcript. Worth showing at length in the detail view. */
  | "text"
  /** A long hex digest. Only its head is informative at a glance. */
  | "hash"
  /** A structured record, usually with a box and a score. */
  | "detection"
  /** A number, a boolean, an enum-ish string. Fits inline verbatim. */
  | "scalar"
  /** Anything JSON-shaped that is not one of the above. */
  | "struct"
  /** A routing verdict — PASS / REJECT and friends. */
  | "control"
  /** A file the node produced, referenced by a worker-local path. */
  | "artifact"
  /** No declared family, or a payload we could not read. */
  | "unknown";

export interface PayloadSummary {
  kind: PreviewKind;
  /** One line, safe to render inside a node card. Never longer than ~40 chars. */
  label: string;
  /** How many elements the payload carries. */
  count: number;
  /** True when the payload declares MANY, even if it happens to carry one element. */
  many: boolean;
  /** True when there is nothing to show — an unwritten selective port, typically. */
  empty: boolean;
}

/** Longest single-line label we will produce. Beyond this the node card starts to wrap. */
const MAX_LABEL = 40;

/** How much of a digest is enough to recognise it, and to tell two apart in a list. */
const HASH_HEAD = 10;

function truncate(text: string, max = MAX_LABEL): string {
  if (text.length <= max) return text;
  return text.slice(0, max - 1) + "…";
}

/**
 * Truncate a path from the *left*.
 *
 * The tail of `/very/long/library/path/clip.mp4` is the part that identifies it; the head is
 * shared by every item in the run and tells you nothing.
 */
function truncatePath(path: string, max = MAX_LABEL): string {
  if (path.length <= max) return path;
  return "…" + path.slice(path.length - (max - 1));
}

/** Render one element's value as a single line, without knowing what it is. */
export function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return "∅";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    // A cyclic structure cannot come off the wire, but String() is a total function and
    // returning something is better than throwing inside a render.
    return String(value);
  }
}

function labelForElement(kind: PreviewKind, value: unknown): string {
  const text = stringifyValue(value);
  switch (kind) {
    case "media":
    case "artifact":
      return truncatePath(text);
    case "hash":
      return text.length > HASH_HEAD ? text.slice(0, HASH_HEAD) + "…" : text;
    case "detection": {
      // A detection is a record; its score is the one field that means the same thing across
      // every detector, so lead with it when it is there.
      if (value && typeof value === "object" && !Array.isArray(value)) {
        const record = value as Record<string, unknown>;
        const score = record.score ?? record.confidence;
        if (typeof score === "number") return `score ${score.toFixed(2)}`;
      }
      return truncate(text);
    }
    case "struct": {
      if (Array.isArray(value)) return `[${value.length} values]`;
      if (value && typeof value === "object") {
        const keys = Object.keys(value as Record<string, unknown>);
        return truncate(keys.length ? `{${keys.slice(0, 3).join(", ")}${keys.length > 3 ? ", …" : ""}}` : "{}");
      }
      return truncate(text);
    }
    default:
      return truncate(text);
  }
}

/** Map a content type onto the renderer family. Unknown or absent families fall back to `unknown`. */
export function previewKind(contentType: string | null | undefined): PreviewKind {
  switch (family(contentType)) {
    case "media": return "media";
    case "text": return "text";
    case "hash": return "hash";
    case "detection": return "detection";
    case "scalar": return "scalar";
    case "struct": return "struct";
    case "control": return "control";
    case "artifact": return "artifact";
    default: return "unknown";
  }
}

/**
 * Condense a payload into one line for a node card or a sidebar row.
 *
 * A `MANY` payload is summarised as its first element plus a count rather than as a list: the
 * strip has one line per port and a fanned-out detection port can carry fifty elements.
 */
export function summarizePayload(payload: PortPayload | null | undefined): PayloadSummary {
  const kind = previewKind(payload?.contentType);
  const elements = payload?.elements ?? [];
  const many = payload?.cardinality === "MANY";

  if (elements.length === 0) {
    // Not an error: leaving a selective output unwritten is how a node routes, and a filter
    // does it on every item that does not match.
    return { kind, label: "∅", count: 0, many, empty: true };
  }

  const first = labelForElement(kind, elements[0].value);
  const label = elements.length === 1 ? truncate(first) : truncate(`${first} +${elements.length - 1}`);
  return { kind, label, count: elements.length, many, empty: false };
}

/** Escape the cell separator so a value containing a pipe cannot break the table apart. */
function escapeCell(text: string): string {
  return text.replace(/\|/g, "\\|").replace(/\n/g, " ");
}

/**
 * Render a payload as a GFM table.
 *
 * Two shapes, chosen by what the elements actually are:
 *
 * - **Records** (every element is a plain object) → one column per key, unioned across elements
 *   so a detector that omits `landmarks` on some faces still lines up.
 * - **Anything else** → a two-column `#`/`value` table.
 *
 * Returns an empty string for an empty payload, so a caller can fall back rather than render a
 * table with no rows.
 */
export function payloadToMarkdownTable(payload: PortPayload | null | undefined): string {
  const elements: PortDataElement[] = payload?.elements ?? [];
  if (elements.length === 0) return "";

  const allRecords = elements.every(
    e => e.value !== null && typeof e.value === "object" && !Array.isArray(e.value),
  );

  if (allRecords) {
    // Union the keys in first-seen order. Sorting them would reorder a detector's natural
    // box/score/landmarks reading order into something alphabetical and less useful.
    const columns: string[] = [];
    for (const element of elements) {
      for (const key of Object.keys(element.value as Record<string, unknown>)) {
        if (!columns.includes(key)) columns.push(key);
      }
    }
    if (columns.length === 0) return "";
    const header = `| # | ${columns.map(escapeCell).join(" | ")} |`;
    const divider = `| --- | ${columns.map(() => "---").join(" | ")} |`;
    const rows = elements.map((element, i) => {
      const record = element.value as Record<string, unknown>;
      const cells = columns.map(key => (key in record ? escapeCell(stringifyValue(record[key])) : ""));
      return `| ${element.origin?.seq ?? i} | ${cells.join(" | ")} |`;
    });
    return [header, divider, ...rows].join("\n");
  }

  const rows = elements.map((element, i) =>
    `| ${element.origin?.seq ?? i} | ${escapeCell(stringifyValue(element.value))} |`);
  return ["| # | value |", "| --- | --- |", ...rows].join("\n");
}
