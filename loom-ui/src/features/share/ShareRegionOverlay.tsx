import React, { useCallback, useRef, useState } from "react";
import { Box, Typography } from "@mui/material";
import { tokens } from "../../theme";
import type { ShareAnnotationResponse } from "../../api/shares";
import { formatTimecode } from "./shareExpiry";

/** A region in the media's own coordinate space: fractions, never pixels. */
export interface NormalisedRegion {
  areaX: number;
  areaY: number;
  areaWidth: number;
  areaHeight: number;
}

export interface ShareRegionOverlayProps {
  /** Marks to draw. Only those carrying a region are rendered. */
  annotations: ShareAnnotationResponse[];
  /** When true, dragging across the media draws a new box instead of passing the event through. */
  drawing: boolean;
  onRegionDrawn: (region: NormalisedRegion) => void;
  /** Jump the player to a mark's timecode when its box is clicked. */
  onSeek?: (seconds: number) => void;
  children: React.ReactNode;
}

/**
 * Draw a box on the media, and show the boxes already drawn.
 *
 * <p>
 * Coordinates are **normalised 0..1** against the rendered media box, which is the same space the
 * server stores and the reason it stores it: this viewer is full-bleed and responsive, so a mark
 * drawn on a laptop has to land in the same place on a phone. The conversion happens here and
 * nowhere else.
 * </p>
 *
 * <p>
 * The overlay is `pointerEvents: none` unless {@link ShareRegionOverlayProps.drawing} is set. A
 * transparent layer permanently over a `<video controls>` would swallow every click on the player's
 * own controls — the reviewer could not press play.
 * </p>
 */
export default function ShareRegionOverlay({
  annotations,
  drawing,
  onRegionDrawn,
  onSeek,
  children,
}: ShareRegionOverlayProps) {
  const frameRef = useRef<HTMLDivElement | null>(null);
  const [start, setStart] = useState<{ x: number; y: number } | null>(null);
  const [current, setCurrent] = useState<{ x: number; y: number } | null>(null);

  const regions = annotations.filter((a) => a.areaX !== undefined && a.areaX !== null);

  /** Pointer position as a fraction of the frame, clamped so a drag off the edge still lands inside. */
  const fractionOf = useCallback((event: React.PointerEvent) => {
    const rect = frameRef.current?.getBoundingClientRect();
    if (!rect || rect.width === 0 || rect.height === 0) return { x: 0, y: 0 };
    const clamp = (value: number) => Math.min(1, Math.max(0, value));
    return {
      x: clamp((event.clientX - rect.left) / rect.width),
      y: clamp((event.clientY - rect.top) / rect.height),
    };
  }, []);

  const finish = () => {
    if (!start || !current) {
      setStart(null);
      setCurrent(null);
      return;
    }
    const areaX = Math.min(start.x, current.x);
    const areaY = Math.min(start.y, current.y);
    const areaWidth = Math.abs(current.x - start.x);
    const areaHeight = Math.abs(current.y - start.y);
    setStart(null);
    setCurrent(null);
    // A stray click is a zero-area box. The database rejects one (extents must be > 0), so it is
    // discarded here rather than sent and refused.
    if (areaWidth < 0.01 || areaHeight < 0.01) return;
    onRegionDrawn({ areaX, areaY, areaWidth, areaHeight });
  };

  const preview = start && current
    ? {
        left: `${Math.min(start.x, current.x) * 100}%`,
        top: `${Math.min(start.y, current.y) * 100}%`,
        width: `${Math.abs(current.x - start.x) * 100}%`,
        height: `${Math.abs(current.y - start.y) * 100}%`,
      }
    : null;

  return (
    <Box ref={frameRef} sx={{ position: "relative", lineHeight: 0 }}>
      {children}

      <Box
        data-testid="share-region-overlay"
        onPointerDown={
          drawing
            ? (e: React.PointerEvent) => {
                (e.target as Element).setPointerCapture?.(e.pointerId);
                const point = fractionOf(e);
                setStart(point);
                setCurrent(point);
              }
            : undefined
        }
        onPointerMove={drawing && start ? (e: React.PointerEvent) => setCurrent(fractionOf(e)) : undefined}
        onPointerUp={drawing ? finish : undefined}
        sx={{
          position: "absolute",
          inset: 0,
          // Only intercept pointer events while drawing; otherwise the player's own controls are
          // unreachable and the reviewer cannot press play.
          pointerEvents: drawing ? "auto" : "none",
          cursor: drawing ? "crosshair" : "default",
          // A faint wash while arming, so it is obvious the next drag draws rather than scrubs.
          bgcolor: drawing ? "rgba(0,0,0,0.18)" : "transparent",
        }}
      >
        {regions.map((annotation) => (
          <Box
            key={annotation.uuid}
            data-testid="share-region"
            onClick={() => annotation.timeFrom !== undefined && onSeek?.(annotation.timeFrom)}
            sx={{
              position: "absolute",
              left: `${(annotation.areaX ?? 0) * 100}%`,
              top: `${(annotation.areaY ?? 0) * 100}%`,
              width: `${(annotation.areaWidth ?? 0) * 100}%`,
              height: `${(annotation.areaHeight ?? 0) * 100}%`,
              border: `2px solid ${tokens.primary.main}`,
              borderRadius: tokens.radius.sm,
              boxShadow: "0 0 0 1px rgba(0,0,0,0.6)",
              // Existing boxes stay clickable even when not drawing, so a mark can be jumped to.
              pointerEvents: "auto",
              cursor: annotation.timeFrom !== undefined && onSeek ? "pointer" : "default",
            }}
          >
            <Typography
              sx={{
                position: "absolute",
                top: -20,
                left: -2,
                px: 0.75,
                fontSize: "0.68rem",
                fontWeight: 700,
                lineHeight: "18px",
                whiteSpace: "nowrap",
                color: tokens.text.inverse,
                bgcolor: tokens.primary.main,
                borderRadius: tokens.radius.sm,
              }}
            >
              {annotation.timeFrom !== undefined ? formatTimecode(annotation.timeFrom) : ""}
              {annotation.text ? ` ${annotation.text}` : ""}
            </Typography>
          </Box>
        ))}

        {preview && (
          <Box
            data-testid="share-region-preview"
            sx={{
              position: "absolute",
              ...preview,
              border: `2px dashed ${tokens.primary.light}`,
              bgcolor: "rgba(87, 203, 204, 0.15)",
            }}
          />
        )}
      </Box>
    </Box>
  );
}
