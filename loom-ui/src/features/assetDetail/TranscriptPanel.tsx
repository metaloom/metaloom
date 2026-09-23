import React, { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Chip, IconButton, InputBase, Tooltip, Typography } from "@mui/material";
import { ArrowUpwardOutlined, ArrowDownwardOutlined, SearchOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { TranscriptSection } from "../../types";
import { formatDuration } from "./helpers";

/**
 * The palette the chapters cycle through.
 *
 * Exported because the timeline draws the same chapters as tiles, and two lists of colours that
 * are meant to agree will not stay in agreement.
 */
export const TRANSCRIPT_SECTION_COLORS = [tokens.accent.blue, tokens.accent.green, tokens.accent.amber, "#c077db", tokens.primary.main, tokens.accent.red];

/**
 * How long a scrolled-to chapter stays ringed, in milliseconds.
 *
 * Long enough to find after the scroll settles, short enough that it is gone before it starts
 * reading as a selection - the playhead tint is what says "this is the chapter now", and two
 * permanent highlights on one panel would be one too many.
 */
export const TRANSCRIPT_REVEAL_MS = 1600;

/** Keyframes for the ring a revealed chapter wears. */
const REVEAL_KEYFRAMES = {
  "@keyframes loomTranscriptReveal": {
    "0%": { boxShadow: `0 0 0 0 ${tokens.primary.main}00` },
    "18%": { boxShadow: `0 0 0 4px ${tokens.primary.main}88` },
    "55%": { boxShadow: `0 0 0 4px ${tokens.primary.main}55` },
    "100%": { boxShadow: `0 0 0 0 ${tokens.primary.main}00` },
  },
};

export function TranscriptPanel({
  sections,
  currentTime,
  onSeek,
  onSectionsChange,
  reveal,
}: {
  sections: TranscriptSection[];
  currentTime: number;
  onSeek: (t: number) => void;
  onSectionsChange: (s: TranscriptSection[]) => void;
  /**
   * A moment somewhere else on the screen asked this panel to show.
   *
   * A timeline chapter tile and a transcript search hit both point at a second of audio, and
   * seeking to it is only half of what they mean: on a 43-minute episode the chapter they picked
   * is several screens down a scroller, and a panel that silently repainted a highlight far below
   * the fold looked like it had ignored the click. The nonce is what makes clicking the same tile
   * twice scroll twice — the time alone would be an unchanged prop.
   */
  reveal?: { time: number; nonce: number } | null;
}) {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const sectionColors = TRANSCRIPT_SECTION_COLORS;

  // Local draft for the section title being edited; commits to onSectionsChange on blur.
  const [editingTitle, setEditingTitle] = useState<{ id: string; value: string } | null>(null);

  /**
   * Find-in-transcript.
   *
   * A transcript is the one place in the UI where "search" means jumping to a moment rather than
   * narrowing a list, so this keeps every section on screen and marks the hits instead of hiding
   * the misses — the timeline bar above has to stay proportional, and a filtered transcript would
   * make the boundary arrows move things they no longer sit between.
   */
  const [query, setQuery] = useState("");
  const term = query.trim().toLowerCase();
  const matchedSections = useMemo(() => {
    if (!term) return null;
    const hit = new Set<string>();
    for (const section of sections) {
      if (section.title?.toLowerCase().includes(term)
        || section.words.some(w => w.word.toLowerCase().includes(term))) {
        hit.add(section.id);
      }
    }
    return hit;
  }, [sections, term]);

  const matchCount = matchedSections?.size ?? 0;
  const isMatch = (word: string) => Boolean(term) && word.toLowerCase().includes(term);

  const commitTitle = (idx: number, value: string) => {
    setEditingTitle(null);
    if (value === sections[idx].title) return;
    onSectionsChange(sections.map((s, i) => (i === idx ? { ...s, title: value } : s)));
  };

  /**
   * Scroll the chapter holding {@link reveal} into view, and ring it while the eye catches up.
   *
   * Two scrolls rather than one: the fold above may have been shut when the click happened, so
   * this panel is mounting inside a `Collapse` that is still animating and the first
   * `scrollIntoView` measures a box that has not finished growing. The second one, after the
   * animation, lands it. Scrolling twice to the same place is invisible; scrolling to the wrong
   * place once is the bug.
   */
  const sectionRefs = useRef<Record<string, HTMLElement | null>>({});
  const [revealedId, setRevealedId] = useState<string | null>(null);

  useEffect(() => {
    if (!reveal) return;
    const at = reveal.time;
    const hit = sections.find(s => at >= s.startTime - 0.05 && at <= s.endTime)
      // Past the end of this transcript - a second transcript on the asset covers the moment, or
      // the chapter boundaries and the player disagree by a frame. Either way, do not scroll.
      ?? null;
    if (!hit) {
      setRevealedId(null);
      return;
    }
    setRevealedId(hit.id);
    const scroll = () => sectionRefs.current[hit.id]?.scrollIntoView({ block: "center", behavior: "smooth" });
    const raf = window.requestAnimationFrame(scroll);
    const settle = window.setTimeout(scroll, 340);
    const clear = window.setTimeout(() => setRevealedId(null), TRANSCRIPT_REVEAL_MS);
    return () => {
      window.cancelAnimationFrame(raf);
      window.clearTimeout(settle);
      window.clearTimeout(clear);
    };
  }, [reveal, sections]);

  const moveBoundary = (idx: number, direction: "up" | "down") => {
    const updated = [...sections];
    const step = 0.5;
    if (direction === "up" && idx > 0) {
      const newTime = Math.max(updated[idx - 1].startTime + 0.5, updated[idx].startTime - step);
      updated[idx - 1] = { ...updated[idx - 1], endTime: newTime };
      updated[idx] = { ...updated[idx], startTime: newTime, words: updated[idx].words.filter(w => w.startTime >= newTime) };
    } else if (direction === "down" && idx < updated.length - 1) {
      const newTime = Math.min(updated[idx + 1].endTime - 0.5, updated[idx].endTime + step);
      updated[idx] = { ...updated[idx], endTime: newTime };
      updated[idx + 1] = { ...updated[idx + 1], startTime: newTime, words: updated[idx + 1].words.filter(w => w.startTime >= newTime) };
    }
    onSectionsChange(updated);
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0, overflow: "auto" }}>
      {sections.length > 0 && (
        /* `pt`, because this panel sits directly under the fold heading and the box was against it. */
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, pt: 1, mb: 1, px: 0.5 }}>
          <InputBase
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={tAD("transcript.searchPlaceholder")}
            inputProps={{ "data-testid": "transcript-search" }}
            startAdornment={<SearchOutlined sx={{ fontSize: 15, color: tokens.text.tertiary, mr: 0.75 }} />}
            sx={{
              flex: 1, fontSize: "0.75rem", px: 1, py: 0.25,
              bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm,
            }}
          />
          {term && (
            <Typography variant="caption" data-testid="transcript-search-count"
              sx={{ fontSize: "0.68rem", color: matchCount ? tokens.text.secondary : tokens.accent.amber, whiteSpace: "nowrap" }}>
              {matchCount
                ? tAD("transcript.matchCount", { count: matchCount })
                : tAD("transcript.noMatch")}
            </Typography>
          )}
        </Box>
      )}

      {/* Section timeline bar */}
      {sections.length > 0 && (() => {
        const total = sections[sections.length - 1].endTime;
        return (
          <Box sx={{ mb: 1.5, px: 0.5 }}>
            <Box sx={{ position: "relative", height: 20, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm, overflow: "hidden" }}>
              {sections.map((s, i) => {
                const left = (s.startTime / total) * 100;
                const width = ((s.endTime - s.startTime) / total) * 100;
                const active = currentTime >= s.startTime && currentTime <= s.endTime;
                return (
                  <Tooltip key={s.id} title={`${s.title} (${formatDuration(Math.round(s.startTime))} – ${formatDuration(Math.round(s.endTime))})`}>
                    <Box
                      onClick={() => onSeek(s.startTime)}
                      sx={{
                        position: "absolute", left: `${left}%`, width: `${width}%`, top: 0, bottom: 0,
                        bgcolor: active ? `${sectionColors[i % sectionColors.length]}44` : `${sectionColors[i % sectionColors.length]}22`,
                        borderLeft: i > 0 ? `1px solid ${tokens.bg.surface}` : "none",
                        cursor: "pointer",
                        "&:hover": { bgcolor: `${sectionColors[i % sectionColors.length]}55` },
                        transition: "background-color 120ms ease",
                      }}
                    />
                  </Tooltip>
                );
              })}
              {/* Playhead */}
              <Box sx={{ position: "absolute", left: `${(currentTime / total) * 100}%`, top: 0, bottom: 0, width: 2, bgcolor: tokens.primary.main, zIndex: 2, pointerEvents: "none" }} />
            </Box>
          </Box>
        );
      })()}

      {sections.map((section, idx) => {
        const color = sectionColors[idx % sectionColors.length];
        const active = currentTime >= section.startTime && currentTime <= section.endTime;
        return (
          <Box key={section.id}>
            {/* Boundary drag arrows between sections */}
            {idx > 0 && (
              <Box sx={{ display: "flex", justifyContent: "center", py: 0.25 }}>
                <Box sx={{ display: "flex", gap: 0.25, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm, px: 0.5 }}>
                  <IconButton size="small" onClick={() => moveBoundary(idx, "up")} sx={{ p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.text.primary } }}>
                    <ArrowUpwardOutlined sx={{ fontSize: 12 }} />
                  </IconButton>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.6rem", alignSelf: "center", px: 0.25 }}>
                    {formatDuration(Math.round(section.startTime))}
                  </Typography>
                  <IconButton size="small" onClick={() => moveBoundary(idx, "down")} sx={{ p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.text.primary } }}>
                    <ArrowDownwardOutlined sx={{ fontSize: 12 }} />
                  </IconButton>
                </Box>
              </Box>
            )}

            {/* Section block */}
            <Box
              data-testid="transcript-section"
              data-section-id={section.id}
              data-revealed={revealedId === section.id ? "true" : "false"}
              ref={(el: HTMLElement | null) => { sectionRefs.current[section.id] = el; }}
              data-matched={matchedSections ? String(matchedSections.has(section.id)) : undefined}
              sx={{
                p: 1.5, borderRadius: tokens.radius.md,
                borderLeft: `3px solid ${color}`,
                bgcolor: active ? `${color}11` : "transparent",
                ...REVEAL_KEYFRAMES,
                ...(revealedId === section.id
                  ? { animation: `loomTranscriptReveal ${TRANSCRIPT_REVEAL_MS}ms ease-out` }
                  : {}),
                // Dimmed rather than hidden: the boundary arrows above each block move a section
                // relative to its neighbour, so removing one from the flow would have them
                // adjusting a pair that is no longer adjacent.
                opacity: matchedSections && !matchedSections.has(section.id) ? 0.35 : 1,
                transition: "background-color 160ms ease, opacity 160ms ease",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.75 }}>
                <InputBase
                  value={editingTitle?.id === section.id ? editingTitle.value : section.title}
                  onFocus={() => setEditingTitle({ id: section.id, value: section.title })}
                  onChange={e => setEditingTitle({ id: section.id, value: e.target.value })}
                  onKeyDown={e => { if (e.key === "Enter") { (e.target as HTMLInputElement).blur(); } }}
                  onBlur={e => commitTitle(idx, e.target.value)}
                  inputProps={{ "data-testid": "transcript-section-title", "aria-label": tAD("transcript.sectionTitle") }}
                  sx={{
                    fontSize: "0.78rem", fontWeight: 700, color,
                    "& .MuiInputBase-input": { p: 0, color, fontWeight: 700, fontSize: "0.78rem" },
                    "&:hover .MuiInputBase-input": { textDecoration: "underline dotted" },
                  }}
                />
                <Chip
                  label={`${formatDuration(Math.round(section.startTime))} – ${formatDuration(Math.round(section.endTime))}`}
                  size="small"
                  onClick={() => onSeek(section.startTime)}
                  sx={{ height: 16, fontSize: "0.62rem", bgcolor: `${color}22`, color, cursor: "pointer" }}
                />
              </Box>
              <Typography variant="body2" sx={{ fontSize: "0.8rem", color: tokens.text.secondary, lineHeight: 1.8 }}>
                {section.words.map((w, wi) => {
                  const wordActive = currentTime >= w.startTime && currentTime <= w.endTime;
                  const hit = isMatch(w.word);
                  return (
                    <Box
                      key={wi}
                      component="span"
                      data-testid={hit ? "transcript-match" : undefined}
                      onClick={() => onSeek(w.startTime)}
                      sx={{
                        cursor: "pointer",
                        // Playhead wins over a search hit: which word is being spoken is the more
                        // urgent of the two signals when both apply.
                        bgcolor: wordActive
                          ? `${tokens.primary.main}33`
                          : hit ? `${tokens.accent.amber}44` : "transparent",
                        borderRadius: wordActive || hit ? "2px" : 0,
                        px: wordActive || hit ? 0.25 : 0,
                        fontWeight: wordActive || hit ? 600 : 400,
                        color: wordActive ? tokens.primary.light : tokens.text.secondary,
                        transition: "all 80ms ease",
                        "&:hover": { bgcolor: `${tokens.primary.main}22`, borderRadius: "2px" },
                      }}
                    >
                      {w.word}{" "}
                    </Box>
                  );
                })}
              </Typography>
            </Box>
          </Box>
        );
      })}

      {sections.length === 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
          <Typography variant="body2" color="text.secondary">{tAD("empty.noTranscript")}</Typography>
        </Box>
      )}
    </Box>
  );
}
