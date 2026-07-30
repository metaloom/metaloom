"""
Checkpoint loading for the LTX-2 video sidecar — everything that has to be decided
*before* the first denoise step.

LTX-2 (Lightricks/LTX-2) is not one 19B model, it is a ~46B system: a 19B DiT
transformer PLUS a 27B Gemma3 text encoder (94 GB in bf16 on disk) PLUS video/audio
VAEs and a vocoder. The documented bf16 diffusers path is therefore a multi-GPU /
datacenter path — the transformer alone is ~38 GB, and the text encoder another ~94 GB,
so neither the 24 GB of VRAM nor a typical CPU RAM budget can hold it.

WHAT ACTUALLY FITS 24 GB (verified on an RTX 4090, peak ~11 GB VRAM):
load BOTH heavy components as bitsandbytes nf4 4-bit, quantized *directly onto the GPU*
(device_map + low_cpu_mem_usage) so the 94 GB text encoder never materialises in CPU RAM,
then hand both to the pipeline and `enable_model_cpu_offload()`:

    text_encoder = Gemma3ForConditionalGeneration.from_pretrained(  # 27B -> ~8 GB nf4
        repo, subfolder="text_encoder", quantization_config=<nf4>, device_map=cuda)
    transformer  = LTX2VideoTransformer3DModel.from_pretrained(     # 19B -> ~10 GB nf4
        repo, subfolder="transformer",  quantization_config=<nf4>, device_map=cuda)
    pipe = LTX2Pipeline.from_pretrained(repo, transformer=..., text_encoder=..., ...)
    pipe.enable_model_cpu_offload(); pipe.vae.enable_tiling()

Two dead ends ruled out on the way (kept here so nobody re-treads them):
  * The fp8 single-file transformer (`ltx-2-19b-*-fp8.safetensors`) UPCASTS to bf16 in
    diffusers 0.39 — LTX2VideoTransformer3DModel is not fp8-quant-aware, it drops the
    `*_scale` tensors — so it is just the 38 GB bf16 path and OOMs a 24 GB card.
  * Quantizing only the transformer still OOMs: the 94 GB bf16 text encoder is loaded to
    CPU RAM by the pipeline and blows the RAM budget. The text encoder must be quantized
    too, and streamed to the GPU rather than through CPU RAM.

VAE decode of a whole video OOMs without tiling (LTX2_VAE_TILING=1, default on).

Text-to-video and image-to-video are two different pipeline classes; the i2v pipeline is
built from the t2v pipeline's already-quantized components so nothing loads twice.

One resident model, one generation at a time (see server.py `_gpu_lock`).
"""

import logging
import os
import threading

logger = logging.getLogger("ltx2-server")

MODEL_REPO = os.environ.get("LTX2_MODEL", "Lightricks/LTX-2")

# nf4 | none. nf4 (the default on CUDA) is what makes LTX-2 fit a 24 GB card; `none`
# loads bf16 and needs a >=48 GB / multi-GPU host.
QUANTIZE = os.environ.get("LTX2_QUANTIZE", "nf4").strip().lower()


def _default_device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("LTX2_DEVICE", _default_device())
# Concrete device for the quantized-load device_map (bnb needs a real GPU, not "cuda").
_QUANT_DEVICE = DEVICE if ":" in DEVICE else (DEVICE + ":0" if DEVICE.startswith("cuda") else DEVICE)

DTYPE_SETTING = os.environ.get("LTX2_DTYPE", "bfloat16" if DEVICE.startswith("cuda") else "float32")

# none | model | sequential.
CPU_OFFLOAD = os.environ.get("LTX2_CPU_OFFLOAD", "model").strip().lower()

# VAE tiling — on by default; a whole-video VAE decode OOMs without it.
VAE_TILING = os.environ.get("LTX2_VAE_TILING", "1") not in ("0", "", "false", "False")

_USE_NF4 = QUANTIZE == "nf4" and DEVICE.startswith("cuda")


def _resolve_dtype():
    import torch

    return {"float16": torch.float16, "bfloat16": torch.bfloat16, "float32": torch.float32}[DTYPE_SETTING]


# Loading is minutes long; serialise it.
_load_lock = threading.Lock()
_t2v = None            # LTX2Pipeline
_i2v = None            # LTX2ImageToVideoPipeline (shares components with _t2v)


