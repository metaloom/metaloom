import React, { useCallback, useRef, useState } from "react";
import { Box, Tooltip, Typography } from "@mui/material";
import { tokens } from "../../theme";
import { formatDuration } from "./helpers";

export interface TimelineMarker {
  time: number;
  endTime?: number;
  type: "comment" | "annotation" | "reaction";
  color: string;
  label: string;
  id: string;
}

export function VideoTimeline({
  duration,
  currentTime,
  markers,
  hoveredMarkerId,
  onSeek,
  onMarkerClick,
  onMarkerHover,
  onMarkerDrag,
}: {
  duration: number;
  currentTime: number;
  markers: TimelineMarker[];
  hoveredMarkerId: string | null;
  onSeek: (t: number) => void;
  onMarkerClick: (id: string, type: string) => void;
  onMarkerHover: (id: string | null) => void;
  onMarkerDrag?: (markerId: string, edge: "start" | "end", newTime: number) => void;
}) {
  const barRef = useRef<HTMLDivElement>(null);
  const markerBarRef = useRef<HTMLDivElement>(null);
  const [draggingMarker, setDraggingMarker] = useState<{ id: string; edge: "start" | "end" } | null>(null);

  const handleBarClick = (e: React.MouseEvent) => {
    if (!barRef.current) return;
    const rect = barRef.current.getBoundingClientRect();
    const pct = (e.clientX - rect.left) / rect.width;
    onSeek(Math.max(0, Math.min(duration, pct * duration)));
  };

  const handleMarkerBarClick = (e: React.MouseEvent) => {
    if (draggingMarker) return;
    if (!markerBarRef.current) return;
    const rect = markerBarRef.current.getBoundingClientRect();
    const pct = (e.clientX - rect.left) / rect.width;
    onSeek(Math.max(0, Math.min(duration, pct * duration)));
  };

  // Draggable marker handle
  const handleMarkerDragStart = useCallback((e: React.MouseEvent, markerId: string, edge: "start" | "end") => {
    e.stopPropagation();
    e.preventDefault();
    setDraggingMarker({ id: markerId, edge });
    const onMove = (ev: MouseEvent) => {
      if (!markerBarRef.current) return;
      const rect = markerBarRef.current.getBoundingClientRect();
      const pct = Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width));
      const time = Math.round(pct * duration * 10) / 10;
      onSeek(time);
      onMarkerDrag?.(markerId, edge, time);
    };
    const onUp = () => {
      setDraggingMarker(null);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, [duration, onSeek, onMarkerDrag]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: "4px" }}>
      {/* Top: thin progress bar */}
      <Box sx={{ position: "relative", height: 6, cursor: "pointer" }}>
        <Box
          ref={barRef}
          onClick={handleBarClick}
          sx={{
            position: "absolute", left: 0, right: 0, top: 0, bottom: 0,
            bgcolor: tokens.bg.overlay, borderRadius: "3px 3px 0 0",
          }}
        >
          <Box sx={{ position: "absolute", left: 0, top: 0, bottom: 0, width: `${(currentTime / duration) * 100}%`, bgcolor: tokens.primary.main, borderRadius: "3px 3px 0 0", transition: "width 50ms linear" }} />
        </Box>
      </Box>

      {/* Bottom: marker/highlight area */}
      <Box
        ref={markerBarRef}
        onClick={handleMarkerBarClick}
        sx={{
          position: "relative", height: 28, bgcolor: `${tokens.bg.overlay}88`,
          borderRadius: "0 0 3px 3px", cursor: "pointer",
          borderTop: `1px solid ${tokens.border.subtle}`,
        }}
      >
        {/* Range highlights for all annotations with endTime */}
        {markers.filter(m => m.endTime && m.endTime > m.time).map(m => {
          const left = (m.time / duration) * 100;
          const width = ((m.endTime! - m.time) / duration) * 100;
          const isHovered = hoveredMarkerId === m.id;
          return (
            <Box
              key={`range_${m.id}`}
              sx={{
                position: "absolute",
                left: `${left}%`,
                width: `${width}%`,
                top: 4,
                bottom: 4,
                bgcolor: isHovered ? `${m.color}44` : `${m.color}22`,
                borderRadius: 1,
                transition: "background-color 120ms ease",
                zIndex: 1,
              }}
              onMouseEnter={() => onMarkerHover(m.id)}
              onMouseLeave={() => onMarkerHover(null)}
            >
              {/* Draggable start handle */}
              <Box
                onMouseDown={(e) => handleMarkerDragStart(e, m.id, "start")}
                sx={{
                  position: "absolute", left: -4, top: 0, bottom: 0, width: 8,
                  cursor: "ew-resize", zIndex: 3, display: "flex", alignItems: "center", justifyContent: "center",
                  "&:hover .handle-line": { bgcolor: m.color },
                }}
              >
                <Box className="handle-line" sx={{ width: 2, height: 14, borderRadius: 1, bgcolor: isHovered ? m.color : `${m.color}66`, transition: "background-color 100ms ease" }} />
              </Box>
              {/* Draggable end handle */}
              <Box
                onMouseDown={(e) => handleMarkerDragStart(e, m.id, "end")}
                sx={{
                  position: "absolute", right: -4, top: 0, bottom: 0, width: 8,
                  cursor: "ew-resize", zIndex: 3, display: "flex", alignItems: "center", justifyContent: "center",
                  "&:hover .handle-line": { bgcolor: m.color },
                }}
              >
                <Box className="handle-line" sx={{ width: 2, height: 14, borderRadius: 1, bgcolor: isHovered ? m.color : `${m.color}66`, transition: "background-color 100ms ease" }} />
              </Box>
            </Box>
          );
        })}

        {/* Point markers */}
        {markers.map(m => {
          const isHovered = hoveredMarkerId === m.id;
          return (
            <Tooltip key={m.id} title={m.label}>
              <Box
                onClick={(e) => { e.stopPropagation(); onMarkerClick(m.id, m.type); }}
                onMouseEnter={() => onMarkerHover(m.id)}
                onMouseLeave={() => onMarkerHover(null)}
                sx={{
                  position: "absolute",
                  left: `${(m.time / duration) * 100}%`,
                  top: "50%",
                  transform: "translate(-50%, -50%)",
                  width: isHovered ? 12 : 8,
                  height: isHovered ? 12 : 8,
                  borderRadius: "50%",
                  bgcolor: m.color,
                  border: `2px solid ${isHovered ? tokens.bg.base : tokens.bg.elevated}`,
                  boxShadow: isHovered ? `0 0 8px ${m.color}` : "none",
                  cursor: "pointer",
                  zIndex: isHovered ? 5 : 4,
                  transition: "width 100ms, height 100ms, box-shadow 100ms",
                }}
              />
            </Tooltip>
          );
        })}

        {/* Playhead indicator */}
        <Box sx={{ position: "absolute", left: `${(currentTime / duration) * 100}%`, top: 0, bottom: 0, width: 1.5, bgcolor: tokens.primary.main, zIndex: 6, pointerEvents: "none", transition: "left 50ms linear" }} />
      </Box>

      <Box sx={{ display: "flex", justifyContent: "space-between", mt: 0.25 }}>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
          {formatDuration(Math.round(currentTime))}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
          {formatDuration(duration)}
        </Typography>
      </Box>
    </Box>
  );
}
