import React from "react";
import { Box } from "@mui/material";
import { CheckCircleOutlineOutlined, HelpOutlineOutlined } from "@mui/icons-material";

import { tokens } from "../theme";
import { DetectedFace } from "../types";

/**
 * How far from the playhead a detection's frame may be and still have its box drawn.
 *
 * A seek lands on the nearest keyframe at or before the requested second, not on the exact frame,
 * so a window narrower than a GOP would leave the reviewer looking at the face they just clicked
 * on with no box around it. Wide enough to survive that, narrow enough that two detections a
 * minute apart are never on screen together — which is what "the bounding boxes are just
 * overlapped and make no sense" was: every face in a 43-minute episode drawn on one frame at once.
 */
export const FACE_BOX_WINDOW_SECONDS = 2.5;

/**
 * How long the flash takes to decay, in milliseconds.
 *
 * Also how far *before* a face a click seeks: jumping to the detection's own frame would arrive
 * with the moment already past, so the player lands a quarter of a second early and the box
 * lights up as the face comes round. The two are the same number on purpose — the lead-in and the
 * decay together are one gesture, and splitting them would make the highlight arrive at a moment
 * with nothing at it.
 */
export const FACE_FLASH_MS = 250;

/**
 * How long the highlight itself runs, in milliseconds.
 *
 * Separate from {@link FACE_FLASH_MS}, which is the lead-in, because the two answer different
 * questions: the lead-in is how early to arrive, this is how long the gesture takes once it does.
 * They used to be the same number and the result was a 250ms decay that read as the box changing
 * colour rather than as anything being pointed at — by the time the eye reached the picture the
 * highlight had already been and gone.
 */
export const FACE_FLASH_DURATION_MS = 1200;

/**
 * Keyframes for the flash. Shared, because two views animate the same thing.
 *
 * Fade in, flash, fade out — in that order and over a period somebody can actually follow. The
 * box's own opacity is part of it: a click seeks and the box for the face that was clicked is
 * usually one that has just appeared, so growing it in is what makes "this one" legible among
 * the others that were already on screen.
 */
const FLASH_KEYFRAMES = {
  "@keyframes loomFaceFlash": {
    "0%": { opacity: 0, boxShadow: `0 0 0 0 ${tokens.primary.main}00`, borderColor: "currentColor" },
    "20%": { opacity: 1, boxShadow: `0 0 0 5px ${tokens.primary.main}55`, borderColor: "#fff" },
    "40%": { opacity: 1, boxShadow: `0 0 0 11px ${tokens.primary.main}cc`, borderColor: "#fff" },
    "60%": { opacity: 1, boxShadow: `0 0 0 5px ${tokens.primary.main}77`, borderColor: "#fff" },
    "100%": { opacity: 1, boxShadow: `0 0 0 0 ${tokens.primary.main}00`, borderColor: "currentColor" },
  },
};

/**
 * Pick the faces whose frame is near enough to the playhead to draw.
 *
 * A still image has no time axis, so every box belongs on it. `pinnedFaceId` survives the window:
 * a click is a question — "where is this face?" — and answering it with an empty frame because
 * the seek landed on the keyframe 3 seconds earlier is the failure the window would introduce.
 */
export function visibleFacesAt(
  faces: DetectedFace[],
  currentTime: number,
  timeOf: (face: DetectedFace) => number | null,
  opts: { isVideo: boolean; pinnedFaceId?: string | null } = { isVideo: true },
): DetectedFace[] {
  if (!opts.isVideo) return faces;
  return faces.filter(f => {
    if (f.id === opts.pinnedFaceId) return true;
    const at = timeOf(f);
    return at != null && Math.abs(at - currentTime) <= FACE_BOX_WINDOW_SECONDS;
  });
}

export interface FaceBoxesProps {
  faces: DetectedFace[];
  /** Cluster whose faces read as "the one under review". */
  selectedClusterId?: string;
  /** Per-cluster verdicts, drawn as a corner glyph. Omit where there is no review to show. */
  clusterDecisions?: Record<string, "confirmed" | "denied">;
  /** The crop the pointer is over, if any. */
  hoveredFaceId?: string | null;
  /**
   * The face to flash, and a nonce that restarts the animation.
   *
   * Two fields rather than one because clicking the same crop twice has to flash twice, and a CSS
   * animation only replays when the element's `key` changes.
   */
  flash?: { faceId: string; nonce: number } | null;
  /** `data-testid` on each box. Per-view, so a spec can say which overlay it means. */
  testId?: string;
}

/**
 * The bounding boxes for the faces on screen right now.
 *
 * Shared by the asset viewer and the workflow review queue so both draw — and highlight — a face
 * the same way. It positions in percentages against whatever is the nearest positioned ancestor,
 * which is the player's picture box in both callers, so a letterboxed video and a still image need
 * no different treatment here.
 */
export function FaceBoxes({ faces, selectedClusterId, clusterDecisions, hoveredFaceId, flash, testId = "face-box" }: FaceBoxesProps) {
  return (
    <>
      {faces.map(f => {
        const decision = clusterDecisions?.[f.clusterId ?? ""];
        const inSelected = !!selectedClusterId && f.clusterId === selectedClusterId;
        const hovered = hoveredFaceId === f.id;
        const flashing = flash?.faceId === f.id;
        return (
          <Box
            // The nonce is in the key so a second click on the same crop restarts the animation
            // instead of leaving the element mounted mid-decay with nothing happening.
            key={flashing ? `${f.id}-${flash!.nonce}` : f.id}
            data-testid={testId} data-face-id={f.id}
            data-in-selected-cluster={inSelected ? "true" : "false"}
            data-flashing={flashing ? "true" : "false"}
            sx={{
              position: "absolute",
              left: `${f.boundingBox.x * 100}%`, top: `${f.boundingBox.y * 100}%`,
              width: `${f.boundingBox.width * 100}%`, height: `${f.boundingBox.height * 100}%`,
              color: inSelected || hovered ? tokens.primary.main : tokens.accent.amber,
              border: "2px solid currentColor",
              boxShadow: hovered ? `0 0 0 3px ${tokens.primary.main}55` : "none",
              borderRadius: tokens.radius.sm, pointerEvents: "none",
              transition: "box-shadow 120ms ease, border-color 120ms ease",
              ...FLASH_KEYFRAMES,
              ...(flashing ? { animation: `loomFaceFlash ${FACE_FLASH_DURATION_MS}ms ease-in-out` } : {}),
            }}>
            {clusterDecisions && (
              <Box sx={{ position: "absolute", top: -16, right: 0 }}>
                {decision === "confirmed" ? (
                  <CheckCircleOutlineOutlined sx={{ fontSize: 12, color: tokens.accent.green }} />
                ) : (
                  <HelpOutlineOutlined sx={{ fontSize: 12, color: decision === "denied" ? tokens.accent.red : tokens.accent.amber }} />
                )}
              </Box>
            )}
          </Box>
        );
      })}
    </>
  );
}

export default FaceBoxes;
