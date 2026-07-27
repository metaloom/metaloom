# Cortex TTS sidecar

FastAPI server behind the Cortex `tts` node. One endpoint, `POST /v1/tts`, routes
by language:

| `lang` | Engine | Notes |
|---|---|---|
| `de` | **Orpheus-3B / Kartoffel** | Best-in-class German (see `audio-eval/TTS.md`). Emits SNAC tokens → decoded to 24 kHz WAV here. |
| `en` | **Kokoro-82M** | Lightweight English, ONNX/CPU. |

The Java `TtsNode` (`io.metaloom.cortex.node.tts`) is a pure HTTP client of this
server — it never loads a model. Keeping the model in the sidecar is what lets the
node stay Java-only, matching the `CaptioningNode` → SmolVLM pattern.

## Why a sidecar and not vLLM directly

Orpheus is a Llama-3.2 LM that emits **SNAC audio tokens**, not audio. vLLM/llama.cpp
can run the LM, but something must still de-interleave the 7-token SNAC frames and
run `SNAC.decode()` to a waveform. That decode lives here. Two backends:

- `ORPHEUS_BACKEND=transformers` (default) — load the checkpoint in-process. Self-contained.
- `ORPHEUS_BACKEND=vllm` — proxy LM token generation to a vLLM / llama.cpp
  OpenAI-compatible endpoint (`ORPHEUS_LLM_ENDPOINT`) and only decode SNAC here.
  This is the production path.

## Setup

```bash
./setup.sh        # venv + Kokoro weights
```

## Run

```bash
./run.sh                      # port 9100 (the TtsNode default)
# production German path:
ORPHEUS_BACKEND=vllm ORPHEUS_LLM_ENDPOINT=http://localhost:8000 ./run.sh
```

## Test

```bash
curl -s -X POST localhost:9100/v1/tts \
  -H 'Content-Type: application/json' \
  -d '{"text":"Guten Tag, dies ist ein Test.","lang":"de","voice":"Jakob"}' -o de.wav

curl -s -X POST localhost:9100/v1/tts \
  -H 'Content-Type: application/json' \
  -d '{"text":"Hello, this is a test.","lang":"en","voice":"af_heart"}' -o en.wav
```

Verify the WAVs are real speech (not silence) with `audio-eval/tools/check_outputs.py`.

## Environment variables

| Var | Default | Meaning |
|---|---|---|
| `ORPHEUS_BACKEND` | `transformers` | `transformers` (in-process) or `vllm` (proxy LM to `ORPHEUS_LLM_ENDPOINT`) |
| `ORPHEUS_LLM_ENDPOINT` | `http://localhost:8000` | vLLM/llama.cpp base URL for the `vllm` backend |
| `ORPHEUS_REPO_DE` | `SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1` | German checkpoint. Gated — set to `Thorsten-Voice/tv-orpheus-v1` (ungated, Apache-2.0) to avoid the HF gate. |
| `SNAC_REPO` | `hubertsiuzdak/snac_24khz` | SNAC decoder |
| `KOKORO_MODEL` / `KOKORO_VOICES` | `models/kokoro-v1.0.onnx` / `models/voices-v1.0.bin` | Kokoro weights |
| `DEVICE` | `cuda` if available else `cpu` | torch device |

## Voices

- German (Orpheus/Kartoffel): `Jakob`, `Anton`, `Julian`, `Sophie`, `Marie`, … (`thorsten` for the ungated single-speaker checkpoint).
- English (Kokoro): `af_heart`, `am_michael`, `bf_emma`, …

`GET /voices` lists what the running configuration exposes.