def _apply_placement(pipe) -> None:
    """Apply the offload strategy and VAE tiling to a freshly built pipeline."""
    if VAE_TILING:
        try:
            pipe.vae.enable_tiling()
            logger.info("VAE tiling enabled")
        except Exception as exc:  # pragma: no cover
            logger.warning("Could not enable VAE tiling: %s", exc)

    if not DEVICE.startswith("cuda") or CPU_OFFLOAD == "none":
        pipe.to(DEVICE)
    elif CPU_OFFLOAD == "sequential":
        pipe.enable_sequential_cpu_offload()
        logger.info("Sequential CPU offload enabled")
    else:  # "model" (default)
        pipe.enable_model_cpu_offload()
        logger.info("Model CPU offload enabled")


def _load_quantized_components(dtype):
    """Quantize the 27B text encoder and 19B transformer to nf4 directly on the GPU.

    Returns (text_encoder, transformer). The text encoder is parked back on the CPU after
    quantisation so both do not sit resident on the GPU at the same time before the
    pipeline's own offload takes over.
    """
    import gc

    import torch
    from diffusers import BitsAndBytesConfig as DiffusersBnb
    from diffusers import LTX2VideoTransformer3DModel
    from transformers import BitsAndBytesConfig as TransformersBnb
    from transformers import Gemma3ForConditionalGeneration

    t_bnb = TransformersBnb(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=dtype)
    d_bnb = DiffusersBnb(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=dtype)

    logger.info("Quantizing 27B text encoder to nf4 on %s (streamed, not via CPU RAM)", _QUANT_DEVICE)
    text_encoder = Gemma3ForConditionalGeneration.from_pretrained(
        MODEL_REPO, subfolder="text_encoder", quantization_config=t_bnb,
        torch_dtype=dtype, device_map=_QUANT_DEVICE, low_cpu_mem_usage=True,
    )
    text_encoder.to("cpu")
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    logger.info("Quantizing 19B transformer to nf4 on %s", _QUANT_DEVICE)
    transformer = LTX2VideoTransformer3DModel.from_pretrained(
        MODEL_REPO, subfolder="transformer", quantization_config=d_bnb,
        torch_dtype=dtype, device_map=_QUANT_DEVICE, low_cpu_mem_usage=True,
    )
    return text_encoder, transformer


def load():
    """Return the text-to-video pipeline, loading it (once) on first use.

    First call downloads the LTX-2 repo components (the text encoder alone is ~94 GB) and
    quantizes the two heavy ones — several minutes before it is several seconds.
    """
    global _t2v
    if _t2v is not None:
        return _t2v
    with _load_lock:
        if _t2v is not None:
            return _t2v
        import torch  # noqa: F401
        from diffusers import LTX2Pipeline

        dtype = _resolve_dtype()
        logger.info(
            "Loading %s on %s (quantize=%s, offload=%s, vae_tiling=%s)",
            MODEL_REPO, DEVICE, QUANTIZE if _USE_NF4 else "none", CPU_OFFLOAD, VAE_TILING,
        )
        kwargs = {"torch_dtype": dtype, "low_cpu_mem_usage": True}
        if _USE_NF4:
            text_encoder, transformer = _load_quantized_components(dtype)
            kwargs["text_encoder"] = text_encoder
            kwargs["transformer"] = transformer
        pipe = LTX2Pipeline.from_pretrained(MODEL_REPO, **kwargs)
        _apply_placement(pipe)
        _t2v = pipe
        logger.info("Loaded LTX2Pipeline (text-to-video)")
        return _t2v


def load_i2v():
    """Return the image-to-video pipeline, reusing the t2v pipeline's quantized components."""
    global _i2v
    if _i2v is not None:
        return _i2v
    with _load_lock:
        if _i2v is not None:
            return _i2v
        from diffusers import LTX2ImageToVideoPipeline

        base = load()
        _i2v = LTX2ImageToVideoPipeline(**base.components)
        # Offload hooks live on the shared modules from `base`, so the offloaded weights
        # onload/offload for this pipeline's forwards too; no re-offload needed.
        logger.info("Built LTX2ImageToVideoPipeline (image-to-video) from resident components")
        return _i2v


def loaded_models() -> list[str]:
    names = []
    if _t2v is not None:
        names.append("text-to-video")
    if _i2v is not None:
        names.append("image-to-video")
    return names
