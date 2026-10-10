import { describe, expect, it } from "vitest";

import { clock } from "./AssetVideoPlayer";
import { clockHMS } from "./DurationBadge";
import { FACE_BOX_WINDOW_SECONDS, visibleFacesAt } from "./FaceBoxes";
import type { DetectedFace } from "../types";

describe("clock (player transport)", () => {
  it("is m:ss below an hour and h:mm:ss from an hour on", () => {
    expect(clock(0)).toBe("0:00");
    expect(clock(9.99)).toBe("0:09");
    expect(clock(61)).toBe("1:01");
    expect(clock(3599)).toBe("59:59");
    expect(clock(3600)).toBe("1:00:00");
    expect(clock(2580)).toBe("43:00");
  });

  it("never prints a negative or non-finite time", () => {
    expect(clock(-5)).toBe("0:00");
    expect(clock(Number.NaN)).toBe("0:00");
    expect(clock(Number.POSITIVE_INFINITY)).toBe("0:00");
  });
});

describe("clockHMS (duration badge)", () => {
  it("always has the hour field, so badges keep one shape", () => {
    expect(clockHMS(0)).toBe("0:00:00");
    expect(clockHMS(65.9)).toBe("0:01:05");
    expect(clockHMS(2580)).toBe("0:43:00");
    expect(clockHMS(36_000)).toBe("10:00:00");
  });

  it("clamps negatives to zero", () => {
    expect(clockHMS(-1)).toBe("0:00:00");
  });
});

describe("visibleFacesAt", () => {
  const face = (id: string, at: number | null): DetectedFace & { at: number | null } =>
    ({ id, at, boundingBox: { x: 0, y: 0, width: 0.1, height: 0.1 } } as unknown as DetectedFace & { at: number | null });
  const faces = [face("a", 10), face("b", 100), face("c", null)];
  const timeOf = (f: DetectedFace) => (f as DetectedFace & { at: number | null }).at;

  it("draws every box on a still image", () => {
    expect(visibleFacesAt(faces, 0, timeOf, { isVideo: false }).map(f => f.id)).toEqual(["a", "b", "c"]);
  });

  it("keeps only faces within the window of the playhead, inclusive at its edge", () => {
    expect(visibleFacesAt(faces, 10, timeOf).map(f => f.id)).toEqual(["a"]);
    expect(visibleFacesAt(faces, 10 + FACE_BOX_WINDOW_SECONDS, timeOf).map(f => f.id)).toEqual(["a"]);
    expect(visibleFacesAt(faces, 10 + FACE_BOX_WINDOW_SECONDS + 0.01, timeOf)).toEqual([]);
  });

  it("drops a face with no time on video rather than pinning it to zero", () => {
    expect(visibleFacesAt(faces, 0, timeOf).map(f => f.id)).toEqual([]);
  });

  it("keeps the pinned face whatever the playhead says", () => {
    expect(visibleFacesAt(faces, 50, timeOf, { isVideo: true, pinnedFaceId: "b" }).map(f => f.id)).toEqual(["b"]);
    expect(visibleFacesAt(faces, 50, timeOf, { isVideo: true, pinnedFaceId: "c" }).map(f => f.id)).toEqual(["c"]);
  });
});
