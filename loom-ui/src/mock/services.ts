import {
  DetectedFace,
} from "../types";
import {
  DETECTED_FACES,
} from "./data";

// Simulate realistic async latency
const delay = (ms = 200) => new Promise<void>((r) => setTimeout(r, ms));

// ── Face Detection ───────────────────────────────────────────────────────
export const mockFaceDetectionService = {
  getFacesByAsset: async (assetId: string): Promise<DetectedFace[]> => {
    await delay(100);
    return DETECTED_FACES.filter(f => f.assetId === assetId);
  },
};
