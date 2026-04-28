import React, { useCallback, useRef, useState } from "react";
import { Box, IconButton, Typography } from "@mui/material";
import {
  ZoomInOutlined, ZoomOutOutlined, CenterFocusStrongOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";

export function ZoomableImage({ src, alt }: { src: string; alt: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setScale(prev => {
      const next = Math.min(8, Math.max(1, prev - e.deltaY * 0.002));
      if (next <= 1) setPan({ x: 0, y: 0 });
      return next;
    });
  }, []);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (scale <= 1) return;
    e.preventDefault();
    dragging.current = true;
    lastMouse.current = { x: e.clientX, y: e.clientY };
  }, [scale]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (!dragging.current) return;
    const dx = e.clientX - lastMouse.current.x;
    const dy = e.clientY - lastMouse.current.y;
    lastMouse.current = { x: e.clientX, y: e.clientY };
    setPan(prev => ({ x: prev.x + dx, y: prev.y + dy }));
  }, []);

  const handleMouseUp = useCallback(() => { dragging.current = false; }, []);

  const reset = useCallback(() => { setScale(1); setPan({ x: 0, y: 0 }); }, []);

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
        position: "relative", width: "100%", height: "100%",
        overflow: "hidden", cursor: scale > 1 ? (dragging.current ? "grabbing" : "grab") : "default",
      }}
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
