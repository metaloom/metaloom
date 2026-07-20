import React, { useCallback, useRef, useState } from "react";
import { Box, IconButton, Typography } from "@mui/material";
import {
  ZoomInOutlined, ZoomOutOutlined, CenterFocusStrongOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";

/** A rectangular region to draw over the image. All values are normalized to 0-1 of the container. */
export interface ImageRegion {
  id: string;
  label?: string;
  color?: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

export function ZoomableImage({
  src,
  alt,
  selectMode = false,
  onRegionSelect,
  regions = [],
}: {
  src: string;
  alt: string;
  /** When true, dragging draws a rubber-band selection instead of panning. */
  selectMode?: boolean;
  /** Called with the drawn region (normalized 0-1) on mouse-up. */
  onRegionSelect?: (region: { x: number; y: number; width: number; height: number }) => void;
  /** Existing regions to render as read-only overlays (normalized 0-1). */
  regions?: ImageRegion[];
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });
  // Rubber-band selection (normalized 0-1 corners) while drawing a region.
  const drawing = useRef(false);
  const [band, setBand] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null);

  const normPoint = useCallback((clientX: number, clientY: number) => {
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect || rect.width === 0 || rect.height === 0) return { x: 0, y: 0 };
    return { x: clamp01((clientX - rect.left) / rect.width), y: clamp01((clientY - rect.top) / rect.height) };
  }, []);

  const handleWheel = useCallback((e: React.WheelEvent) => {
    if (selectMode) return;
    e.preventDefault();
    setScale(prev => {
      const next = Math.min(8, Math.max(1, prev - e.deltaY * 0.002));
      if (next <= 1) setPan({ x: 0, y: 0 });
      return next;
    });
  }, [selectMode]);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (selectMode) {
      e.preventDefault();
      drawing.current = true;
      const p = normPoint(e.clientX, e.clientY);
      setBand({ x0: p.x, y0: p.y, x1: p.x, y1: p.y });
      return;
    }
    if (scale <= 1) return;
    e.preventDefault();
    dragging.current = true;
    lastMouse.current = { x: e.clientX, y: e.clientY };
  }, [scale, selectMode, normPoint]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (drawing.current) {
      const p = normPoint(e.clientX, e.clientY);
      setBand(prev => (prev ? { ...prev, x1: p.x, y1: p.y } : prev));
      return;
    }
    if (!dragging.current) return;
    const dx = e.clientX - lastMouse.current.x;
    const dy = e.clientY - lastMouse.current.y;
    lastMouse.current = { x: e.clientX, y: e.clientY };
    setPan(prev => ({ x: prev.x + dx, y: prev.y + dy }));
  }, [normPoint]);

  const handleMouseUp = useCallback(() => {
    if (drawing.current) {
      drawing.current = false;
      setBand(b => {
        if (b) {
          const x = Math.min(b.x0, b.x1);
          const y = Math.min(b.y0, b.y1);
          const width = Math.abs(b.x1 - b.x0);
          const height = Math.abs(b.y1 - b.y0);
          // Ignore accidental clicks / zero-size selections.
          if (width > 0.01 && height > 0.01) onRegionSelect?.({ x, y, width, height });
        }
        return null;
      });
      return;
    }
    dragging.current = false;
  }, [onRegionSelect]);

  const reset = useCallback(() => { setScale(1); setPan({ x: 0, y: 0 }); }, []);

  // Normalized band while drawing, for the preview overlay.
  const previewBand = band
    ? { x: Math.min(band.x0, band.x1), y: Math.min(band.y0, band.y1), width: Math.abs(band.x1 - band.x0), height: Math.abs(band.y1 - band.y0) }
    : null;

  // Minimap viewport fraction
  const vpW = Math.min(1, 1 / scale);
  const vpH = Math.min(1, 1 / scale);
  const cw = containerRef.current?.clientWidth ?? 1;
  const ch = containerRef.current?.clientHeight ?? 1;
  const vpX = 0.5 - pan.x / (cw * scale) - vpW / 2;
  const vpY = 0.5 - pan.y / (ch * scale) - vpH / 2;

  return (
    <Box
      ref={containerRef}
      onWheel={handleWheel}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      sx={{
        position: "relative", width: "100%", height: "100%", minHeight: 200,
        overflow: "hidden",
        cursor: selectMode ? "crosshair" : scale > 1 ? (dragging.current ? "grabbing" : "grab") : "default",
      }}
      data-testid="zoomable-image"
    >
      <img
        src={src}
        alt={alt}
        draggable={false}
        style={{
          maxWidth: "100%", maxHeight: "100%", objectFit: "contain",
          transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})`,
          transformOrigin: "center center",
          transition: dragging.current ? "none" : "transform 80ms ease-out",
          userSelect: "none",
        }}
      />
      {/* Existing region overlays (normalized to the container) */}
      {regions.map(r => {
        const color = r.color ?? tokens.primary.main;
        return (
          <Box
            key={r.id}
            data-testid="image-region"
            sx={{
              position: "absolute",
              left: `${r.x * 100}%`,
              top: `${r.y * 100}%`,
              width: `${r.width * 100}%`,
              height: `${r.height * 100}%`,
              border: `2px solid ${color}`,
              bgcolor: `${color}18`,
              boxSizing: "border-box",
              pointerEvents: "none",
              borderRadius: tokens.radius.sm,
            }}
          >
            {r.label && (
              <Typography
                variant="caption"
                sx={{
                  position: "absolute", top: -18, left: -2,
                  fontSize: "0.62rem", px: 0.5, borderRadius: tokens.radius.sm,
                  bgcolor: color, color: "#fff", whiteSpace: "nowrap",
                }}
              >
                {r.label}
              </Typography>
            )}
          </Box>
        );
      })}
      {/* Rubber-band selection preview */}
      {previewBand && (
        <Box
          sx={{
            position: "absolute",
            left: `${previewBand.x * 100}%`,
            top: `${previewBand.y * 100}%`,
            width: `${previewBand.width * 100}%`,
            height: `${previewBand.height * 100}%`,
            border: `2px dashed ${tokens.primary.main}`,
            bgcolor: `${tokens.primary.main}22`,
            boxSizing: "border-box",
            pointerEvents: "none",
          }}
        />
      )}
      {/* Zoom controls */}
      <Box sx={{ position: "absolute", bottom: 8, right: 8, display: "flex", gap: 0.5, bgcolor: "rgba(0,0,0,0.6)", borderRadius: tokens.radius.md, px: 0.5, py: 0.25 }}>
        <IconButton size="small" onClick={() => setScale(s => Math.min(8, s + 0.5))} sx={{ color: "#fff", p: 0.5 }}><ZoomInOutlined sx={{ fontSize: 16 }} /></IconButton>
        <IconButton size="small" onClick={reset} sx={{ color: "#fff", p: 0.5 }}><CenterFocusStrongOutlined sx={{ fontSize: 16 }} /></IconButton>
        <IconButton size="small" onClick={() => { const ns = Math.max(1, scale - 0.5); setScale(ns); if (ns <= 1) setPan({ x: 0, y: 0 }); }} sx={{ color: "#fff", p: 0.5 }}><ZoomOutOutlined sx={{ fontSize: 16 }} /></IconButton>
        {scale > 1 && (
          <Typography variant="caption" sx={{ color: "#fff", fontSize: "0.65rem", alignSelf: "center", px: 0.5 }}>
            {Math.round(scale * 100)}%
          </Typography>
        )}
      </Box>
      {/* Minimap */}
      {scale > 1 && (
        <Box sx={{ position: "absolute", top: 8, right: 8, width: 100, height: 70, bgcolor: "rgba(0,0,0,0.5)", border: `1px solid ${tokens.border.default}`, borderRadius: tokens.radius.sm, overflow: "hidden" }}>
          <img src={src} alt="" style={{ width: "100%", height: "100%", objectFit: "contain", opacity: 0.7 }} />
          <Box
            sx={{
              position: "absolute",
              left: `${vpX * 100}%`,
              top: `${vpY * 100}%`,
              width: `${vpW * 100}%`,
              height: `${vpH * 100}%`,
              border: `2px solid ${tokens.primary.main}`,
              bgcolor: `${tokens.primary.main}22`,
              boxSizing: "border-box",
              pointerEvents: "none",
            }}
          />
        </Box>
      )}
    </Box>
  );
}
