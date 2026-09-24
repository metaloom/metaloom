# ASR Sidecar (:9140)

Time-coded speech recognition, German first, as a container: `sidecars/asr/`. Three backends
behind one API: **Whisper** large-v3-turbo (faster-whisper, GPU), **Parakeet**-TDT-0.6B-v3
(sherpa-onnx, CPU) and **Voxtral** Mini 4B Realtime (transformers, GPU, true streaming). Every
result carries word and segment time codes, absolute to the audio the caller sent.

**Related:** [SIDECARS.md](SIDECARS.md) (index, cross-cutting status) ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) (the in-process `whisper` node this could
back) · operator documentation: `sidecars/asr/README.md` (API examples, measured numbers).

**Scope of this file:** the sidecar's contract, internals and tests. It does not cover the
in-process `whisper` Cortex node (whisper.cpp through asr4j), which does not call this sidecar.

## Status

| Aspect | State |
|---|---|
| Container | Built and run on this checkout: `Dockerfile` (CUDA 12.8 cuDNN runtime, 12.7 GB), `container.sh` build/run/test/logs/stop, docker or podman, CDI or `--gpus` |
| Bare-metal | `setup.sh` + `run.sh` (venv), like the other Python sidecars |
| Python tests | 36 unit/contract tests (no model) + 20 live tests (real models, German fixture). All green on an RTX 4090 |
| Live bring-up observed | Yes: all three backends, HTTP and realtime, over the container's real port |
| Calling node | None yet. The `whisper` node still runs whisper.cpp in-process |
| Helm / compose | None, like every other sidecar |
| Auth | None. Binds `0.0.0.0` |

## Architecture

```
 client (node, browser, curl)
   |  POST /v1/audio/transcriptions (multipart)        WS /v1/realtime?model=... (PCM16 16 kHz)
   v                                                    v
 server.py  transcribe_file()                       _Connection -> RealtimeSession (asr/session.py)
   |   decode (soundfile | ffmpeg)                      |  session-absolute audio buffer
   |   endpointing.split() -> spans                     |  Endpointer.feed() -> utterances (server_vad)
   |   per span: backend.transcribe()                   |  or commit-only (turn_detection: null)
   |   timecodes.offset(span start), clamp              |  worker thread, one utterance at a time:
   v                                                    |    batch backend: partials, then decode
 timecodes.render(verbose_json|loom|srt|vtt|...)        |    voxtral: backend.stream(take, ...)
                                                        v  timecodes.offset(utterance start)
                                                     transcription.delta/partial/segment/done
 backends/ (asr/backends/)
   whisper.py   faster-whisper, word_timestamps=True (DTW on cross-attention)
   parakeet.py  sherpa-onnx transducer: token frame + TDT duration, snap_to_speech()
   voxtral.py   transformers streaming generate: 1 token / 80 ms; TokenTimer -> word ends
```

The one invariant: **a backend answers in utterance-relative time, and `timecodes.offset` is the
only place that becomes absolute.** The HTTP route and the realtime session each apply it once, per
span or per utterance.

## Key Classes Reference

| Name | File | Purpose |
|---|---|---|
| `Word`, `Segment`, `Transcript` | `asr/timecodes.py` | The transcript model every backend returns |
| `WordBuilder`, `SegmentBuilder` | `asr/timecodes.py` | Incremental token->word and word->segment grouping; the batch helpers use the same code |
| `offset`, `clamp`, `merge`, `render` | `asr/timecodes.py` | Absolute times, bounds, concatenation, output formats |
| `Endpointer`, `split` | `asr/endpointing.py` | Energy-based utterance spans in session-absolute samples; same cuts for files and streams |
| `Backend` | `asr/backends/base.py` | Lazy load, per-backend lock, `transcribe(audio, language)` |
| `WhisperBackend` | `asr/backends/whisper.py` | faster-whisper, segment + word timestamps |
| `ParakeetBackend`, `snap_to_speech` | `asr/backends/parakeet.py` | sherpa-onnx; word boundaries pulled in to the waveform |
| `VoxtralBackend`, `TokenTimer` | `asr/backends/voxtral.py` | Offline and streaming decode; frame index -> word end, estimated starts, pause clock |
| `RealtimeSession`, `Utterance` | `asr/session.py` | One WebSocket client: buffer, utterances, worker, events |
| `_Connection`, `transcribe_file` | `server.py` | WebSocket glue (slots, session.update); the HTTP route |
| `backends.get/resolve/register` | `asr/backends/__init__.py` | Model name and alias resolution; tests register fakes |

