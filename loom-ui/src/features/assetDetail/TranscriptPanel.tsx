import React from "react";
import { useTranslation } from "react-i18next";
import { Box, Chip, IconButton, Tooltip, Typography } from "@mui/material";
import { ArrowUpwardOutlined, ArrowDownwardOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { TranscriptSection } from "../../types";
import { formatDuration } from "./helpers";

export function TranscriptPanel({
  sections,
  currentTime,
  onSeek,
  onSectionsChange,
}: {
  sections: TranscriptSection[];
  currentTime: number;
  onSeek: (t: number) => void;
  onSectionsChange: (s: TranscriptSection[]) => void;
}) {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const sectionColors = [tokens.accent.blue, tokens.accent.green, tokens.accent.amber, "#c077db", tokens.primary.main, tokens.accent.red];

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
              sx={{
                p: 1.5, borderRadius: tokens.radius.md,
                borderLeft: `3px solid ${color}`,
                bgcolor: active ? `${color}11` : "transparent",
                transition: "background-color 160ms ease",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.75 }}>
                <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", color }}>
                  {section.title}
                </Typography>
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
                  return (
                    <Box
                      key={wi}
                      component="span"
                      onClick={() => onSeek(w.startTime)}
                      sx={{
                        cursor: "pointer",
                        bgcolor: wordActive ? `${tokens.primary.main}33` : "transparent",
                        borderRadius: wordActive ? "2px" : 0,
                        px: wordActive ? 0.25 : 0,
                        fontWeight: wordActive ? 600 : 400,
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
