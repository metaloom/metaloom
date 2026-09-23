"""
Checkpoint loading for the Qwen-Image-2.1 sidecar — everything that has to be decided
*before* the first denoise step.

Four things here are not obvious from the model card, and each one is a hard crash, a
silent quality loss, or a surprise bill in VRAM if it is skipped:

1. DIFFUSERS COMES FROM GIT, NOT FROM PyPI. `QwenImage21Pipeline` was merged into
   diffusers on 2026-09-18 (PR #14804); the newest release at the time of writing,
   0.40.0, shipped 2026-08-20 and does not contain it. `pip install diffusers`
   therefore gives an ImportError that reads like a typo. requirements.txt pins the
   merge commit rather than tracking main, so a rebuild six months from now still
   produces the pipeline this server was written against. `_require_pipeline()` below
   turns the ImportError into a sentence that says which commit to install.

2. ONE PIPELINE, EVERY MODE. Unlike the Qwen-Image 1.0 family — which has separate
   Pipeline / Img2Img / Inpaint / Edit / EditPlus classes — 2.1 is a single
   `QwenImage21Pipeline` that switches on whether `image=` is passed. Text-to-image,
   instruction edit, multi-reference composition and masked editing are all the same
   object, so there is exactly ONE resident model and no eviction logic of the kind
   mage_loader needs.

3. THERE IS NO `mask_image` PARAMETER, AND THAT IS NOT AN OMISSION. Local editing in
   2.1 is done by handing the model a mask as one of the CONDITION IMAGES — the model
   card's "the original image and a separate mask as two inputs". `__call__` accepts a
   PIL image or a flat list of them and nothing else; see server.py's `/edit`.

4. BF16, NOT FP16. The published weights are bfloat16 and the model card uses bf16
   throughout. float16 on this transformer produces NaN latents on long prompts. The
   dtype is overridable for experiments but the default is deliberately not "whatever
   fits".

VRAM: the weights are 33 GB in bf16 (text encoder 17.5, transformer 14.2, VAE 1.35),
and that is NOT the figure to size a card by. Measured on an H200: ~46 GB resident at
the default output_resolution of 1024, ~66 GB at 2048. The difference is activations
and the KV cache, which torch's caching allocator holds onto between requests - so
resident and peak are the same number and a 40 GB card OOMs on the first generation
despite "fitting" the weights. 48 GB is the floor at 1K, 80 GB at 2K.
`QWENIMAGE_OFFLOAD=1` swaps components CPU<->GPU per stage at a real latency cost.
"""

import logging
import os
import threading

logger = logging.getLogger("qwen-image-server")

DEFAULT_MODEL = os.environ.get("QWENIMAGE_MODEL", "Qwen/Qwen-Image-2.1")

# The diffusers commit that first carried QwenImage21Pipeline. Quoted in the import
# error so a failure tells you how to fix it rather than just what broke.
DIFFUSERS_GIT_REF = "6256aa7666cedd47443adc8f82da9a10e110b09c"


def _default_device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("QWENIMAGE_DEVICE", _default_device())
DTYPE_NAME = os.environ.get("QWENIMAGE_DTYPE", "bfloat16")
OFFLOAD = os.environ.get("QWENIMAGE_OFFLOAD", "0") not in ("0", "", "false", "False")

# Loading is minutes long and 33 GB of VRAM; two concurrent loads would OOM the card.
_load_lock = threading.Lock()
_resident: dict[str, object] = {}


def _dtype():
    import torch

    mapping = {
        "bfloat16": torch.bfloat16,
        "float16": torch.float16,
        "float32": torch.float32,
    }
    if DTYPE_NAME not in mapping:
        raise ValueError(
            f"QWENIMAGE_DTYPE must be one of {sorted(mapping)}, got {DTYPE_NAME!r}"
        )
    return mapping[DTYPE_NAME]


def _require_pipeline():
    """Import QwenImage21Pipeline, or explain precisely why it is not there.

    A bare ImportError here reads as "diffusers is broken". It is not — it is almost
    always a released diffusers, which cannot contain this pipeline at all.
    """
    try:
        from diffusers import QwenImage21Pipeline

        return QwenImage21Pipeline
    except ImportError as exc:
        try:
            import diffusers

            installed = getattr(diffusers, "__version__", "unknown")
        except Exception:
            installed = "not installed"
        raise RuntimeError(
            f"QwenImage21Pipeline is missing from diffusers {installed}. It was merged "
            f"on 2026-09-18 and is not in any release up to 0.40.0, so it has to come "
            f"from git:\n"
            f"  pip install 'diffusers @ git+https://github.com/huggingface/diffusers"
            f"@{DIFFUSERS_GIT_REF}'"
        ) from exc


def load(model_id: str = None):
    """Return the pipeline, loading it on first use.

    Downloads ~33 GB from the Hub the very first time, so the first request after a
    cold start is several minutes before it is several seconds. That is the sidecar
    convention across the fleet, and it is easily mistaken for a hang.
    """
    model_id = model_id or DEFAULT_MODEL
    pipe = _resident.get(model_id)
    if pipe is not None:
        return pipe

    with _load_lock:
        # Re-check: a request that queued behind a load of the same model gets it free.
        pipe = _resident.get(model_id)
        if pipe is not None:
            return pipe

        pipeline_cls = _require_pipeline()
        dtype = _dtype()
        logger.info(
            "Loading %s on %s (dtype=%s, offload=%s) - ~33 GB, first run downloads it",
            model_id, DEVICE, DTYPE_NAME, OFFLOAD,
        )
        pipe = pipeline_cls.from_pretrained(model_id, torch_dtype=dtype)

        if OFFLOAD and DEVICE.startswith("cuda"):
            # Moves components CPU<->GPU per stage. accelerate owns placement from
            # here, so .to(DEVICE) afterwards would undo it - hence the either/or.
            pipe.enable_model_cpu_offload()
        else:
            pipe = pipe.to(DEVICE)

        _resident[model_id] = pipe
        logger.info("Loaded %s", model_id)
        return pipe


def loaded_models() -> list[str]:
    return sorted(_resident.keys())
