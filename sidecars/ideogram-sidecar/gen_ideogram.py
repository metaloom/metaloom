"""
Reliable Ideogram-4 (nf4) generation on a 12 GB card.

Findings from bring-up (see the sidecar README notes): the two nf4 transformers
(~5.2 GB each) cannot both stay resident on 12 GB, per-step CPU<->GPU swapping of
the transformer produces a degenerate gray "Image blocked by safety filter" card,
and — critically — *any* classifier-free-guidance extrapolation (guidance_scale>1)
pushes this 4-bit checkpoint into that same card attractor. The only stable path
is guidance_scale=1.0 (no CFG): keep the conditional transformer resident, run the
unconditional branch as zeros, and decode. Output is real (lower-contrast than a
24 GB fp8/bf16 run with true asymmetric CFG would give).

Usage: python gen_ideogram.py "<prompt>" <out.png> [steps] [size] [seed]
"""
import sys, os, time
import torch
from PIL import Image
from diffusers import (AutoencoderKLFlux2, FlowMatchEulerDiscreteScheduler,
                       Ideogram4Pipeline, Ideogram4Transformer2DModel)
from diffusers.models.modeling_outputs import Transformer2DModelOutput
from transformers import AutoTokenizer, Qwen3VLModel

GPU, CPU = torch.device("cuda"), torch.device("cpu")
Ideogram4Pipeline._execution_device = property(lambda self: GPU)
REPO = "ideogram-ai/ideogram-4-nf4-diffusers"
DT = torch.bfloat16


def _rgb(img):
    if img.mode == "RGB":
        return img
    bg = Image.new("RGB", img.size, (255, 255, 255))
    bg.paste(img, mask=img.split()[-1] if img.mode == "RGBA" else None)
    return bg


def build(token):
    tok = {"token": token} if token else {}
    te = Qwen3VLModel.from_pretrained(REPO, subfolder="text_encoder", torch_dtype=DT, **tok)
    te.to(CPU); torch.cuda.empty_cache()
    tr = Ideogram4Transformer2DModel.from_pretrained(REPO, subfolder="transformer", torch_dtype=DT, **tok)
    # Park tr on CPU during load/encode so it doesn't co-reside with the text encoder
    # (that OOMs a 12 GB card). The encode wrapper moves it back to GPU once the text
    # encoder is freed, and it then stays resident for the whole denoise (moving it
    # per-step corrupts output).
    tr.to(CPU); torch.cuda.empty_cache()
    vae = AutoencoderKLFlux2.from_pretrained(REPO, subfolder="vae", torch_dtype=DT, **tok).to(GPU)
    tk = AutoTokenizer.from_pretrained(REPO, subfolder="tokenizer", **tok)
    sc = FlowMatchEulerDiscreteScheduler.from_pretrained(REPO, subfolder="scheduler", **tok)

    # Unconditional branch is unused at guidance_scale=1.0 — stub it to zeros so we
    # never need the second 5 GB transformer resident.
    class _ZeroUncond(torch.nn.Module):
        dtype = DT
        config = tr.config
        def forward(self, hidden_states, **kw):
            o = torch.zeros_like(hidden_states)
            return (o,) if kw.get("return_dict", True) is False else Transformer2DModelOutput(sample=o)

    pipe = Ideogram4Pipeline(scheduler=sc, vae=vae, text_encoder=te, tokenizer=tk,
                             transformer=tr, unconditional_transformer=_ZeroUncond())

    # Text encoder is called via submodules (not .forward), so move it to GPU around
    # encode_prompt (bnb-4bit must run on GPU) and free it before the denoise loop.
    _orig = pipe.encode_prompt
    def enc(*a, **k):
        te.to(GPU)
        try:
            return _orig(*a, **k)
        finally:
            te.to(CPU); torch.cuda.empty_cache(); tr.to(GPU)
    pipe.encode_prompt = enc
    return pipe


def main():
    prompt = sys.argv[1]
    out = sys.argv[2]
    steps = int(sys.argv[3]) if len(sys.argv) > 3 else 50
    size = int(sys.argv[4]) if len(sys.argv) > 4 else 1024
    seed = int(sys.argv[5]) if len(sys.argv) > 5 else 0
    pipe = build(os.environ.get("HF_TOKEN"))
    print("LOADED", flush=True)
    t = time.time()
    img = pipe(prompt, height=size, width=size, num_inference_steps=steps,
               guidance_scale=1.0, guidance_schedule=None,
               generator=torch.Generator("cuda").manual_seed(seed)).images[0]
    _rgb(img).save(out)
    print(f"GENERATED {out} in {time.time()-t:.0f}s peakVRAM={round(torch.cuda.max_memory_allocated()/1e9,2)}GB", flush=True)


if __name__ == "__main__":
    main()
