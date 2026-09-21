import { describe, expect, it } from "vitest";

import { pointInPicture } from "./ZoomableImage";
import { fitContain } from "../../components/ContainFrame";

/**
 * Where a hand-drawn box lands.
 *
 * <p>Regions used to be captured *and* rendered in container coordinates. That is internally
 * consistent — draw a box, see it where you drew it — and wrong about the only thing that
 * matters: a detector's box is a fraction of the *picture*, so a hand-drawn region and a model's
 * region meant different things and could not be compared or corrected against each other. On a
 * letterboxed image the two frames differ by the width of a black bar.</p>
 *
 * <p>The rect these take is the picture layer's `getBoundingClientRect()`, which already has the
 * pan/zoom transform applied — which is why zooming in does not need a case of its own in the
 * component, only here.</p>
 */
describe("pointInPicture", () => {
  // A 16:9 picture letterboxed inside a 1000x400 container: 711x400, 144px of bar each side.
  const container = { width: 1000, height: 400 };
  const natural = { width: 1920, height: 1080 };
  const fit = fitContain(container, natural);
  const rect = { left: fit.left, top: fit.top, width: fit.width, height: fit.height };

  it("puts the centre of the picture at 0.5, 0.5 — not the centre of the container", () => {
    const centre = pointInPicture(rect, fit.left + fit.width / 2, fit.top + fit.height / 2);
    expect(centre.x).toBeCloseTo(0.5, 5);
    expect(centre.y).toBeCloseTo(0.5, 5);

    // The old behaviour, for contrast: the container's centre is the picture's centre here only
    // because the bars are symmetric. A quarter of the way across is where they diverge.
    const quarterOfContainer = pointInPicture(rect, container.width * 0.25, 100);
    expect(quarterOfContainer.x).not.toBeCloseTo(0.25, 2);
  });

  it("reads the picture's own left edge as 0 and its right edge as 1", () => {
    expect(pointInPicture(rect, fit.left, fit.top).x).toBeCloseTo(0, 5);
    expect(pointInPicture(rect, fit.left + fit.width, fit.top).x).toBeCloseTo(1, 5);
  });

  it("clamps a drag that leaves the picture to its edge", () => {
    // Into the left letterbox bar, and past the right-hand edge of the container.
    expect(pointInPicture(rect, 0, 200).x).toBe(0);
    expect(pointInPicture(rect, 5000, 200).x).toBe(1);
    expect(pointInPicture(rect, 500, -80).y).toBe(0);
    expect(pointInPicture(rect, 500, 5000).y).toBe(1);
  });

  it("is unchanged by zoom, because the rect it is given is already transformed", () => {
    // The same point on the picture, with the layer scaled 2x about its centre. The browser
    // reports a rect twice the size and shifted; the fraction has to come out the same.
    const zoomed = {
      left: fit.left - fit.width / 2,
      top: fit.top - fit.height / 2,
      width: fit.width * 2,
      height: fit.height * 2,
    };
    const atRest = pointInPicture(rect, fit.left + fit.width * 0.3, fit.top + fit.height * 0.7);
    const atZoom = pointInPicture(zoomed, zoomed.left + zoomed.width * 0.3, zoomed.top + zoomed.height * 0.7);
    expect(atZoom.x).toBeCloseTo(atRest.x, 5);
    expect(atZoom.y).toBeCloseTo(atRest.y, 5);
  });

  it("answers 0,0 for a picture that has not been laid out", () => {
    expect(pointInPicture({ left: 0, top: 0, width: 0, height: 0 }, 100, 100)).toEqual({ x: 0, y: 0 });
  });
});
