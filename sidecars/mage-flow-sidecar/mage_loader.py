"""
Checkpoint loading for the Mage-Flow sidecar — everything that has to be decided
*before* the first denoise step.

Three things here are not obvious from upstream's README, and each one is a wrong
image or a hard crash if it is skipped:

1. ATTENTION BACKEND. Mage-Flow's own default is `flash2`, i.e. flash-attn, and that
   default is baked into `ModelConfig.attn_type` — not into the checkpoint. The
   published `transformer/config.json` carries no `attn_type` key, so `load_from_repo`
   always constructs the config with the flash2 default, and the model dies on the
   first attention call if flash-attn is not installed. Upstream ships an SDPA
   fallback for exactly this case but leaves no plumbing to select it, so we select
   it ourselves, on two separate paths:

     * the DiT + packed-text path goes through upstream's varlen shim, switched with
       `set_attn_backend()`. It must be called AFTER the model is built, because
       `MageFlowModel.__init__` calls it with the flash2 config value and would
       overwrite an earlier choice. The shim resolves the kernel lazily on first
       call, so a post-load switch still wins.
     * the HuggingFace text encoder resolves its own `attn_implementation` at load
       time, so that one has to be set BEFORE, via the `VF_HF_ATTN_IMPL` env var
       upstream reads for this purpose.

2. STEP / CFG DEFAULTS PER VARIANT. The variants are not interchangeable at fixed
   settings: Turbo is distilled for 4 steps at cfg 1.0 (no CFG branch), the
   RL-aligned model wants 20 steps at cfg 5.0, Base 30. Running Turbo at cfg 5.0
   produces a scorched, oversaturated image and takes ~10x longer, which reads as
   "the model is bad" rather than "the caller passed the wrong number". So the
   defaults are derived from the checkpoint name instead of being one constant.

3. ONE RESIDENT MODEL. Each repo is fully self-contained — every checkpoint ships
   its own copy of the 8.9 GB text encoder — so t2i + edit resident together is
   ~35 GB and does not fit on a 24 GB card. Loading a second model evicts the
   first rather than OOMing.
"""

import logging
import os
import threading

logger = logging.getLogger("mage-flow-server")

# Text-to-image and edit checkpoints. Turbo by default on both: 4 steps is what makes
# this usable as a request-scoped sidecar, and it costs ~0.02 GenEval against the
# 5x-slower RL-aligned variant.
DEFAULT_T2I_MODEL = os.environ.get("MAGEFLOW_MODEL", "microsoft/Mage-Flow-Turbo")
DEFAULT_EDIT_MODEL = os.environ.get("MAGEFLOW_EDIT_MODEL", "microsoft/Mage-Flow-Edit-Turbo")

# auto | flash2 | flash4 | sdpa
ATTN_BACKEND_SETTING = os.environ.get("MAGEFLOW_ATTN", "auto")


def _default_device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("MAGEFLOW_DEVICE", _default_device())


def variant_defaults(model_id: str) -> tuple[int, float]:
    """(steps, cfg) the given checkpoint was actually tuned for.

    Turbo is distilled to 4 steps with CFG disabled; the RL-aligned t2i model to 20;
    Base and the RL-aligned edit model to 30. All non-Turbo variants use cfg 5.0.
    """
    name = model_id.rsplit("/", 1)[-1].lower()
    if "turbo" in name:
        return 4, 1.0
    if "base" in name or "edit" in name:
        return 30, 5.0
    return 20, 5.0


def resolve_attn_backend() -> str:
    """Pick the attention kernel: honour MAGEFLOW_ATTN, else flash-attn if importable.

    `auto` exists because flash-attn is the faster kernel but is unbuildable on a lot
    of machines (it compiles against torch's CUDA ABI and needs a major-version-matched
    nvcc). Probing beats asking every deployment to configure it.
    """
    requested = (ATTN_BACKEND_SETTING or "auto").strip().lower()
    if requested != "auto":
        return requested
    try:
        import flash_attn  # noqa: F401

        return "flash2"
    except Exception:
        logger.info("flash-attn is not installed - falling back to torch SDPA attention")
        return "sdpa"


ATTN_BACKEND = resolve_attn_backend()

# Loading is minutes long and ~18 GB of VRAM; two concurrent loads would OOM the card.
_load_lock = threading.Lock()
_resident: dict[str, object] = {}


def _evict() -> None:
    """Drop the resident pipeline and give the VRAM back to the driver.

    empty_cache() is not cosmetic here: torch's caching allocator would hold the
    freed 17.5 GB as reserved-but-unused, and the *next* model would OOM against a
    card that `nvidia-smi` reports as full while python thinks it is empty.
    """
    if not _resident:
        return
    stale = list(_resident.keys())
    _resident.clear()
    try:
        import gc

        import torch

        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass
    logger.info("Evicted %s to free VRAM", ", ".join(stale))


def load(model_id: str):
    """Return the pipeline for `model_id`, loading (and evicting the other) if needed.

    Downloads the repo from the Hub on first use — ~17.5 GB, so the first call is
    several minutes before it is several seconds.
    """
    pipe = _resident.get(model_id)
    if pipe is not None:
        return pipe

    with _load_lock:
        # Re-check: a request that queued behind a load of the same model gets it free.
        pipe = _resident.get(model_id)
        if pipe is not None:
            return pipe
        _evict()

        backend = ATTN_BACKEND
        if backend == "sdpa":
            # Read by upstream's TextEncoder at construction time; must be set first.
            os.environ["VF_HF_ATTN_IMPL"] = "sdpa"

        logger.info("Loading %s on %s (attn=%s, bf16)", model_id, DEVICE, backend)
        from mage_flow import MageFlowPipeline

        pipe = MageFlowPipeline.from_pretrained(model_id, device=DEVICE)

        # After construction, never before - see note 1 in the module docstring.
        from mage_flow.models.modules._attn_backend import set_attn_backend

        set_attn_backend(backend)

        _resident[model_id] = pipe
        logger.info("Loaded %s", model_id)
        return pipe


def loaded_models() -> list[str]:
    return sorted(_resident.keys())
