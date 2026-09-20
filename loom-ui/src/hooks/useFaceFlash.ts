import { useCallback, useEffect, useRef, useState } from "react";

import { FACE_FLASH_MS } from "../components/FaceBoxes";

/** What {@link FaceBoxes} needs to animate one box: which, and a nonce to restart on. */
export interface FaceFlash {
  faceId: string;
  nonce: number;
}

/**
 * Arm a bounding-box highlight for the moment a seek lands on its face.
 *
 * <p>Clicking a face crop seeks a quarter of a second <em>before</em> the detection, so the box
 * has to light up when the playhead gets there rather than immediately. Waiting on
 * `timeupdate` to cross the frame would be the literal reading and is the wrong one here: the
 * stream is remuxed on demand and a seek lands on the keyframe at or before the request, so the
 * crossing either happens some seconds late or — with the player paused, which is the usual case
 * when somebody is picking through a cluster — never happens at all. A timer is honest about what
 * it is measuring and fires the same way whether or not the video is rolling.</p>
 */
export function useFaceFlash(leadInMs: number = FACE_FLASH_MS) {
  const [flash, setFlash] = useState<FaceFlash | null>(null);
  const nonce = useRef(0);
  const timers = useRef<number[]>([]);

  const clear = useCallback(() => {
    timers.current.forEach(window.clearTimeout);
    timers.current = [];
  }, []);

  useEffect(() => clear, [clear]);

  /** Flash `faceId` once the lead-in has elapsed, then drop it when the animation is spent. */
  const armFlash = useCallback((faceId: string) => {
    clear();
    timers.current.push(window.setTimeout(() => {
      nonce.current += 1;
      setFlash({ faceId, nonce: nonce.current });
      timers.current.push(window.setTimeout(() => {
        setFlash(prev => (prev?.faceId === faceId ? null : prev));
      }, FACE_FLASH_MS));
    }, leadInMs));
  }, [clear, leadInMs]);

  return { flash, armFlash };
}

export default useFaceFlash;
