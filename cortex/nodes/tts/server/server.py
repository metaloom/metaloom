"""
TTS sidecar — FastAPI HTTP server for the Cortex `tts` node.

One endpoint, `POST /v1/tts`, routes by language:

  * lang="de"  → Orpheus-3B / Kartoffel  (best-in-class German, see audio-eval/TTS.md)
  * lang="en"  → Kokoro-82M              (lightweight English)

Orpheus is a Llama-3.2 backbone that emits SNAC audio tokens, so the audio
decode (SNAC de-interleave + SNAC.decode → 24 kHz WAV) always happens here.
The heavy LM generation can run one of two ways, selected by ORPHEUS_BACKEND:

  * "transformers" (default) — load the checkpoint in-process (self-contained).
  * "vllm"                   — proxy token generation to a vLLM / llama.cpp
                               OpenAI-compatible endpoint (ORPHEUS_LLM_ENDPOINT)
                               and only decode SNAC locally. This is the
                               production path: vLLM/llama.cpp does the LM work,
                               this server just turns tokens into audio.

The Java `TtsNode` is a pure HTTP client of this server (io.metaloom.cortex.node.tts).

Run:
  uvicorn server:app --host 0.0.0.0 --port 9100

Environment variables:
  ORPHEUS_BACKEND     transformers | vllm            (default: transformers)
  ORPHEUS_LLM_ENDPOINT vLLM/llama.cpp base URL        (default: http://localhost:8000)
  ORPHEUS_REPO_DE     German Orpheus checkpoint       (default: SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1)
  ORPHEUS_REPO_DE_MODEL  model id announced by vLLM   (default: same as ORPHEUS_REPO_DE)
  SNAC_REPO           SNAC decoder repo               (default: hubertsiuzdak/snac_24khz)
  KOKORO_MODEL        path to kokoro-v1.0.onnx        (default: models/kokoro-v1.0.onnx)
  KOKORO_VOICES       path to voices-v1.0.bin         (default: models/voices-v1.0.bin)
  DEVICE              torch device                    (default: cuda if available else cpu)

Note: `Thorsten-Voice/tv-orpheus-v1` is an ungated, Apache-2.0 German checkpoint
(single speaker "thorsten"); set ORPHEUS_REPO_DE to it to avoid the gated
Kartoffel repo. Kartoffel needs an accepted HF licence + HF_TOKEN.
"""

import io
import os
import re
import logging
from typing import List

import numpy as np
import soundfile as sf
import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("tts-server")

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
ORPHEUS_BACKEND = os.environ.get("ORPHEUS_BACKEND", "transformers")
ORPHEUS_LLM_ENDPOINT = os.environ.get("ORPHEUS_LLM_ENDPOINT", "http://localhost:8000")
ORPHEUS_REPO_DE = os.environ.get(
    "ORPHEUS_REPO_DE", "SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1"
)
ORPHEUS_REPO_DE_MODEL = os.environ.get("ORPHEUS_REPO_DE_MODEL", ORPHEUS_REPO_DE)
SNAC_REPO = os.environ.get("SNAC_REPO", "hubertsiuzdak/snac_24khz")
KOKORO_MODEL = os.environ.get("KOKORO_MODEL", "models/kokoro-v1.0.onnx")
KOKORO_VOICES = os.environ.get("KOKORO_VOICES", "models/voices-v1.0.bin")


def _default_device() -> str:
    try:
        import torch
        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("DEVICE", _default_device())

# Orpheus SNAC token layout — identical to audio-eval/tts/orpheus/run.py.
TOK_START_HUMAN = 128259  # <|begin_of_human|>
TOK_END_TEXT = 128009     # <|eot_id|>
TOK_END_HUMAN = 128260    # <|end_of_human|>
TOK_START_AUDIO = 128257  # <|begin_of_audio|>
TOK_END_AUDIO = 128258    # <|end_of_audio|> (also the generation EOS)
TOK_AUDIO_BASE = 128266   # first SNAC code token
CODEBOOK_SIZE = 4096
FRAME = 7                 # SNAC codes per frame, interleaved 1/2/3/3/2/3/3

_CUSTOM_TOKEN_RE = re.compile(r"<custom_token_(\d+)>")


