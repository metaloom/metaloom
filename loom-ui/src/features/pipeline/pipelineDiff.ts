// ── Pipeline version diff ─────────────────────────────────────────────────
//
// Pure helpers backing the "Compare with current" view. Two pipeline
// `definition` objects (as returned by `loadPipelineVersion`) are normalized to
// stable, pretty-printed JSON and then compared line-by-line so an author can
// see exactly what a restore would reintroduce. No diff library is used — a
// compact LCS keeps the output deterministic and easy to test.

/** A single aligned row of the side-by-side diff. */
export interface DiffRow {
  /** Left-hand (base version) line, or `null` when the line was added. */
  left: string | null;
  /** Right-hand (current version) line, or `null` when the line was removed. */
  right: string | null;
  kind: "same" | "added" | "removed" | "changed";
}

/** Recursively sort object keys so cosmetic key ordering never shows as a diff. */
function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    return Object.fromEntries(entries.map(([k, v]) => [k, sortValue(v)]));
  }
  return value;
}

/**
 * Produce a stable, pretty-printed JSON string for a pipeline definition.
 *
 * Object keys are sorted recursively and the `nodes` / `edges` arrays are
 * ordered by `id`, so reordering alone never registers as a change — only real
 * structural or parameter edits surface. The raw server definition is already
 * free of the cosmetic React-Flow keys that live on the local canvas state, so
 * no extra stripping is required.
 */
export function normalizeDefinition(def: Record<string, unknown> | undefined): string {
  const source = (def ?? {}) as Record<string, unknown>;
  const ordered: Record<string, unknown> = { ...source };
  for (const key of ["nodes", "edges"]) {
    const arr = source[key];
    if (Array.isArray(arr)) {
      ordered[key] = [...arr].sort((a, b) => {
        const ai = String((a as Record<string, unknown>)?.id ?? "");
        const bi = String((b as Record<string, unknown>)?.id ?? "");
        return ai < bi ? -1 : ai > bi ? 1 : 0;
      });
    }
  }
  return JSON.stringify(sortValue(ordered), null, 2);
}

/**
 * Align two multi-line strings with a longest-common-subsequence diff.
 *
 * Runs of removed-then-added lines are folded into `"changed"` rows (paired
 * positionally) so an edited line reads as one row instead of a separate delete
 * and insert.
 */
export function diffLines(left: string, right: string): DiffRow[] {
  const a = left.split("\n");
  const b = right.split("\n");
  const n = a.length;
  const m = b.length;

  // LCS length table.
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j]
        ? lcs[i + 1][j + 1] + 1
        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }

  const rows: DiffRow[] = [];
  const flushBlock = (removed: string[], added: string[]) => {
    const paired = Math.min(removed.length, added.length);
    for (let k = 0; k < paired; k++) {
      rows.push({ left: removed[k], right: added[k], kind: "changed" });
    }
    for (let k = paired; k < removed.length; k++) {
      rows.push({ left: removed[k], right: null, kind: "removed" });
    }
    for (let k = paired; k < added.length; k++) {
      rows.push({ left: null, right: added[k], kind: "added" });
    }
  };

  let i = 0;
  let j = 0;
  let removed: string[] = [];
  let added: string[] = [];
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      flushBlock(removed, added);
      removed = [];
      added = [];
      rows.push({ left: a[i], right: b[j], kind: "same" });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      removed.push(a[i++]);
    } else {
      added.push(b[j++]);
    }
  }
  while (i < n) removed.push(a[i++]);
  while (j < m) added.push(b[j++]);
  flushBlock(removed, added);

  return rows;
}

/** True when at least one row represents an actual difference. */
export function hasChanges(rows: DiffRow[]): boolean {
  return rows.some(r => r.kind !== "same");
}
