"""
Route tests for the Qwen-Image-2.1 sidecar — the request layer, with the model stubbed.

WHAT THESE COVER, AND WHY IT IS WORTH COVERING
----------------------------------------------
Everything in server.py except the denoise itself: argument validation, dimension
snapping, and above all the CONDITION-LIST ASSEMBLY — which images go into `image=`,
in what order, and what the prompt says about them. That assembly is the whole design
(see the server.py docstring: a mask is a condition image, not a parameter), and it is
invisible from the outside, because a wrong condition list produces a perfectly
plausible picture of the wrong thing.

The pipeline is replaced by `FakePipe`, which records its kwargs. So these tests say
nothing about image quality and everything about whether the server asked for the right
image. Quality is a live-GPU question and is answered by qwen_smoke.py.

`_generator` is stubbed too: it is one `torch.Generator` call, and stubbing it is what
lets this file run in CI without a 2 GB torch wheel.

Run:
  pip install pillow fastapi pydantic httpx2 pytest && python -m pytest test_routes.py -q
  # or, with no pytest:
  python test_routes.py
"""

import base64
import io

from fastapi.testclient import TestClient
from PIL import Image

import qwen_loader
import server

# Every kwarg dict handed to the pipeline, in order. Cleared per test.
CALLS = []

SOURCE_SIZE = (256, 192)


class FakePipe:
    """Records the call and returns something shaped like the real result.

    It answers a mask request with a white-on-black rectangle, because the mask path
    binarizes what it gets and a uniform image would make `binarize` degenerate — the
    test would then pass for the wrong reason.
    """

    def __call__(self, **kwargs):
        CALLS.append(kwargs)
        if "segmentation mask" in kwargs["prompt"]:
            image = Image.new("RGB", (512, 512), (0, 0, 0))
            image.paste(Image.new("RGB", (200, 200), (250, 250, 250)), (100, 100))
        else:
            image = Image.new("RGB", (512, 512), (40, 80, 120))

        class Result:
            images = [image]

        return Result()


qwen_loader.load = lambda model_id=None: FakePipe()
server._generator = lambda seed: f"gen({seed})"

client = TestClient(server.app)


def _b64(size=SOURCE_SIZE, colour=(200, 30, 30)):
    buf = io.BytesIO()
    Image.new("RGB", size, colour).save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def setup_function(_):
    CALLS.clear()


# --------------------------------------------------------------------------- #
# Validation - each of these is a caller mistake that must not reach the GPU
# --------------------------------------------------------------------------- #
def test_blank_prompt_is_rejected():
    assert client.post("/generate", json={"prompt": "   "}).status_code == 400


def test_out_of_range_dimension_is_rejected_not_clamped():
    """Clamping would silently give a caller asking for 4096 a 2752 image and hide
    that this checkpoint cannot do what they asked."""
    response = client.post("/generate", json={"prompt": "x", "width": 9999})
    assert response.status_code == 400
    assert "between 256 and 2752" in response.text


def test_empty_image_list_is_rejected():
    assert client.post("/edit", json={"prompt": "x", "images_b64": []}).status_code == 400


def test_too_many_images_are_rejected():
    response = client.post("/edit", json={"prompt": "x", "images_b64": [_b64()] * 11})
    assert response.status_code == 400


def test_mask_b64_and_mask_prompt_together_are_rejected():
    """They are two ways to answer the same question, and honouring one silently would
    leave the caller believing the other took effect."""
    response = client.post("/edit", json={
        "prompt": "x", "images_b64": [_b64()], "mask_b64": _b64(), "mask_prompt": "hair",
    })
    assert response.status_code == 400


def test_composite_without_a_mask_is_rejected():
    response = client.post("/edit", json={
        "prompt": "x", "images_b64": [_b64()], "composite": True,
    })
    assert response.status_code == 400


def test_bad_base64_names_which_image_was_bad():
    """With up to ten images in one request, "could not decode the image" is not a
    usable error message."""
    response = client.post("/edit", json={"prompt": "x", "images_b64": ["not base64!!"]})
    assert response.status_code == 400
    assert "images_b64[0]" in response.text


# --------------------------------------------------------------------------- #
# /generate - the unchanged contract, which must keep working
# --------------------------------------------------------------------------- #
def test_generate_returns_a_png_and_sends_no_condition_image():
    response = client.post("/generate", json={
        "prompt": "a cat", "width": 1000, "height": 1000, "seed": 7,
    })
    assert response.status_code == 200
    assert response.content[:4] == b"\x89PNG"
    assert "image" not in CALLS[0], "text-to-image must not pass a condition image"


def test_generate_snaps_dimensions_to_the_32_pixel_grid():
    client.post("/generate", json={"prompt": "a cat", "width": 1000, "height": 1000})
    assert CALLS[0]["width"] == 1024
    assert CALLS[0]["height"] == 1024


def test_every_image_response_carries_the_model_id():
    """The node reads this header to record producerVersion on the ledger row."""
    response = client.post("/generate", json={"prompt": "a cat"})
    assert response.headers["x-model-id"] == "Qwen/Qwen-Image-2.1"


