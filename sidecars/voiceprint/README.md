# Voiceprint sidecar

Speaker embeddings ("voiceprints") and who-spoke-when for Metaloom, as a container: **ReDimNet2**,
**WeSpeaker** and **SpeechBrain ECAPA** behind one HTTP API on port **9150**. Upload a recording and
get back every speech window as a unit vector, the windows grouped into speakers with a timeline,
and one pooled voiceprint for the whole file. The Cortex `voiceprint` node is a thin client of it.

| Backend | Model | Params | Licence | Commercial | Composite EER (DE/AT) | CPU ms / 10 s |
|---|---|---|---|---|---|---|
| `redimnet2` *(default)* | ReDimNet2-B6 LM, VoxCeleb2 ([PalabraAI/redimnet2](https://github.com/PalabraAI/redimnet2)) | 12.4M | MIT | yes | **0.43 %** | 870 |
| `wespeaker` | WeSpeaker ResNet34-LM ONNX ([Wespeaker/wespeaker-voxceleb-resnet34-LM](https://huggingface.co/Wespeaker/wespeaker-voxceleb-resnet34-LM)) - the pyannote 3.1 embedding | 6.6M | CC-BY-4.0 | yes, with attribution | 0.74 % | 292 |
| `speechbrain` | ECAPA-TDNN ([speechbrain/spkrec-ecapa-voxceleb](https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb)) | 22M | Apache-2.0 | yes | 1.28 % | 197 |

The numbers come from the `voiceprint-eval` comparison of 22 speaker-recognition approaches on
Austrian and German speakers (VoxPopuli MEPs across sessions, Common Voice, five degradations;
EER in %, cosine scoring). ReDimNet2-B6 on VoxCeleb2 is the best model whose training data allows
commercial use; the VoxBlink2-trained variants are better still (0.29 %, 0.26 %) but VoxBlink2 and
CN-Celeb forbid it - they are selectable through `VOICEPRINT_REDIMNET2_DATASET` for research use.
W2V-BERT 2.0 ties ReDimNet2 at 0.43 % with 587M parameters and 3x the CPU time, so it is not here.

Written for: Metaloom developers and operators who deploy the sidecar or call it from a node.

## Quick start

```bash
./container.sh build
./container.sh run            # GPU "all", caches from ~/.cache/{huggingface,torch}, port 9150
./container.sh test           # unit tests + live tests inside the running container
```

Weights are not in the image. They download on first use into the mounted caches: ReDimNet2
50 MB (torch hub, from the GitHub release), the WeSpeaker ONNX export 25 MB and SpeechBrain ECAPA
80 MB (Hugging Face). `VOICEPRINT_PRELOAD=redimnet2` loads at startup instead.

```bash
curl -F file=@interview.wav localhost:9150/v1/voiceprints | jq '.speakers[] | {index, total_ms, segments}'
curl -F file=@interview.mp4 -F model=wespeaker -F include_vectors=false localhost:9150/v1/voiceprints
```

WAV, FLAC, OGG and MP3 are decoded directly. Anything else, including video containers, is decoded
with the ffmpeg in the image. (The Cortex node demuxes video to a 16 kHz WAV itself before
uploading, so a worker never ships a 4 GB video to the sidecar.)

Without a container: `./setup.sh` (venv, torch for CUDA 12.8 by default) then `./run.sh`.

## What it does

```
upload -> decode to 16 kHz mono -> speech spans (energy endpointer) -> fixed windows (3 s, hop 1.5 s)
       -> one L2-normalised embedding per window (batched)
       -> agglomerative clustering on cosine, cut at the backend's threshold -> speakers, longest first
       -> per speaker: segments (merged windows), total time, cohesion, mean vector
       -> pooled vector over every window
```

Every window has exactly `window_s` of audio: padding changes an embedding, so a span shorter than
the window (but at least `min_speech_s`) is **tiled** to fill it, and the models are batched over
equal-length windows only. Spans under `min_speech_s` are dropped.

Clustering happens here, not in the node, because the right cosine threshold is a property of the
model: the three backends ship 0.40 / 0.40 / 0.35, set on the two-voice fixture in `tests/` (same
voice cosine 0.75 / 0.74 / 0.66 mean, different voices 0.04 / 0.03 / 0.07) and the eval's German
sets. Override per request with `cluster_threshold`, or per deployment with
`VOICEPRINT_CLUSTER_THRESHOLD`.

## HTTP

### `POST /v1/voiceprints`

Multipart form: `file` (required), `model` (`redimnet2` | `wespeaker` | `speechbrain`, or the full
model id `/v1/models` prints), `window_s`, `hop_s`, `min_speech_s`, `cluster_threshold` (cosine
similarity in [-1, 1]), `max_speakers` (0 = unlimited), `include_windows` (default true),
`include_vectors` (default true). Every time is an integer millisecond offset from the start of
the file.

```json
{
  "model": "redimnet2-b6-vox2-lm", "backend": "redimnet2", "dim": 192, "normalized": true,
  "threshold": 0.4, "duration_ms": 37530, "speech_ms": 30290, "window_s": 3.0, "hop_s": 1.5,
  "compute_ms": 1657,
  "pooled":   { "vector": [0.012, -0.08, ...] },
  "speakers": [
    { "index": 0, "total_ms": 14010, "cohesion": 0.88, "windows": [0, 1, 2, 5, 6, 11, 12],
      "segments": [ { "start_ms": 2420, "end_ms": 7440 }, { "start_ms": 15470, "end_ms": 19950 } ],
      "vector": [ ... ] },
    { "index": 1, "total_ms": 12020, "cohesion": 0.90, "windows": [3, 4, 7, 8, 9, 10],
      "segments": [ { "start_ms": 8660, "end_ms": 14220 }, { "start_ms": 22430, "end_ms": 29250 } ],
      "vector": [ ... ] }
  ],
  "windows": [
    { "index": 0, "start_ms": 2420, "end_ms": 5420, "speaker": 0, "vector": [ ... ] },
    ...
  ]
}
```

Speakers are numbered by speaking time, longest first. `cohesion` is the mean cosine of a
speaker's windows to its vector (1.0 for a single window; a low value means the cluster probably
holds two voices). A recording without speech answers 200 with empty `speakers`/`windows`, no
`pooled`, and `speech_ms: 0`. Errors: 400 for an unknown model, an undecodable upload or
inconsistent window parameters; 413 above `VOICEPRINT_MAX_UPLOAD_MB`. Response headers `X-Model-Id`
and `X-Backend`.

### `POST /v1/compare`

JSON `{"a": [...], "b": [...]}` -> `{"score": <cosine similarity>, "dim": n}`. For verifying two
voiceprints the caller already holds (a stored speaker vector against a new recording's). It does
no audio work.

### `GET /health`, `GET /v1/models`

`/health` lists the enabled backends with `loaded`, `device`, `dim`, `default_threshold`,
`licence` and `commercial`. `/v1/models` is an OpenAI-style list.

## Configuration

All variables are `VOICEPRINT_`-prefixed and read by `server.py` itself, so `run.sh`, the container
and `python server.py` agree.

| Variable | Default | Meaning |
|---|---|---|
| `VOICEPRINT_HOST` / `VOICEPRINT_PORT` | `0.0.0.0` / `9150` | Listener |
| `VOICEPRINT_BACKENDS` | `redimnet2,wespeaker,speechbrain` | Backends this process offers |
| `VOICEPRINT_DEFAULT_MODEL` | `redimnet2` | Backend for a request without `model` |
| `VOICEPRINT_PRELOAD` | *(empty)* | Backends to load at startup instead of on first use |
| `VOICEPRINT_DEVICE` | `auto` | `cuda`, `cpu`, or `auto` (cuda when torch sees a GPU) |
| `VOICEPRINT_REDIMNET2_SIZE` / `_DATASET` / `_TRAIN` | `b6` / `vox2` / `lm` | ReDimNet2 checkpoint: size b0..b6, data `vox2` \| `vb2+vox2_v0` \| `vb2+vox2+cnc2_v0` (non-commercial), stage `ptn` \| `lm` \| `dis` |
| `VOICEPRINT_REDIMNET2_WEIGHTS_URL` | the GitHub release | `{weight_name}` template for an internal mirror |
| `VOICEPRINT_WESPEAKER_REPO` / `_ONNX` | `Wespeaker/wespeaker-voxceleb-resnet34-LM` / first `*.onnx` | Any WeSpeaker repo with an ONNX export; `resnet293-LM` and `campplus-LM` work |
| `VOICEPRINT_SPEECHBRAIN_REPO` | `speechbrain/spkrec-ecapa-voxceleb` | SpeechBrain speaker encoder |
| `VOICEPRINT_WINDOW_S` / `_HOP_S` / `_MIN_SPEECH_S` | `3.0` / `1.5` / `1.0` | Windowing defaults a request may override |
| `VOICEPRINT_MAX_WINDOWS` | `4000` | Cap per request; above it the hop widens |
| `VOICEPRINT_CLUSTER_THRESHOLD` | *(backend default)* | Deployment-wide cosine threshold |
| `VOICEPRINT_BATCH` | `32` | Windows per model call |
| `VOICEPRINT_MAX_UPLOAD_MB` | `4096` | 413 above this |
| `VOICEPRINT_TORCH_THREADS` | `4` | CPU threads for the torch backends |
| `VOICEPRINT_LOG_LEVEL` | `INFO` | |

`container.sh` additionally reads `VOICEPRINT_IMAGE`, `VOICEPRINT_NAME`, `VOICEPRINT_GPU`
(`all` | `0` | `none`), `VOICEPRINT_HF_CACHE`, `VOICEPRINT_TORCH_CACHE` and `VOICEPRINT_RUNTIME`
(`docker` | `podman`), and forwards every other `VOICEPRINT_*` variable into the container.

## Adding a backend

A backend is one class in `voiceprint/backends/` with a `model_id`, a `default_threshold`, a
`_load()` and a `_embed(list of equal-length float32 windows) -> [N, dim]` (see `base.py`), plus
one line in the registry's `_create()` and the name in `VOICEPRINT_BACKENDS`. That is the whole
seam: VAD, windowing, clustering, the timeline and the HTTP contract are shared. A remote
service (pyannoteAI's `/voiceprint`, Speechmatics' speaker identification) fits the same shape
with `_embed` doing an HTTP call, which is how a hosted API would be put behind the node without
touching Java.

## Tests

```bash
.venv/bin/python -m pytest tests -q --ignore=tests/test_live.py      # 25 tests, no model, < 1 s
VOICEPRINT_TEST_URL=http://localhost:9150 .venv/bin/python -m pytest tests/test_live.py -v -s
```

The unit tests drive the whole pipeline with a fake backend whose "embedding" is the window's
dominant frequency, over two synthetic tones taking turns - so VAD, windowing, batching,
clustering and the timeline are checked exactly, with only the network taken out. The live tests
run every real backend on `tests/data/two_speakers.wav`: two TTS voices (Kokoro `af_heart` and
`am_michael`, via the TTS sidecar; `tests/make_fixture.py` rebuilds it) taking five turns over
37.5 s, with the exact turn positions in `two_speakers.json`. All three backends attribute 13/13
windows to the right voice on an RTX 4090, in 1.7 s / 1.0 s / 0.3 s.

## Licences

| Component | Licence | Note |
|---|---|---|
| ReDimNet2 code (`third_party/redimnet2`, vendored at `c5bbe0b`) and `vox2` weights | MIT (c) 2026 Palabra.ai | trained on VoxCeleb2 |
| ReDimNet2 `vb2+vox2*` weights | MIT code, **CC-BY-NC-SA data** | VoxBlink2 / CN-Celeb: research only |
| WeSpeaker ResNet34-LM / ResNet293-LM / CAM++ exports | CC-BY-4.0 | attribute WeSpeaker; VoxCeleb2 |
| SpeechBrain ECAPA-TDNN | Apache-2.0 | VoxCeleb1+2 |
| VoxCeleb (training data of all three) | CC-BY-4.0 metadata; audio is YouTube content whose copyright stays with the uploaders | common industry practice; residual risk a legal review should see |

Voiceprints used to identify people are biometric data under GDPR Art. 9. Austria's § 9 Abs. 1 Z 2
DSG covers journalistic use by media companies; the AI Act most plausibly classes post-hoc
identification in archive audio as Annex III high-risk. Not legal advice - see the eval's
`spec/research/MARKET_AND_LICENSES.md`.
