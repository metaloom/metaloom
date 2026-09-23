"""
Mask arithmetic for the Qwen-Image-2.1 sidecar — the part that is neither model output
nor HTTP glue, and therefore the only part with unit tests (test_mask_ops.py).

It lives in its own module for exactly that reason: server.py imports fastapi, and
fastapi pulls in a dependency tree that a test of "does Otsu's method work" should not
need. Nothing here imports torch, diffusers or fastapi.

The contract these three functions share, and that /edit and /mask both rely on:

    A MASK IS AN 8-BIT "L" IMAGE WHOSE PIXELS ARE EXACTLY 0 OR 255,
    WHITE OVER THE REGION THAT MAY CHANGE.

Qwen-Image-2.1 does not produce that. It produces a *picture of* a mask — anti-aliased
edges, "white" around 250, a compression-ish halo, occasionally a helpful text label.
`binarize` is what turns the one into the other, and it runs on every mask the server
handles, whether the model drew it or a caller supplied it.
"""

from PIL import Image


def otsu_threshold(gray: Image.Image) -> int:
    """Otsu's method: the cut minimising intra-class variance of the histogram.

    Hand-rolled rather than pulled from OpenCV or scikit-image, which would be the two
    heaviest dependencies this sidecar does not otherwise need, in exchange for thirty
    lines of arithmetic over a 256-bin histogram.

    A fixed threshold of 128 was the first attempt and is wrong: the model's idea of
    "black" drifts with the subject, so a mask over a bright region comes back lighter
    overall and a fixed cut either swallows the region or keeps the background.
    """
    histogram = gray.histogram()
    total = sum(histogram)
    if total == 0:
        return 127

    sum_all = sum(i * histogram[i] for i in range(256))
    sum_background = 0.0
    weight_background = 0
    best_variance = -1.0
    best_threshold = 127

    for t in range(256):
        weight_background += histogram[t]
        if weight_background == 0:
            continue
        weight_foreground = total - weight_background
        if weight_foreground == 0:
            break
        sum_background += t * histogram[t]
        mean_background = sum_background / weight_background
        mean_foreground = (sum_all - sum_background) / weight_foreground
        variance = weight_background * weight_foreground * (mean_background - mean_foreground) ** 2
        if variance > best_variance:
            best_variance = variance
            best_threshold = t

    return best_threshold


def binarize(image: Image.Image, size: tuple = None) -> Image.Image:
    """Turn the model's rendering of a mask into an actual mask.

    `size` resizes back to the source's dimensions, which is needed because the
    pipeline renders at `output_resolution` rather than at the input's size. NEAREST,
    so the resize cannot reintroduce the grey edge that was just removed — a LANCZOS
    resize here would undo the whole function.
    """
    gray = image.convert("L")
    threshold = otsu_threshold(gray)
    mask = gray.point(lambda p: 255 if p > threshold else 0, mode="L")
    if size is not None and mask.size != size:
        mask = mask.resize(size, Image.NEAREST)
    return mask


def composite(original: Image.Image, generated: Image.Image, mask: Image.Image) -> Image.Image:
    """Blend `generated` over `original` through `mask`, guaranteeing locality.

    Off by default (`composite: false` on /edit) because passing the mask as a
    condition image is the model's OWN preservation mechanism and is usually enough.
    This is the belt for callers who need a hard guarantee that nothing outside the
    region moved.

    It is not free: the pipeline renders at `output_resolution`, so `generated` has to
    be scaled back to the source's size before the blend, and that upscale costs
    quality on the edited region too. LANCZOS keeps it as small as it can be.
    """
    if generated.size != original.size:
        generated = generated.resize(original.size, Image.LANCZOS)
    if mask.size != original.size:
        mask = mask.resize(original.size, Image.NEAREST)
    return Image.composite(generated.convert("RGB"), original.convert("RGB"), mask.convert("L"))