## HTTP and realtime contract

`POST /v1/audio/transcriptions`: OpenAI multipart form (`file`, `model`, `language`,
`response_format`, and `timestamp_granularities[]`/`prompt`/`temperature`, which are accepted and
ignored). The default `response_format` is **`verbose_json`**, not OpenAI's `json`. Formats:
`verbose_json` (segments with `start/end/start_ms/end_ms/words[]`, plus flattened `words`), `json`,
`text`, `loom`, `srt`, `vtt`. `loom` is byte-compatible with
`io.metaloom.cortex.media.whisper.WhisperResult#fromJson`: `{"segments": [{"text", "from", "to"}]}`
in integer ms. Errors: 400 for an unknown model, a bad format or an undecodable upload; 413 above
`ASR_MAX_UPLOAD_MB`. Response headers `X-Model-Id` and `X-Backend`.

`GET /health`: backends with `loaded` and device info. `GET /v1/models`: an OpenAI-style list.

`WS /v1/realtime?model=`: events `session.created/updated`,
`input_audio_buffer.speech_started/speech_stopped/committed`, `transcription.delta` (Voxtral),
`transcription.partial` (Whisper/Parakeet), `transcription.segment` (final, session-wide ids),
`transcription.done` (one per utterance; exactly one per commit), `error`. The full table and a real
trace are in `sidecars/asr/README.md`.

## Time codes: where they come from

| Backend | Source | Measured vs Whisper on the fixture (word end median / p90 / max) |
|---|---|---|
| whisper | timestamp tokens (segments) + DTW on the alignment heads' cross-attention (words) | reference |
| voxtral | the first text token of a word at generated index k => the word ends at (k-1) x 80 ms; starts estimated | 0.06 / 0.10 / 0.20 s |
| parakeet | token emission frame + TDT duration, then `snap_to_speech` | 0.08 / 0.20 / 0.28 s |

Voxtral's rule was found empirically. The model emits `[STREAMING_PAD]`, `[STREAMING_WORD]` or a
text token for every 80 ms frame, and the prompt is BOS + 38 pad tokens (32 left pad + 6 delay).
`tests/test_voxtral_timer.py` replays a real token stream against Whisper's ends.

## Environment Variables

All are read by `asr/config.py` at import time, so a change needs a restart.

| Variable | Default | Meaning |
|---|---|---|
| `ASR_HOST` / `ASR_PORT` | `0.0.0.0` / `9140` | bind address. Read by Python, not only `run.sh` |
| `ASR_BACKENDS` | `whisper,parakeet,voxtral` | enabled backends |
| `ASR_DEFAULT_MODEL` | `whisper` | backend when `model` is absent |
| `ASR_LANGUAGE` | `de` | Whisper decode language (`auto` detects) |
| `ASR_PRELOAD` | empty | backends to load at startup |
| `ASR_WHISPER_MODEL` / `_DEVICE` / `_COMPUTE_TYPE` / `_BEAM_SIZE` | `large-v3-turbo` / `auto` / fp16 or int8 / `5` | |
| `ASR_PARAKEET_MODEL` / `_THREADS` / `_PROVIDER` | sherpa-onnx v3 export / `4` / `cpu` | |
| `ASR_VOXTRAL_MODEL` / `_DEVICE` / `_DTYPE` | Voxtral-Mini-4B-Realtime-2602 / `cuda` / `bfloat16` | |
| `ASR_VOXTRAL_MAX_STREAMS` | `1` | concurrent Voxtral realtime sessions |
| `ASR_VOXTRAL_TIME_SHIFT_MS` | `0` | constant added to every Voxtral time (recalibration) |
| `ASR_MAX_SESSIONS` | `8` | concurrent realtime sessions |
| `ASR_TURN_DETECTION` | `server_vad` | session default; `none` = commits only |
| `ASR_SILENCE_MS` / `ASR_MAX_UTTERANCE_S` | `600` / `20` | endpointer |
| `ASR_PARTIAL_INTERVAL_MS` | `1000` | interim decodes for batch backends; `0` = off |
| `ASR_MAX_UPLOAD_MB` | `512` | |
| `ASR_LOG_LEVEL` | `INFO` | |

