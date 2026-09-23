"""
Unit tests for mask_ops — the sidecar's mask arithmetic.

These are the first Python tests in `sidecars/`. They exist because mask_ops is the
only sidecar logic that is neither model output nor HTTP glue: a wrong threshold or an
inverted polarity produces a plausible-looking edit in entirely the wrong place, and
nothing downstream would notice.

Run:
  pip install pillow pytest && python -m pytest test_mask_ops.py -q
  # or, with no pytest:
  python test_mask_ops.py
"""

from PIL import Image

import mask_ops


def _soft_mask(size=(64, 64), box=(16, 16, 48, 48), fg=250, bg=6):
    """A mask the way the MODEL draws one: near-white region, near-black background,
    anti-aliased edge. This is the input binarize exists to clean up."""
    image = Image.new("L", size, bg)
    region = Image.new("L", (box[2] - box[0], box[3] - box[1]), fg)
    image.paste(region, box[:2])
    # Blur the edge so there is a genuine grey ramp to threshold.
    return image.filter(__import__("PIL.ImageFilter", fromlist=["GaussianBlur"]).GaussianBlur(2))


def test_binarize_produces_only_two_values():
    result = mask_ops.binarize(_soft_mask())
    assert set(result.tobytes()) <= {0, 255}, "a mask must be exactly {0, 255}"


def test_binarize_keeps_the_region_white():
    """Polarity is a contract: white marks what MAY change. Inverting it silently
    edits the background instead, which looks like a model failure."""
    result = mask_ops.binarize(_soft_mask())
    assert result.getpixel((32, 32)) == 255, "the centre of the region must be white"
    assert result.getpixel((2, 2)) == 0, "the corner outside the region must be black"


def test_binarize_resizes_back_to_the_source_size():
    """The pipeline renders at output_resolution, not at the input's size, so a mask
    comes back at the wrong dimensions and has to be put back."""
    result = mask_ops.binarize(_soft_mask(size=(128, 128)), size=(64, 48))
    assert result.size == (64, 48)
    assert set(result.tobytes()) <= {0, 255}, "the resize must not reintroduce grey"


def test_binarize_adapts_to_a_bright_mask():
    """The reason for Otsu rather than a fixed 128: the model's 'black' drifts with the
    subject. Here the background is 140 - above a fixed threshold - so a fixed cut
    would return an all-white mask and the edit would apply everywhere."""
    bright = _soft_mask(fg=250, bg=140)
    result = mask_ops.binarize(bright)
    values = set(result.tobytes())
    assert values == {0, 255}, f"expected both values, got {values}"
    assert result.getpixel((32, 32)) == 255
    assert result.getpixel((2, 2)) == 0


def test_otsu_on_a_uniform_image_does_not_crash():
    """A degenerate mask - the model drew nothing, or everything. It must return a
    threshold rather than raise, because /edit would otherwise 500 on a bad mask
    instead of producing a (wrong but visible) picture the caller can diagnose."""
    uniform = Image.new("L", (16, 16), 0)
    assert 0 <= mask_ops.otsu_threshold(uniform) <= 255


def test_composite_leaves_the_background_byte_identical():
    """The entire point of composite=true. Outside the white region the result must be
    the ORIGINAL's pixels, not a re-encoded approximation of them."""
    original = Image.new("RGB", (64, 64), (10, 20, 30))
    generated = Image.new("RGB", (64, 64), (200, 100, 50))
    mask = Image.new("L", (64, 64), 0)
    mask.paste(Image.new("L", (32, 32), 255), (16, 16))

    result = mask_ops.composite(original, generated, mask)
    assert result.getpixel((2, 2)) == (10, 20, 30), "outside the mask must be untouched"
    assert result.getpixel((32, 32)) == (200, 100, 50), "inside the mask must be generated"


def test_composite_rescales_a_differently_sized_generation():
    """The pipeline renders at output_resolution, so `generated` routinely arrives at a
    different size from the source. Compositing has to cope rather than raise."""
    original = Image.new("RGB", (64, 64), (10, 20, 30))
    generated = Image.new("RGB", (128, 128), (200, 100, 50))
    mask = Image.new("L", (128, 128), 0)
    mask.paste(Image.new("L", (64, 64), 255), (32, 32))

    result = mask_ops.composite(original, generated, mask)
    assert result.size == (64, 64), "the result keeps the ORIGINAL's size"
    assert result.getpixel((2, 2)) == (10, 20, 30)


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"PASS {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    raise SystemExit(1 if failures else 0)
