import { describe, expect, it } from "vitest";

import { fitContain } from "./ContainFrame";

/**
 * The arithmetic behind "the bounding boxes are elongated and in the wrong place".
 *
 * A detection's box arrives as fractions of the *image*. The overlay draws them as percentages of
 * whatever element contains it, and with `object-fit: contain` those two rectangles are only the
 * same when the aspect ratios match. On the asset viewer they never do: the picture box is the
 * player minus its transport, so a 16:9 video sits in a wider box with black down each side, and
 * every box came out stretched horizontally and shifted left by the width of one bar.
 */
describe("fitContain", () => {
  it("pillarboxes a picture narrower than its box, and centres it", () => {
    // The real case: a 16:9 video in a box 966x380 (16:9 minus a 33px control bar).
    const fit = fitContain({ width: 966, height: 380 }, { width: 1920, height: 1080 });
    expect(fit.height).toBeCloseTo(380, 5);
    expect(fit.width).toBeCloseTo(380 * (16 / 9), 5);
    // Half the leftover width on each side — this offset is what the boxes were missing.
    expect(fit.left).toBeCloseTo((966 - 380 * (16 / 9)) / 2, 5);
    expect(fit.top).toBeCloseTo(0, 5);
  });

  it("letterboxes a picture taller than its box", () => {
    const fit = fitContain({ width: 400, height: 400 }, { width: 1000, height: 500 });
    expect(fit.width).toBeCloseTo(400, 5);
    expect(fit.height).toBeCloseTo(200, 5);
    expect(fit.top).toBeCloseTo(100, 5);
    expect(fit.left).toBeCloseTo(0, 5);
  });

  it("fills the box exactly when the ratios already agree", () => {
    const fit = fitContain({ width: 640, height: 360 }, { width: 1920, height: 1080 });
    expect(fit).toEqual({ left: 0, top: 0, width: 640, height: 360 });
  });

  it("scales with the box, which is what makes zoom and fullscreen come out right", () => {
    // The same picture in a box twice the size: every number doubles and the *fractions* the
    // boxes are expressed in are unchanged. Getting this wrong is why the boxes moved when the
    // player was resized.
    const small = fitContain({ width: 480, height: 200 }, { width: 1920, height: 1080 });
    const large = fitContain({ width: 960, height: 400 }, { width: 1920, height: 1080 });
    expect(large.width).toBeCloseTo(small.width * 2, 5);
    expect(large.height).toBeCloseTo(small.height * 2, 5);
    expect(large.left).toBeCloseTo(small.left * 2, 5);
  });

  it("fills the box while the intrinsic size is unknown", () => {
    // Before `loadedmetadata`, and for a picture whose dimensions nothing recorded. Degrading to
    // the old behaviour is right; degrading to a frame of zero size would hide the boxes.
    expect(fitContain({ width: 800, height: 450 }, null))
      .toEqual({ left: 0, top: 0, width: 800, height: 450 });
    expect(fitContain({ width: 800, height: 450 }, { width: 0, height: 0 }))
      .toEqual({ left: 0, top: 0, width: 800, height: 450 });
  });

  it("survives a box that has not been laid out yet", () => {
    expect(fitContain({ width: 0, height: 0 }, { width: 1920, height: 1080 }))
      .toEqual({ left: 0, top: 0, width: 0, height: 0 });
  });
});
