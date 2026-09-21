import React, { useCallback, useRef, useState } from "react";
import { Box } from "@mui/material";

/** A rectangle inside the container, in CSS pixels. */
export interface FitRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * Where a `object-fit: contain` picture actually lands inside its box.
 *
 * The letterbox bars are the whole point. A 16:9 video in a box that is 16:9 *minus a control
 * bar* is pillarboxed by a few dozen pixels on each side, and nothing on screen says so — which
 * is exactly how an overlay drawn in percentages of the box ends up wider than the picture and
 * offset from it.
 */
export function fitContain(box: { width: number; height: number }, natural: { width: number; height: number } | null): FitRect {
  if (!natural || natural.width <= 0 || natural.height <= 0 || box.width <= 0 || box.height <= 0) {
    return { left: 0, top: 0, width: box.width, height: box.height };
  }
  const scale = Math.min(box.width / natural.width, box.height / natural.height);
  const width = natural.width * scale;
  const height = natural.height * scale;
  return { left: (box.width - width) / 2, top: (box.height - height) / 2, width, height };
}

export interface ContainFrameProps {
  /**
   * Intrinsic size of the picture, when it is known.
   *
   * `null` means "not measured yet" and the frame fills its parent, which is the same thing the
   * overlay did before this component existed — so an unknown size degrades to the old behaviour
   * rather than to a frame of zero size.
   */
  natural: { width: number; height: number } | null;
  testId?: string;
  children?: React.ReactNode;
}

/**
 * The coordinate space an overlay on a letterboxed picture belongs in.
 *
 * <p>Bounding boxes arrive as fractions of the <em>image</em>: x=0.5 is the middle of the frame the
 * detector saw. Drawing them as percentages of the element that holds the picture is only correct
 * when the two have the same aspect ratio, and with `object-fit: contain` they almost never do.
 * On the asset viewer the picture box is 16:9 minus the height of the transport, so a 16:9 video
 * sat in the middle with a bar of black down each side and every box came out stretched
 * horizontally and shifted left — "elongated, and the height seems to match".</p>
 *
 * <p>This measures rather than computes, which is what makes it survive the cases where the
 * arithmetic was wrong before: fullscreen, browser zoom, and a player the user is dragging
 * larger. A `ResizeObserver` on the frame reports the box after every one of those, and the
 * picture's intrinsic size comes from the element itself.</p>
 *
 * <p>It renders two boxes: an outer one pinned to the parent, purely to be measured, and an inner
 * one at the computed picture rect that the children position themselves against. Children are
 * therefore unchanged — they still say `left: "42%"` — and they are now percentages of the right
 * thing.</p>
 */
export function ContainFrame({ natural, testId, children }: ContainFrameProps) {
  const [box, setBox] = useState({ width: 0, height: 0 });
  const observer = useRef<ResizeObserver | null>(null);

  // A callback ref, not an effect: the overlay is mounted and unmounted as the media loads, and
  // an empty-deps effect would attach to whichever element happened to exist on the first render.
  const measureRef = useCallback((node: HTMLDivElement | null) => {
    observer.current?.disconnect();
    observer.current = null;
    if (!node) return;
    const read = (width: number, height: number) => {
      setBox(prev => (prev.width === width && prev.height === height ? prev : { width, height }));
    };
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

  const fit = fitContain(box, natural);

  return (
    <Box ref={measureRef} sx={{ position: "absolute", inset: 0, pointerEvents: "none", overflow: "hidden" }}>
      <Box
        data-testid={testId}
        data-fit-width={Math.round(fit.width)}
        data-fit-height={Math.round(fit.height)}
        data-fit-left={Math.round(fit.left)}
        sx={{ position: "absolute", left: fit.left, top: fit.top, width: fit.width, height: fit.height }}
      >
        {children}
      </Box>
    </Box>
  );
}

export default ContainFrame;