# --------------------------------------------------------------------------- #
# /remix - also the unchanged contract
# --------------------------------------------------------------------------- #
def test_remix_passes_one_condition_image_and_no_explicit_size():
    """Omitting width/height is load-bearing: the pipeline then derives the size from
    the condition image, which is what preserves the source's framing."""
    response = client.post("/remix", json={
        "image_b64": _b64(), "prompt": "oil painting", "strength": 0.6,
    })
    assert response.status_code == 200
    assert len(CALLS[0]["image"]) == 1
    assert "width" not in CALLS[0] and "height" not in CALLS[0]


# --------------------------------------------------------------------------- #
# /edit - multi-reference composition
# --------------------------------------------------------------------------- #
def test_compose_passes_every_reference_in_one_call():
    """A flat list is one set of condition images for the whole batch. Passing them
    one per call instead would be three unrelated pictures."""
    response = client.post("/edit", json={
        "prompt": "combine them", "images_b64": [_b64(), _b64(), _b64()],
    })
    assert response.status_code == 200
    assert len(CALLS[0]["image"]) == 3


def test_compose_without_a_mask_adds_no_mask_instruction():
    client.post("/edit", json={"prompt": "combine them", "images_b64": [_b64(), _b64()]})
    assert server.MASK_INSTRUCTION not in CALLS[0]["prompt"]


# --------------------------------------------------------------------------- #
# /edit with mask_prompt - the two-step flow, in one request
# --------------------------------------------------------------------------- #
def test_mask_prompt_runs_two_passes():
    response = client.post("/edit", json={
        "prompt": "dark hair", "images_b64": [_b64()], "mask_prompt": "the boy's hair",
    })
    assert response.status_code == 200
    assert len(CALLS) == 2, "expected a mask pass followed by an edit pass"
    assert "segmentation mask" in CALLS[0]["prompt"]


def test_the_derived_mask_is_appended_to_the_condition_list():
    """THE load-bearing assertion of this file. Qwen-Image-2.1 has no mask_image
    parameter; a mask reaches the model as a further condition image or not at all."""
    client.post("/edit", json={
        "prompt": "dark hair", "images_b64": [_b64()], "mask_prompt": "the boy's hair",
    })
    edit_call = CALLS[1]
    assert len(edit_call["image"]) == 2, "source + mask"
    mask = edit_call["image"][-1]
    assert set(mask.convert("L").tobytes()) <= {0, 255}, "the mask must be binarized"
    assert mask.size == SOURCE_SIZE, "the mask must be resized back to the source"


def test_the_edit_pass_tells_the_model_the_last_image_is_a_mask():
    """Without this the model composes the black-and-white shape INTO the picture."""
    client.post("/edit", json={
        "prompt": "dark hair", "images_b64": [_b64()], "mask_prompt": "the boy's hair",
    })
    assert server.MASK_INSTRUCTION in CALLS[1]["prompt"]


def test_a_supplied_mask_is_binarized_too():
    """A caller's mask is not trusted to be {0, 255} either - it may well have come
    from an editor, a screenshot or a JPEG."""
    client.post("/edit", json={
        "prompt": "dark hair", "images_b64": [_b64()],
        "mask_b64": _b64(colour=(200, 200, 200)),
    })
    mask = CALLS[0]["image"][-1]
    assert set(mask.convert("L").tobytes()) <= {0, 255}


# --------------------------------------------------------------------------- #
# /mask - the polarity and format contract the other routes depend on
# --------------------------------------------------------------------------- #
def test_mask_returns_an_eight_bit_binary_mask_at_the_source_size():
    response = client.post("/mask", json={"image_b64": _b64(), "prompt": "the hair"})
    assert response.status_code == 200
    mask = Image.open(io.BytesIO(response.content))
    assert mask.mode == "L"
    assert set(mask.tobytes()) <= {0, 255}
    assert mask.size == SOURCE_SIZE


def test_the_png_carries_provenance_in_text_chunks():
    """The raw-bytes contract leaves no JSON to put this in, and tEXt survives the file
    being written to disk and picked up later."""
    response = client.post("/mask", json={"image_b64": _b64(), "prompt": "the hair"})
    info = Image.open(io.BytesIO(response.content)).info
    assert info["qwenimage:model"] == "Qwen/Qwen-Image-2.1"
    assert info["qwenimage:mode"] == "mask"


# --------------------------------------------------------------------------- #
def test_health_advertises_every_capability():
    report = client.get("/health").json()
    assert set(report["capabilities"]) == {"generate", "remix", "edit", "mask"}
    assert "non-commercial" in report["licence"]


if __name__ == "__main__":
    failures = []
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            CALLS.clear()
            try:
                fn()
                print(f"PASS {name}")
            except AssertionError as exc:
                failures.append(name)
                print(f"FAIL {name}: {exc}")
    print(f"\n{len(failures)} failure(s)" + (": " + ", ".join(failures) if failures else ""))
    raise SystemExit(1 if failures else 0)
