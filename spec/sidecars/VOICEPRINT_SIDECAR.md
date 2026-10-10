# Voiceprint Sidecar (:9150)

Speaker embeddings and who-spoke-when as a container: `sidecars/voiceprint/`. Three backends behind
one API — **ReDimNet2-B6** (default, MIT), **WeSpeaker** ResNet34 ONNX (CC-BY-4.0), **SpeechBrain**
ECAPA (Apache-2.0). Every result is a set of L2-normalised window vectors, speakers with a timeline,
and a pooled vector, all timed in integer ms from the start of the upload.

**Related:** [SIDECARS.md](SIDECARS.md) (index) ·
[../features/nodes/voiceprint/NODE_VOICEPRINT.md](../features/nodes/voiceprint/NODE_VOICEPRINT.md)
(the calling node, model choice, persistence) · operator docs `sidecars/voiceprint/README.md` (curl
examples, full response sample).

**Scope of this file:** the sidecar's contract, internals, configuration and tests. The node side is in
NODE_VOICEPRINT.md.

## Status

| Aspect | State |
|---|---|
| Container | `Dockerfile` (CUDA 12.8 cuDNN runtime, 12.6 GB) + `container.sh` build/run/test/logs/stop; docker or podman; CDI preferred, detected from `<runtime> info` |
| Bare metal | `setup.sh` (uv venv, py3.12, torch cu128) + `run.sh` |
| Python tests | 25 unit/contract (fake backend, < 1 s) + 7 live (real models) — all green in the container on an RTX 4090 |
| Live bring-up | Yes, over the container's real port; all three backends on CUDA |
| Calling node | `VoiceprintNode` (`voiceprint`) |
| Helm / compose / auth | None, like every sidecar |

## Architecture

```
 POST /v1/voiceprints (multipart file + fields)
   |
 audio.decode()            soundfile, else ffmpeg -> 16 kHz mono f32
 vad.speech_spans()        energy endpointer (noise floor + margin), sample spans
 vad.windows()             fixed window_s / hop_s; short span tiled, < min_speech_s dropped; MAX_WINDOWS cap
 backend.embed()           batched, equal-length only, L2-normalised, lock per backend
 cluster.speakers()        average-linkage on cosine, cut at 1 - threshold, longest speaker first
 cluster.pooled()          mean of all windows, normalised
   v
 JSON {model, backend, dim, threshold, duration_ms, speech_ms, speakers[], windows[], pooled}
```

Invariant: **a window always holds exactly `window_s` of audio.** Padding changes an embedding; the eval
harness this came from asserts batch == single only for equal lengths. A short span is tiled.

## Key Classes Reference

| Name | File | Purpose |
|---|---|---|
| `Backend` | `voiceprint/backends/base.py` | lazy load (+1 s probe for `dim`), lock, normalise, finite check, `default_threshold`, licence info |
| `ReDimNet2Backend` | `voiceprint/backends/redimnet2.py` | vendored `third_party/redimnet2` (MIT, commit `c5bbe0b`), weights from the GitHub release, `weights_only=True` |
| `WeSpeakerBackend` | `voiceprint/backends/wespeaker.py` | onnxruntime (CUDA EP), Kaldi fbank 80 on int16 scale + CMN |
| `SpeechBrainBackend` | `voiceprint/backends/speechbrain.py` | `EncoderClassifier.encode_batch` |
| `get` / `resolve` / `register` | `voiceprint/backends/__init__.py` | short names, full model ids and repo-id aliases; tests register fakes |
| `speech_spans`, `windows` | `voiceprint/vad.py` | speech finding and windowing |
| `speakers`, `pooled`, `cosine` | `voiceprint/cluster.py` | clustering and timeline |
| `analyse` | `server.py` | the whole job without HTTP |

## HTTP contract

| Route | In | Out |
|---|---|---|
| `POST /v1/voiceprints` | multipart `file`; optional `model`, `window_s`, `hop_s`, `min_speech_s`, `cluster_threshold`, `max_speakers`, `include_windows`, `include_vectors` | 200 JSON as above (headers `X-Model-Id`, `X-Backend`); 400 unknown model / undecodable / bad parameters; 413 above `VOICEPRINT_MAX_UPLOAD_MB` |
| `POST /v1/compare` | JSON `{a: [...], b: [...]}` | `{score, dim}`; 400 on length mismatch |
| `GET /health` | | backends with `loaded`, `device`, `dim`, `default_threshold`, `licence`, `commercial` |
| `GET /v1/models` | | OpenAI-style list |

Silence answers 200 with empty `speakers`/`windows`, no `pooled`, `speech_ms: 0`.

## Environment variables

All `VOICEPRINT_`-prefixed and read by `voiceprint/config.py` itself (none of the `DEVICE` / run.sh-only
`*_PORT` hazards of the older sidecars).

