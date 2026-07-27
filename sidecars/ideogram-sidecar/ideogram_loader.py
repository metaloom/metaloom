"""
Staged loader + manual CPU-offload for Ideogram-4 (nf4) on limited VRAM.

Two problems have to be solved to run the ~16 GB nf4 checkpoint on a 12 GB card:

1. **Loading.** bitsandbytes refuses to *load* 4-bit modules onto CPU, and
   accelerate's device_map offload sends them to the *meta* device (no data),
   which crashes on the just-in-time move to GPU. But a bnb-4bit module CAN be
   loaded to GPU and then `.to("cpu")`-moved and back. So we load each big
   component to GPU one at a time (peak ~5.4 GB) and park it on CPU.

2. **Offloading during inference.** diffusers' `enable_model_cpu_offload()` is
   NOT usable here: its CpuOffload hook attaches to each component's `.forward`,
   but Ideogram4Pipeline calls the text encoder's *submodules* directly
   (`_get_text_encoder_hidden_states`), so the hook never onloads it — the
   encoder runs on CPU, where bnb-4bit yields broken features and the model
   collapses to its unconditional output (a gray "Image blocked by safety
   filter" card) for every prompt. It also leaves both transformers resident
   and OOMs. Instead we offload manually and deterministically:

     * VAE (small) stays on GPU.
     * text_encoder is moved to GPU around `encode_prompt` and back to CPU after
       — so the directly-called submodules actually run on GPU.
     * each transformer is moved to GPU by a forward *pre*-hook and evicted to
       CPU by a forward *post*-hook, so only one ~5 GB transformer is resident.
     * `_execution_device` is forced to CUDA so the pipeline builds latents and
       conditioning tensors on the GPU while the heavy modules live on CPU.

   Peak VRAM ~7-9 GB; fits a 12 GB card.
"""

import torch
from diffusers import (
    AutoencoderKLFlux2,
    FlowMatchEulerDiscreteScheduler,
    Ideogram4Pipeline,
    Ideogram4Transformer2DModel,
)
from transformers import AutoTokenizer, Qwen3VLModel

_GPU = torch.device("cuda")
_CPU = torch.device("cpu")

# Force the pipeline's execution device to CUDA regardless of where the (mostly
# CPU-parked) modules physically sit. Latents/conditioning are then built on GPU.
Ideogram4Pipeline._execution_device = property(lambda self: _GPU)


def _park(module):
    """Move a freshly-loaded (GPU-resident) bnb module to CPU, freeing VRAM."""
    module.to(_CPU)
    torch.cuda.empty_cache()
    return module


def _wrap_forward_with_swap(module):
    """Wrap module.forward so the module is on GPU for the call and evicted to
    CPU right after — implemented by wrapping forward directly (NOT via
    register_forward_hook, whose output-replacement semantics corrupted the
    bnb-4bit output in practice). Moving bnb-4bit weights CPU<->GPU is a verified
    no-op on the math, so only one ~5 GB transformer is resident at a time."""
    orig = module.forward

    def swapped(*args, **kwargs):
        module.to(_GPU)
        try:
            return orig(*args, **kwargs)
        finally:
            module.to(_CPU)
            torch.cuda.empty_cache()

    module.forward = swapped


def load_ideogram(repo: str, dtype=torch.bfloat16, token=None):
    """Load Ideogram-4 with deterministic manual CPU offload. Returns a ready
    Ideogram4Pipeline that fits a ~12 GB card."""
    tok = {"token": token} if token else {}

    # Heavy bnb-4bit components: load to GPU (bnb requirement), then park on CPU.
    text_encoder = _park(Qwen3VLModel.from_pretrained(
        repo, subfolder="text_encoder", torch_dtype=dtype, **tok))
    transformer = _park(Ideogram4Transformer2DModel.from_pretrained(
        repo, subfolder="transformer", torch_dtype=dtype, **tok))
    unconditional_transformer = _park(Ideogram4Transformer2DModel.from_pretrained(
        repo, subfolder="unconditional_transformer", torch_dtype=dtype, **tok))

    # Light components. VAE stays resident on GPU.
    vae = AutoencoderKLFlux2.from_pretrained(repo, subfolder="vae", torch_dtype=dtype, **tok).to(_GPU)
    tokenizer = AutoTokenizer.from_pretrained(repo, subfolder="tokenizer", **tok)
    scheduler = FlowMatchEulerDiscreteScheduler.from_pretrained(repo, subfolder="scheduler", **tok)

    pipe = Ideogram4Pipeline(
        scheduler=scheduler,
        vae=vae,
        text_encoder=text_encoder,
        tokenizer=tokenizer,
        transformer=transformer,
        unconditional_transformer=unconditional_transformer,
    )

    # Each transformer: onload on GPU for its forward, evict to CPU right after.
    _wrap_forward_with_swap(transformer)
    _wrap_forward_with_swap(unconditional_transformer)

    # text_encoder is called via submodules, not .forward, so wrap encode_prompt:
    # move it to GPU for the encode (so bnb-4bit runs on GPU → correct features),
    # then back to CPU to free ~5.5 GB before the denoise loop.
    _orig_encode = pipe.encode_prompt

    def _encode_on_gpu(*args, **kwargs):
        text_encoder.to(_GPU)
        try:
            return _orig_encode(*args, **kwargs)
        finally:
            text_encoder.to(_CPU)
            torch.cuda.empty_cache()

    pipe.encode_prompt = _encode_on_gpu
    return pipe
