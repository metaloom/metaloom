import React, { useCallback, useRef, useState } from "react";
import { Box, IconButton, Typography } from "@mui/material";
import {
  ZoomInOutlined, ZoomOutOutlined, CenterFocusStrongOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { fitContain } from "../../components/ContainFrame";

/**
 * Turn a pointer position into a fraction of the picture.
 *
 * <p>Pure, so the arithmetic that decides where a drawn box lands is testable without a DOM. The
 * rect passed in is the *picture's* on-screen rectangle — `getBoundingClientRect()` of the layer
 * that carries the pan/zoom transform, so the transform is already baked into it and this needs
 * no knowledge of either.</p>
 *
 * <p>Clamped, because a drag that leaves the picture is a drag to its edge, not a coordinate
 * outside the image. A region with x &gt; 1 would be stored, rendered nowhere, and impossible to
 * explain.</p>
 */
export function pointInPicture(
  rect: { left: number; top: number; width: number; height: number },
  clientX: number,
  clientY: number,
): { x: number; y: number } {
  if (rect.width === 0 || rect.height === 0) return { x: 0, y: 0 };
  return {
    x: clamp01((clientX - rect.left) / rect.width),
    y: clamp01((clientY - rect.top) / rect.height),
  };
}

/**
 * A rectangular region to draw over the image.
 *
 * <p><b>Normalized 0-1 of the image</b>, not of the element that holds it. That is the same frame
 * of reference a detector works in — `detection.bbox_*` is a fraction of the picture the model
 * saw — so a box drawn by hand and a box drawn by a model mean the same thing and can be compared,
 * corrected and re-drawn interchangeably.</p>
 */
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
  const containerRef = useRef<HTMLDivElement | null>(null);
  /**
   * The layer the picture and its overlays share.
   *
   * Everything that has to agree with the image lives inside it: the `<img>`, the regions, the
   * rubber band. It is positioned at the letterboxed picture rect and carries the pan/zoom
   * transform, so a child at `left: "42%"` is at 42% of the *image* however the image is
   * currently sized, panned or scaled — the browser does the arithmetic instead of this file.
   */
  const pictureRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });
  // Rubber-band selection (normalized 0-1 corners of the IMAGE) while drawing a region.
  const drawing = useRef(false);
  const [band, setBand] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null);

  /** The container's size, so the picture rect can be computed. */
  const [box, setBox] = useState({ width: 0, height: 0 });
  /** The image's intrinsic size, from the element itself. Null until it loads. */
  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);
  const observer = useRef<ResizeObserver | null>(null);

  const measureRef = useCallback((node: HTMLDivElement | null) => {
    containerRef.current = node;
    observer.current?.disconnect();
    observer.current = null;
    if (!node) return;
    const read = (width: number, height: number) =>
      setBox(prev => (prev.width === width && prev.height === height ? prev : { width, height }));
    if (typeof ResizeObserver !== "undefined") {
      const ro = new ResizeObserver(entries => {
        const r = entries[0]?.contentRect;
        if (r) read(r.width, r.height);
      });
      ro.observe(node);
      observer.current = ro;
    }
    const rect = node.getBoundingClientRect();
    read(rect.width, rect.height);
  }, []);

  /** Where `object-fit: contain` puts the picture inside the container, before the transform. */
  const fit = fitContain(box, natural);

  /**
   * A pointer position as a fraction of the picture.
   *
   * Measured off the transformed layer rather than computed from `pan` and `scale`: the browser
   * has already applied both, and re-deriving them here is how the drawn box and the rendered
   * box drift apart. This used to read the *container* rect, which meant a box drawn on a
   * letterboxed image was stored in coordinates that were not the image's — self-consistent with
   * the old renderer, and disagreeing with every box a detector produced.
   */
  const normPoint = useCallback((clientX: number, clientY: number) => {
    const rect = pictureRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return pointInPicture(rect, clientX, clientY);
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

  // Minimap viewport fraction. Against the PICTURE, not the container: `pan` moves the picture,
  // so dividing by the container's width overstated the travel on a letterboxed image and the
  // indicator drifted away from what was actually on screen.
  const vpW = Math.min(1, 1 / scale);
  const vpH = Math.min(1, 1 / scale);
  const pw = fit.width || 1;
  const ph = fit.height || 1;
  const vpX = 0.5 - pan.x / (pw * scale) - vpW / 2;
  const vpY = 0.5 - pan.y / (ph * scale) - vpH / 2;

  return (
    <Box
      ref={measureRef}
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
      {/* The picture and everything that has to line up with it, in one transformed layer.

          The image used to be a bare <img> in the container with the overlays as siblings, each
          positioned in percentages of the *container*. Those two rectangles are only the same
          when the aspect ratios match, which with a letterboxed image they never do — so a box a
          model produced (fractions of the picture) was drawn in the wrong place, and a box drawn
          by hand was stored in coordinates that were not the picture's. Self-consistent, and
          disagreeing with everything else in the system.

          Now the layer IS the picture rect, and it carries the pan/zoom transform, so a child at
          `left: "42%"` is at 42% of the image at every zoom level and every pan offset, with no
          arithmetic here to get wrong. */}
      <Box
        ref={pictureRef}
        data-testid="zoomable-image-picture"
        sx={{
          position: "absolute",
          left: fit.left, top: fit.top, width: fit.width, height: fit.height,
          transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})`,
          transformOrigin: "center center",
          transition: dragging.current ? "none" : "transform 80ms ease-out",
        }}
      >
        <img
          src={src}
          alt={alt}
          draggable={false}
          onLoad={e => {
            const el = e.currentTarget;
            if (el.naturalWidth > 0 && el.naturalHeight > 0) {
              setNatural({ width: el.naturalWidth, height: el.naturalHeight });
            }
          }}
          style={{
            display: "block", width: "100%", height: "100%", objectFit: "contain",
            userSelect: "none",
          }}
        />
      {/* Existing region overlays — percentages of the picture layer above. */}
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
      </Box>
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