# --------------------------------------------------------------------------- #
# SNAC decode — shared by both Orpheus backends
# --------------------------------------------------------------------------- #
def _to_snac_layers(codes: List[int]):
    """De-interleave flat SNAC codes into the three hierarchical codebooks."""
    import torch

    usable = (len(codes) // FRAME) * FRAME
    if usable == 0:
        raise RuntimeError(
            f"Orpheus emitted {len(codes)} audio tokens, fewer than one {FRAME}-token frame"
        )
    codes = codes[:usable]

    coarse, mid, fine = [], [], []
    for i in range(len(codes) // FRAME):
        f = codes[FRAME * i: FRAME * i + FRAME]
        coarse.append(f[0])
        mid.append(f[1] - CODEBOOK_SIZE)
        fine.append(f[2] - 2 * CODEBOOK_SIZE)
        fine.append(f[3] - 3 * CODEBOOK_SIZE)
        mid.append(f[4] - 4 * CODEBOOK_SIZE)
        fine.append(f[5] - 5 * CODEBOOK_SIZE)
        fine.append(f[6] - 6 * CODEBOOK_SIZE)

    layers = [torch.tensor(layer).unsqueeze(0) for layer in (coarse, mid, fine)]
    for layer in layers:
        if layer.min() < 0 or layer.max() >= CODEBOOK_SIZE:
            raise RuntimeError(
                f"SNAC code out of range [{layer.min()}, {layer.max()}] — token layout mismatch"
            )
    return layers


_snac = None


def _snac_decode(codes: List[int]) -> bytes:
    """Decode raw SNAC code values into 24 kHz WAV bytes."""
    global _snac
    import torch
    from snac import SNAC

    if _snac is None:
        logger.info("Loading SNAC decoder %s on %s ...", SNAC_REPO, DEVICE)
        _snac = SNAC.from_pretrained(SNAC_REPO).eval().to(DEVICE)

    layers = [layer.to(DEVICE) for layer in _to_snac_layers(codes)]
    with torch.inference_mode():
        audio = _snac.decode(layers)
    waveform = audio.squeeze().float().cpu().numpy()

    buf = io.BytesIO()
    sf.write(buf, waveform, 24000, format="WAV", subtype="PCM_16")
    return buf.getvalue()


# --------------------------------------------------------------------------- #
# Orpheus (German) — transformers backend
# --------------------------------------------------------------------------- #
_orpheus_model = None
_orpheus_tokenizer = None


def _load_orpheus():
    global _orpheus_model, _orpheus_tokenizer
    if _orpheus_model is not None:
        return
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    logger.info("Loading Orpheus checkpoint %s on %s ...", ORPHEUS_REPO_DE, DEVICE)
    _orpheus_tokenizer = AutoTokenizer.from_pretrained(ORPHEUS_REPO_DE)
    model = AutoModelForCausalLM.from_pretrained(ORPHEUS_REPO_DE, dtype=torch.bfloat16)
    _orpheus_model = model.to(DEVICE).eval()


def _orpheus_prompt_text(text: str, voice: str) -> str:
    """Multi-speaker Orpheus prompt framing: ``{voice}: {text}``."""
    return f"{voice}: {text}"


def _orpheus_transformers(text: str, voice: str) -> bytes:
    import torch

    _load_orpheus()
    ids = _orpheus_tokenizer(_orpheus_prompt_text(text, voice), return_tensors="pt").input_ids
    start = torch.tensor([[TOK_START_HUMAN]], dtype=torch.int64)
    end = torch.tensor([[TOK_END_TEXT, TOK_END_HUMAN]], dtype=torch.int64)
    input_ids = torch.cat([start, ids, end], dim=1).to(DEVICE)

    with torch.inference_mode():
        generated = _orpheus_model.generate(
            input_ids,
            attention_mask=torch.ones_like(input_ids),
            max_new_tokens=4096,
            do_sample=True,
            temperature=0.6,
            top_p=0.95,
            repetition_penalty=1.1,
            eos_token_id=TOK_END_AUDIO,
            pad_token_id=TOK_END_AUDIO,
        )

    row = generated[0].tolist()
    if TOK_START_AUDIO in row:
        row = row[len(row) - 1 - row[::-1].index(TOK_START_AUDIO) + 1:]
    codes = [t - TOK_AUDIO_BASE for t in row if t != TOK_END_AUDIO and t >= TOK_AUDIO_BASE]
    return _snac_decode(codes)


# --------------------------------------------------------------------------- #
# Orpheus (German) — vLLM / llama.cpp proxy backend
# --------------------------------------------------------------------------- #
def _orpheus_vllm(text: str, voice: str) -> bytes:
    """Generate SNAC tokens on a vLLM/llama.cpp OpenAI-compatible endpoint, decode here.

    vLLM returns the audio tokens as ``<custom_token_N>`` strings in the completion
    text; we map them back to raw SNAC code values (N - 10, matching the Orpheus
    tokenizer's custom-token offset) and decode locally.
    """
    prompt = f"<|begin_of_human|>{_orpheus_prompt_text(text, voice)}<|eot_id|><|end_of_human|>"
    url = f"{ORPHEUS_LLM_ENDPOINT}/v1/completions"
    payload = {
        "model": ORPHEUS_REPO_DE_MODEL,
        "prompt": prompt,
        "max_tokens": 4096,
        "temperature": 0.6,
        "top_p": 0.95,
        "stop": ["<|end_of_audio|>"],
    }
    with httpx.Client(timeout=120.0) as client:
        resp = client.post(url, json=payload)
        if resp.status_code != 200:
            raise HTTPException(status_code=502, detail=f"Orpheus vLLM backend error: {resp.text[:500]}")
        completion = resp.json()["choices"][0]["text"]

    # Map <custom_token_N> back to raw SNAC code values. The Orpheus tokenizer
    # numbers audio tokens starting at custom_token_10 == SNAC code 0.
    codes = [int(n) - 10 for n in _CUSTOM_TOKEN_RE.findall(completion)]
    codes = [c for c in codes if c >= 0]
    return _snac_decode(codes)


def _synthesize_de(text: str, voice: str) -> bytes:
    if ORPHEUS_BACKEND == "vllm":
        return _orpheus_vllm(text, voice)
    return _orpheus_transformers(text, voice)


# --------------------------------------------------------------------------- #
# Kokoro (English)
# --------------------------------------------------------------------------- #
_kokoro = None


def _synthesize_en(text: str, voice: str) -> bytes:
    global _kokoro
    from kokoro_onnx import Kokoro

    if _kokoro is None:
        if not os.path.exists(KOKORO_MODEL) or not os.path.exists(KOKORO_VOICES):
            raise HTTPException(
                status_code=503,
                detail=f"Kokoro weights missing ({KOKORO_MODEL} / {KOKORO_VOICES}); run setup.sh",
            )
        logger.info("Loading Kokoro %s ...", KOKORO_MODEL)
        _kokoro = Kokoro(KOKORO_MODEL, KOKORO_VOICES)

    samples, rate = _kokoro.create(text, voice=voice or "af_heart", speed=1.0, lang="en-us")
    buf = io.BytesIO()
    sf.write(buf, samples, rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()


# --------------------------------------------------------------------------- #
# FastAPI app
# --------------------------------------------------------------------------- #
app = FastAPI(title="Cortex TTS Server")


class TTSRequest(BaseModel):
    text: str
    lang: str = "de"          # "de" -> Orpheus/Kartoffel, "en" -> Kokoro
    voice: str = "Jakob"
    format: str = "wav"


@app.get("/health")
async def health():
    return {"status": "ok", "orpheus_backend": ORPHEUS_BACKEND, "device": DEVICE}


@app.get("/voices")
async def voices():
    return {
        "de": {
            "engine": "orpheus",
            "repo": ORPHEUS_REPO_DE,
            "voices": ["Jakob", "Anton", "Julian", "Sophie", "Marie", "thorsten"],
        },
        "en": {"engine": "kokoro", "voices": ["af_heart", "am_michael", "bf_emma"]},
    }


@app.post("/v1/tts")
async def synthesize(req: TTSRequest):
    if not req.text or not req.text.strip():
        raise HTTPException(status_code=400, detail="text is required")

    lang = (req.lang or "de").lower()
    logger.info("TTS request — lang=%s voice=%s text=%r", lang, req.voice, req.text[:80])

    try:
        if lang.startswith("de"):
            wav = _synthesize_de(req.text, req.voice)
        elif lang.startswith("en"):
            wav = _synthesize_en(req.text, req.voice)
        else:
            raise HTTPException(status_code=400, detail=f"unsupported language: {req.lang}")
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        logger.error("synthesis failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"synthesis error: {e}")

    logger.info("TTS response — %d bytes WAV", len(wav))
    return Response(content=wav, media_type="audio/wav")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9100)