`container.sh` also reads `ASR_IMAGE`, `ASR_NAME`, `ASR_GPU`, `ASR_HF_CACHE`, `ASR_RUNTIME`,
`ASR_TEST_MODELS` and `ASR_TEST_PACE`, and forwards every other `ASR_*` into the container.

## Conventions and Gotchas

* **Never add an offset inside a backend.** Backends return utterance-relative times.
  `test_server.py`'s fakes model exactly that; a second offset shows up there as a shifted window.
* **Voxtral word ends are measured; its starts are estimated** (previous end, or 60 ms per letter
  before the end after a pause). Key media positions on ends and segment boundaries. The start of
  the first word after a pause is the least precise value for *every* backend: Whisper put the
  fixture's "Der" 1 s early.
* **Voxtral's pause clock is `TokenTimer.clock`**, which is `time_of(k) - MAX_WORD_S`, or None while a
  word is open. A word's first token arrives when the word *ends*, so measuring a pause from
  `time_of(k)` split sentences in the middle of long words. `test_a_long_word_arriving_is_not_a_pause`
  guards this.
* **Parakeet's TDT durations include the blank frames after a token.** Without `snap_to_speech`,
  sentence-final words ended 0.4 - 0.6 s into the silence. The snap uses a threshold relative to the
  word's own peak (-20 dB) as well as the noise floor (+10 dB). The floor alone fails when digital
  silence drags the percentile down.
* **Punctuation tokens never extend a word's end** (`WordBuilder`). Transducers emit the "." after
  the speaker stops.
* **Parakeet weights go to `$HF_HOME/asr-sidecar/<repo>` via `snapshot_download(local_dir=...)`**,
  not the plain cache. onnxruntime rejects the encoder's external-data file behind the cache's
  symlinked blobs ("External data path escapes model directory").
* **ffmpeg reads uploads from a temp file, not stdin.** An mp4 keeps its `moov` index at the end,
  and a pipe cannot seek to it. `test_video_upload_goes_through_ffmpeg` guards this.
* **asyncio semaphores are created in the lifespan**, not at import. They bind to the first loop,
  and every `TestClient` has its own.
* **One `transcription.done` per commit, plus one per server-VAD utterance.** Clients that pop a
  pending commit per `done` (speck's and webrtc-poc's `RealtimeSpeechToText`) must use
  `turn_detection: null` / `ASR_TURN_DETECTION=none`.
* **Voxtral is slow offline** (about 4x real time for files) and one stream holds the model for as
  long as its speaker talks. HTTP `model=voxtral` requests wait behind a live stream.
* **From inside a dev container**, docker's bind mounts resolve on the host: pass the host path in
  `ASR_HF_CACHE` (on the dev box: `/extra/.cache/huggingface`, workspace under
  `/home/jotschi/workspaces`).

## Test Setup

```bash
cd sidecars/asr
./container.sh build && ./container.sh run     # ASR_GPU=0, ASR_HF_CACHE=<host path> as needed
./container.sh test                            # unit tests, then live tests in the container
```

