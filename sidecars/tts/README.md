# Cortex TTS sidecar

FastAPI server behind the Cortex `tts` node. `POST /v1/tts` turns text into audio and routes by
language; which engine serves a language is a property of the **process**, not of the request:

| `lang` | `TTS_ENGINE_*` | Engine | Notes |
|---|---|---|---|
| `de` | `TTS_ENGINE_DE=orpheus` *(default)* | **Orpheus-3B / Kartoffel** | Llama-3.2 LM that emits SNAC audio tokens → decoded to a 24 kHz waveform here. Can offload the LM to vLLM/llama.cpp. |
| `de` | `TTS_ENGINE_DE=kartoffelbox` | **Kartoffelbox-v0.1** | A German Chatterbox fine-tune that clones a reference clip. **The better voice** — see [Which German engine](#which-german-engine). Needs `KARTOFFELBOX=1 ./setup.sh`. |
| `en` | `TTS_ENGINE_EN=kokoro` *(default)* | **Kokoro-82M** | Lightweight English, ONNX/CPU, Apache-2.0. |

The Java `TtsNode` (`io.metaloom.cortex.node.tts`) is a pure HTTP client of this server — it never
loads a model, and it sends the same `{text, lang, voice, format}` whichever engine is bound.

## Endpoints

| Route | What |
|---|---|
| `POST /v1/tts` | the whole recording, `audio/wav` (default) or `audio/mpeg` |
| `POST /v1/tts/stream` | the same audio, chunked, starting before the synthesis has finished |
| `GET /v1/tts/stream?text=…` | the same stream as a plain URL, so `<audio src="…">` plays it |
| `GET /v1/models` | what this process can speak with, per engine, loading nothing |
| `GET /voices` | the older per-language projection of the same data |
| `GET /health` | liveness only — use `/v1/models` for per-engine readiness |

## Setup

```bash
./setup.sh                     # venv + Kokoro weights + the test deps
KARTOFFELBOX=1 ./setup.sh      # …and the Chatterbox stack for TTS_ENGINE_DE=kartoffelbox
```

## Run

```bash
./run.sh                                             # port 9100 (the TtsNode default)
TTS_ENGINE_DE=kartoffelbox ./run.sh                  # the better German voice
ORPHEUS_BACKEND=vllm ORPHEUS_LLM_ENDPOINT=http://localhost:8000 ./run.sh   # LM on vLLM
TTS_POSTFX=podcast ./run.sh                          # with the post-production chain
```

`run.sh` and `python server.py` honour the same `TTS_HOST`/`TTS_PORT`, because Python reads them.

## Test

```bash
.venv/bin/python -m pytest tests -q    # 166 tests, every engine mocked, no GPU and no network
```

```bash
curl -s -X POST localhost:9100/v1/tts -H 'Content-Type: application/json' \
  -d '{"text":"Guten Tag, dies ist ein Test.","lang":"de","voice":"Jakob"}' -o de.wav

curl -s -X POST localhost:9100/v1/tts -H 'Content-Type: application/json' \
  -d '{"text":"Hello, this is a test.","lang":"en","voice":"af_heart","format":"mp3"}' -o en.mp3
```

Verify a WAV is real speech and not silence — a file of the right size can still be silence, which
is exactly what a SNAC layout mismatch produces.

## Which German engine

**Kartoffelbox is the one to use; Kartoffel (Orpheus) is outdated.** Decided by listening and
backed by measurement on identical text (SQUIM PESQ 3.59 vs 3.07 as a mean, and every voice
improving over the Kartoffel speaker it clones — `Sophie` 2.97 → 3.82). Kartoffel is also
band-limited to ~8 kHz where Kartoffelbox reaches 11–12 kHz, and several of its speakers are
unstable.

The code default is still `orpheus`, for two practical reasons: it is what `setup.sh` installs
without a flag, and Kartoffelbox needs reference clips. Five synthetic clips (`Jakob`, `Anton`,
`Julian`, `Sophie`, `Marie`) ship in `voices/kartoffelbox/`, rendered from the Kartoffel speakers
of the same names by `tools/make_reference_voices.py`, so `voice` works out of the box and no
recording of a real person is committed. A deployment's own clips go into
`models/kartoffelbox-voices/` and add to or replace them.

Clip quality matters more than any setting: Chatterbox reproduces a clip's **level and dynamics**
one-for-one, so a loud clip is cloned clipping and a bursty clip is cloned shouting. The server
levels and evens every clip before it is cloned, but a clean, even clip is still the better start.

## Audio processing

Every engine hands back a float waveform and the server owns everything after that. The stages are
in `tts/audio.py` and `tts/postfx.py`, each of them the one place its job is done:

| Stage | Why it exists |
|---|---|
| chunking (`tts/chunking.py`) | every engine has a duration ceiling; past it a generation returns a well-formed file cut off **mid-word**, with no error anywhere |
| edge trim | engines leave ~0.10 s before and ~0.15 s after the speech; across a chunk seam that became a 0.5 s hole, which is what "choppy" meant in the field report |
| phase dispersion | the Chatterbox vocoder ticks once per pitch period — heard as buzz or "pops", invisible to every spectrum measure, because only the phase is wrong |
| reference leveling | see above: the clone copies the clip's level and bursts |
| peak headroom | measured peaks of 1.15 on live output, which the WAV writer hard-clips and an MP3 decoder clips on playback: crackle on every loud syllable |
| post-production (`TTS_POSTFX`) | optional "radio/podcast" sound: EQ, an RMS compressor, loudness to `TTS_POSTFX_LUFS`, a look-ahead limiter |

**The rule all of them follow: a gain, never a curve.** The first versions of the headroom stage,
the compressor and the limiter all bent individual samples (a `tanh` knee, a 3 ms peak detector, a
5 ms release) and all three added harmonics to the loudest syllables of the voices they were meant
to protect — "übersteuert". Every stage now moves over tens of milliseconds, measured: THD ≤ −69 dB
in every post-production mode, against −30 dB for the chain that was replaced.

`TTS_POSTFX` is `off` by default. Verified live at −19.0 LUFS on both the file and the stream route.

## Environment variables

`tts/config.py` is the authoritative reference; these are the ones a deployment usually sets.

| Var | Default | Meaning |
|---|---|---|
| `TTS_HOST` / `TTS_PORT` | `0.0.0.0` / `9100` | bind address, read by Python |
| `TTS_ENGINE_DE` | `orpheus` | `orpheus` or `kartoffelbox` |
| `TTS_ENGINE_EN` | `kokoro` | — |
| `TTS_DEVICE` | `auto` | torch device; `DEVICE` is still honoured as a fallback |
| `ORPHEUS_BACKEND` | `transformers` | `transformers` (in-process) or `vllm` (proxy the LM) |
| `ORPHEUS_LLM_ENDPOINT` | `http://localhost:8000` | vLLM/llama.cpp base URL for the `vllm` backend |
| `ORPHEUS_REPO_DE` | `SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1` | gated; `Thorsten-Voice/tv-orpheus-v1` is the ungated Apache-2.0 escape hatch (single speaker) |
| `SNAC_REPO` | `hubertsiuzdak/snac_24khz` | SNAC decoder |
| `KARTOFFELBOX_REPO` | `SebastianBodza/Kartoffelbox-v0.1` | gated |
| `KARTOFFELBOX_VOICES_DIR` | `models/kartoffelbox-voices` | a deployment's own reference clips |
| `KARTOFFELBOX_WATERMARK` | `1` | Chatterbox watermarks its output; `0` installs a dummy watermarker |
| `KOKORO_MODEL` / `KOKORO_VOICES` | `models/kokoro-v1.0.onnx` / `models/voices-v1.0.bin` | Kokoro weights |
| `TTS_POSTFX` | `off` | `off`, `level`, `clean`, `podcast`, `broadcast` |
| `TTS_CHUNK_CHARS` | `500` | Orpheus text budget per generation |
| `TTS_STREAM_CHUNK_CHARS` | `200` | streaming budget — the time-to-first-audio when not incremental |

A relative `KOKORO_*` or `KARTOFFELBOX_*` path resolves against **this directory**, not the
working directory, so starting uvicorn from elsewhere no longer finds nothing.

## Adding another model

The engines are a registry, so a new model is three small things and nothing else:

1. `tts/engines/<name>.py` with an `Engine` subclass: declare `languages`, `chunk_chars`,
   `max_seconds` and the licence, implement `_generate`, and override `finish` if it needs a
   per-generation audio stage. Import its dependencies **inside** the module, never at package
   level.
2. its name in `_NAMES` and one branch in `_create` (`tts/engines/__init__.py`).
3. optionally a `TTS_ENGINE_<LANG>` entry in `config.ENGINE_BY_LANG`.

The routes, the request contract, the chunk loop, the gap welding, the containers, the
post-production chain and both catalogue routes are shared and do not change.

## Voices

- German, Orpheus: `Jakob`, `Anton`, `Julian`, `Sophie`, `Marie` (or `thorsten` for the ungated
  single-speaker checkpoint) — derived from the configured checkpoint, not a static list.
- German, Kartoffelbox: whichever reference clips are installed.
- English, Kokoro: 54 voices, read out of `voices-v1.0.bin` itself.

`GET /voices` and `GET /v1/models` report exactly what the running process would use.
