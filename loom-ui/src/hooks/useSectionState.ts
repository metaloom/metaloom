import { useCallback, useEffect, useState } from "react";

/** One entry per view that folds sections, so two screens cannot overwrite each other. */
export type SectionState = Record<string, boolean>;

function read(key: string): SectionState {
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    // Only booleans survive: a value written by an older shape of this state must not decide
    // whether a section is open.
    const out: SectionState = {};
    for (const [k, v] of Object.entries(parsed)) {
      if (typeof v === "boolean") out[k] = v;
    }
    return out;
  } catch {
    return {};
  }
}

/**
 * Which sections are open, remembered across visits.
 *
 * <p>Stored per view rather than per asset: "I never want to see the locations block" is a
 * statement about how someone works, not about one file, and keying it by asset would mean the
 * preference silently resets on the next episode.</p>
 *
 * <p>`defaults` decides what an unvisited section does, so a new section can ship open without
 * anyone's stored state having to mention it.</p>
 */
export function useSectionState(storageKey: string, defaults: SectionState) {
  const [state, setState] = useState<SectionState>(() => read(storageKey));

  useEffect(() => {
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(state));
    } catch {
      /* private mode: the fold still works, it just does not survive a reload */
    }
  }, [storageKey, state]);

  const isExpanded = useCallback(
    (id: string) => state[id] ?? defaults[id] ?? true,
    [state, defaults],
  );

  const toggle = useCallback((id: string) => {
    setState(prev => ({ ...prev, [id]: !(prev[id] ?? defaults[id] ?? true) }));
  }, [defaults]);

  /**
   * Open a section without caring what it was.
   *
   * Distinct from {@link toggle} because the callers are different in kind: a toggle is somebody
   * pressing the heading, and this is the screen following a link into a section's contents —
   * "show me this moment in the transcript" must open the fold, and must not close it when it was
   * already open.
   */
  const expand = useCallback((id: string) => {
    setState(prev => (prev[id] === true ? prev : { ...prev, [id]: true }));
  }, []);

  return { isExpanded, toggle, expand };
}