* Unit/contract tests: `tests/test_timecodes.py`, `test_endpointing.py`, `test_voxtral_timer.py`,
  `test_server.py`. There is no model and no GPU. They run in the image or in the venv
  (`python -m pytest tests -q --ignore=tests/test_live.py`). The mp4 test skips without ffmpeg.
* Live: `tests/test_live.py`, skipped unless `ASR_TEST_URL` is set. Fixture
  `tests/data/de_timeline.flac` (+ `.json`) is three German clips at known sample positions with
  2.5 s gaps, built by `tests/make_fixture.py` from audio-eval's FLEURS / Common Voice sets. It
  asserts clip WER <= 20 %, total <= 10 %; every word inside its clip window (+/-0.35 s); word ends
  within median 0.20 / p90 0.45 / max 0.50 s of Whisper; realtime equal to HTTP; final segments
  <= 3 s after speech; Voxtral text before the clip ends and a median delta lag <= 1.5 s. It runs
  in about 100 s at `ASR_TEST_PACE=1.0`, cold loads included when the weights are cached.
* German WER on the full audio-eval sets (100 FLEURS DE + 100 CV DE) is not part of the suite. It
  was measured once: whisper 3.41 / 4.72 %, parakeet 4.41 / 6.12 %, voxtral 6.47 / 8.16 %.

## Where do I find ...?

| I want ... | Look at |
|---|---|
| The API with real examples | `sidecars/asr/README.md` |
| How a backend's times are made | `sidecars/asr/asr/backends/{whisper,parakeet,voxtral}.py` module docstrings |
| Output formats, the Loom format | `sidecars/asr/asr/timecodes.py` (`render`, `to_loom`) |
| Utterance cutting | `sidecars/asr/asr/endpointing.py` |
| Realtime event flow | `sidecars/asr/asr/session.py`; protocol table at the top of `server.py` |
| The container | `sidecars/asr/Dockerfile`, `sidecars/asr/container.sh` |
| The German fixture | `sidecars/asr/tests/data/`, `sidecars/asr/tests/make_fixture.py` |
| The Java type the `loom` format targets | `cortex/core-media/src/main/java/io/metaloom/cortex/media/whisper/WhisperResult.java` |
| The older ASR servers this replaces for time codes | `webrtc-poc/asr-{voxtral,whisper}-server`, `speck/sidecars/asr-*`, `asr4j/voxtral` (vLLM, no time codes) |

## Progress Assessment

- [x] Container image with Whisper, Parakeet and Voxtral; weights from a mounted HF cache
- [x] Word and segment time codes from every backend, absolute to the input; `verbose_json`, `loom`, `srt`, `vtt`
- [x] Realtime WebSocket: server VAD or commit-only; deltas (Voxtral), partials (batch), final segments
- [x] Video uploads through ffmpeg
- [x] Unit/contract tests with fakes; live tests on real German speech with known positions
- [x] Measured German WER for all three backends on audio-eval's sets
- [ ] A Cortex node (or a `whisper` node mode) that calls this sidecar and stores the `loom` result
- [ ] Customer-facing docs and a model-licenses entry (Parakeet is CC-BY-4.0: attribution) once a node uses it
- [ ] Run speck's / webrtc-poc's `RealtimeSpeechToText` against it (needs `turn_detection: null`)
- [ ] Neural VAD option for noisy rooms (the energy endpointer falls back to `ASR_MAX_UTTERANCE_S` cuts)
- [ ] Authentication, as for every sidecar
- [ ] Re-check vLLM: when #50783 (Voxtral realtime segments) or #47664 (Whisper words) merge, compare with this sidecar's times

---

_Git HEAD revision: `52631fca`_
_Last updated: 2026-09-24 (new: ASR sidecar with Whisper, Parakeet and Voxtral, time-coded, containerised, live-tested)_