| Variable | Default | Meaning |
|---|---|---|
| `VOICEPRINT_HOST` / `_PORT` | `0.0.0.0` / `9150` | listener |
| `VOICEPRINT_BACKENDS` | `redimnet2,wespeaker,speechbrain` | offered backends |
| `VOICEPRINT_DEFAULT_MODEL` | `redimnet2` | backend without `model` |
| `VOICEPRINT_PRELOAD` | `` | load at startup |
| `VOICEPRINT_DEVICE` | `auto` | `cuda` / `cpu` / auto |
| `VOICEPRINT_REDIMNET2_SIZE` / `_DATASET` / `_TRAIN` | `b6` / `vox2` / `lm` | checkpoint; `vb2+…` datasets are non-commercial |
| `VOICEPRINT_REDIMNET2_WEIGHTS_URL` | GitHub release template | mirror |
| `VOICEPRINT_WESPEAKER_REPO` / `_ONNX` | `Wespeaker/wespeaker-voxceleb-resnet34-LM` / first `*.onnx` | WeSpeaker export |
| `VOICEPRINT_SPEECHBRAIN_REPO` | `speechbrain/spkrec-ecapa-voxceleb` | |
| `VOICEPRINT_WINDOW_S` / `_HOP_S` / `_MIN_SPEECH_S` | 3.0 / 1.5 / 1.0 | defaults |
| `VOICEPRINT_MAX_WINDOWS` | 4000 | cap per request |
| `VOICEPRINT_CLUSTER_THRESHOLD` | backend default | deployment override |
| `VOICEPRINT_BATCH` / `_TORCH_THREADS` | 32 / 4 | |
| `VOICEPRINT_MAX_UPLOAD_MB` | 4096 | |
| `VOICEPRINT_LOG_LEVEL` | `INFO` | |

`container.sh` also reads `VOICEPRINT_IMAGE`, `_NAME`, `_GPU`, `_HF_CACHE`, `_TORCH_CACHE`, `_RUNTIME`.

## Measured (RTX 4090, container, 37.5 s two-voice fixture, warm)

| Backend | dim | Windows right | Same-voice cos (mean) | Cross-voice cos (mean) | Warm request |
|---|---|---|---|---|---|
| `redimnet2` | 192 | 13/13 | 0.75 | 0.04 | 0.10 s |
| `wespeaker` | 256 | 13/13 | 0.74 | 0.03 | 0.32 s |
| `speechbrain` | 192 | 13/13 | 0.66 | 0.07 | 0.03 s |

The thresholds 0.40 / 0.40 / 0.35 sit in the gap on this fixture and in the range the eval's German
sets support. A synthetic two-voice fixture is easy; in-domain audio (phone-ins, crowds) is not.

## Test Setup

```bash
cd sidecars/voiceprint
.venv/bin/python -m pytest tests -q --ignore=tests/test_live.py                     # no model
./container.sh build && ./container.sh run && ./container.sh test                    # unit + live in the container
VOICEPRINT_TEST_URL=http://localhost:9150 .venv/bin/python -m pytest tests/test_live.py -v -s
```

`tests/make_fixture.py` rebuilds the fixture through the TTS sidecar (:9100, Kokoro `af_heart` /
`am_michael`).

## Conventions and Gotchas

- **`--workers 1`**: models are module globals.
- **No `/etc/cdi` visible does not mean no CDI.** With a namespaced or remote daemon the spec dirs are
  not visible to the shell; `container.sh` asks `<runtime> info` for `nvidia.com/gpu=`. Legacy
  `--gpus` refuses images whose CUDA requirement exceeds the driver on some hosts.
- **Vendored ReDimNet2** is imported as top-level `redimnet2` because its modules import each other
  that way; `third_party/redimnet2` is put on `sys.path` at load.
- **Weights from mirrors must load `weights_only=True`.** The ReDimNet2 release files are plain dicts.
- **`0` form values must reach validation.** `window_s=0` is a 400, not "use the default" — the
  route uses explicit `None` checks.
- **Thresholds are per backend.** Changing `VOICEPRINT_WESPEAKER_REPO` to ResNet293 or CAM++ keeps
  the ResNet34 threshold; re-check it.
- **Weights download on first use** into `HF_HOME` and `TORCH_HOME/hub/checkpoints`; mount both.

## Where do I find …?

| What | Where |
|---|---|
| Routes | `sidecars/voiceprint/server.py` |
| Add a backend | `voiceprint/backends/<name>.py` + `_create` in `backends/__init__.py` + `VOICEPRINT_BACKENDS` |
| Fixture | `tests/data/two_speakers.{wav,json}` |
| Container | `Dockerfile`, `container.sh` |

## Progress Assessment

- [x] Three backends, one contract, container with GPU, unit + live tests
- [x] Called by the `voiceprint` node
- [ ] AS-norm with a cohort, score calibration per source
- [ ] Overlap-aware segmentation (a neural VAD / pyannote segmentation)
- [ ] Not in Helm or compose; no auth

---

_Git HEAD revision: `3da90863`_
_Last updated: 2026-10-10 (created)_
