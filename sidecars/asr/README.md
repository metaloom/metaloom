# ASR sidecar

Speech recognition for Metaloom as a container: **Whisper**, **Parakeet** and **Voxtral** behind
one HTTP + WebSocket API. Every result carries **time codes**: segment and word start/end, in
seconds and milliseconds, absolute to the start of the audio you sent. The focus is German.

| Backend | Model | Runs on | Real time | Where the time codes come from | German WER (FLEURS / CV) |
|---|---|---|---|---|---|
| `whisper` *(default)* | OpenAI Whisper large-v3-turbo, [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | GPU | per utterance, ~1 s after it ends, plus interim partials | Whisper's timestamp tokens + DTW on cross-attention (as in `openai-whisper`) | **3.41 % / 4.72 %** |
| `parakeet` | NVIDIA Parakeet-TDT-0.6B-v3, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | **CPU** | per utterance, ~1 s after it ends, plus interim partials | transducer frame of every token, snapped to the waveform | 4.41 % / 6.12 % |
| `voxtral` | Mistral Voxtral Mini 4B Realtime | GPU | **streaming**: text ~0.7 s behind the speaker | the 80 ms frame each word's first token is decoded in | 6.47 % / 8.16 % |

WER was measured through this sidecar's own backends on the 100 + 100 German utterances of
`audio-eval` (FLEURS DE test and Common Voice 17 DE test), scored with audio-eval's normalizer.
For comparison, audio-eval measured Whisper **large-v3** at 3.4 % / 4.3 %; set
`ASR_WHISPER_MODEL=large-v3` if that half point is worth twice the decode time.

Written for: Metaloom developers and operators who deploy the sidecar or call it from a node.

## Why this exists

The Voxtral container in `asr4j/voxtral` serves Voxtral Realtime through vLLM, and vLLM's
realtime API returns **no time codes** at all. The feature request is
[vllm#39735](https://github.com/vllm-project/vllm/issues/39735), and the segment-timestamp PR
[#50783](https://github.com/vllm-project/vllm/pull/50783) is unmerged as of 2026-09. For Whisper,
vLLM has had segment timestamps since
[#24209](https://github.com/vllm-project/vllm/pull/24209) (2025-12), but word timestamps are still
an open PR ([#47664](https://github.com/vllm-project/vllm/pull/47664)).

This sidecar gets both: word-level and segment-level time codes from every backend, over the same
realtime protocol shape, so a transcript can be mapped back onto the media.

## Quick start

```bash
./container.sh build
./container.sh run            # GPU "all", HF cache from ~/.cache/huggingface, port 9140
./container.sh test           # unit tests + live tests inside the running container
```

Weights are not in the image. They download into the mounted HF cache on first use:
Whisper 1.6 GB, Parakeet 2.5 GB (copied to `<HF cache>/asr-sidecar/`) and Voxtral 8.9 GB. So the
first request to each backend is slow. `ASR_PRELOAD=whisper,parakeet,voxtral` loads them at
startup instead.

```bash
curl -F file=@interview.mp4 -F model=whisper localhost:9140/v1/audio/transcriptions
curl -F file=@interview.mp4 -F response_format=srt localhost:9140/v1/audio/transcriptions
```

WAV, FLAC, OGG and MP3 are decoded directly. Anything else, including mp4/mkv/webm/mov video, is
decoded with the ffmpeg in the image.

## HTTP: `POST /v1/audio/transcriptions`

OpenAI-compatible multipart form: `file`, `model` (`whisper` | `parakeet` | `voxtral`; the full
model ids and `whisper-1` work too), `language` (default `de`, used by Whisper only), and
`response_format`. The differences from OpenAI's API are deliberate. The default format is
`verbose_json`, word **and** segment timestamps are always returned, and
`timestamp_granularities[]` is accepted but changes nothing.

`verbose_json`. This is a real response for the German test fixture, second segment, trimmed:

```json
{
  "task": "transcribe", "language": "de", "duration": 25.052, "model": "large-v3-turbo",
  "text": "Casablanca ist einer der … wie ein Land.",
  "segments": [
    {
      "id": 1, "start": 9.81, "end": 14.17, "start_ms": 9810, "end_ms": 14170,
      "text": "Für Stromausfälle hat Inge ein Wörterbuch zur Übersetzung in den Koffer gepackt.",
      "words": [
        {"word": "Für", "start": 9.81, "end": 10.29, "probability": 0.9868},
        {"word": "Stromausfälle", "start": 10.29, "end": 11.03, "probability": 0.9963},
        {"word": "hat", "start": 11.03, "end": 11.25, "probability": 0.9985}
      ]
    }
  ],
  "words": ["… every word of every segment, flattened …"]
}
```

`loom` (this one from `parakeet`): exactly the JSON `io.metaloom.cortex.media.whisper.WhisperResult#fromJson` parses, so a
node can store it as the existing transcript component unchanged:

```json
{"segments": [
  {"text": "Casablanca ist einer der uninteressantesten Orte zum Shoppen in ganz Marokko.", "from": 2190, "to": 6190},
  {"text": "Für Stromausfälle hat Inge ein Wörterbuch zur Übersetzung in den Koffer gepackt.", "from": 9970, "to": 14170},
  {"text": "Der Schengen-Raum funktioniert in dieser Hinsicht jedoch ein wenig wie ein Land.", "from": 17710, "to": 22490}
]}
```

Also `json` (`{"text"}`), `text`, `srt` and `vtt`. The response headers `X-Model-Id` and
`X-Backend` name the model that answered.

A file is cut at its pauses by the same endpointer the realtime path uses, and each piece is
decoded on its own and shifted back to its position. A 249 s recording (20 German clips back to
back) came back with monotonic word times and every word inside its source clip's span, on all
three backends.

## Realtime: `WS /v1/realtime?model=voxtral`

The transcription subset of the OpenAI/vLLM realtime API. Audio is base64 PCM16 LE, mono, 16 kHz.
**Every time in every event is seconds since the first sample of the session.**

| Direction | Event | Payload |
|---|---|---|
| → | `session.update` | `{"session": {"model", "language", "turn_detection": {"type": "server_vad", "silence_duration_ms"} \| null}}`. vLLM's flat `{"model", "language"}` is accepted too. The model can only change before the first audio |
| → | `input_audio_buffer.append` | `{"audio": "<base64 PCM16>"}` |
| → | `input_audio_buffer.commit` | ends the current utterance now; always answered by exactly one `transcription.done` |
| ← | `session.created` / `session.updated` | `{"session": {...}}` |
| ← | `input_audio_buffer.speech_started` / `speech_stopped` | `{"item_id", "audio_start_ms" / "audio_end_ms"}`, server VAD only |
| ← | `transcription.delta` | `{"item_id", "delta", "end"}`: Voxtral, one per text token, while the speaker talks |
| ← | `transcription.partial` | `{"item_id", "text", "start", "end"}`: Whisper/Parakeet interim text, replaced by the next one |
| ← | `transcription.segment` | `{"item_id", "segment": {id, start, end, start_ms, end_ms, text, words}}`: **final**, never revised |
| ← | `transcription.done` | `{"item_id", "text", "start", "end", "segments"}`: one per utterance, repeating its segments |
| ← | `error` | `{"error": {"message"}}` |

With `server_vad` (the default) the server cuts utterances at pauses of `silence_duration_ms`
(600 ms), so a client can stream a microphone and never commit. With `turn_detection: null` only
commits end utterances.

Clients that segment the audio themselves and expect exactly one `transcription.done` per commit,
like the `RealtimeSpeechToText` classes in `speck` and `webrtc-poc`, need `turn_detection: null`.
Either they send it, or the sidecar runs with `ASR_TURN_DETECTION=none`. Otherwise the extra
`done` events for pauses desynchronise their queue of pending commits. They send the Voxtral repo
id as `model`, which resolves to `voxtral`. Neither client has been run against this sidecar yet.

A real Voxtral trace: the first clip of the fixture, streamed at real-time speed, left column is
wall-clock seconds since the first byte was sent:

```
 1.60s input_audio_buffer.speech_started  item_0001  audio_start_ms 1230
 3.83s transcription.delta   " Casablanca"   end 3.15
 4.10s transcription.delta   " ist"          end 3.39
 4.20s transcription.delta   " einer"        end 3.47
 ...
 7.00s transcription.delta   " Marokko"      end 6.27
 7.10s input_audio_buffer.speech_stopped   item_0001  audio_end_ms 6840
 7.57s transcription.segment  2.47-6.27  "Casablanca ist einer der uninteressantesten Orte zum Shoppen in ganz Marokko."
 7.57s transcription.done     item_0001  1.23-6.84
```

Whisper and Parakeet send no deltas. While the speaker talks they send a `transcription.partial`
every `ASR_PARTIAL_INTERVAL_MS` (a fresh decode of the utterance so far), and the final segments
follow about a second after the utterance ends: the 600 ms pause that ends it, plus the decode.

## How the time codes are made, and how good they are

Measured by `tests/test_live.py` on the German fixture, against Whisper's word timings and the
known clip positions:

| Backend | Word end vs Whisper (median / p90 / worst) | Word start vs Whisper (median / p90) |
|---|---|---|
| `voxtral` | 0.06 / 0.10 / 0.20 s | 0.08 / 0.16 s |
| `parakeet` | 0.08 / 0.20 / 0.28 s | 0.10 / 0.20 s |

- **Whisper** gives segment times from its timestamp tokens and word times from DTW over the
  alignment heads' cross-attention (`word_timestamps=True`).
- **Voxtral** decodes exactly one token per 80 ms of audio: `[STREAMING_PAD]`, `[STREAMING_WORD]`
  or text. Measured on the fixture, the **first text token of a word marks the end of that word**,
  at `(k − 1) × 80 ms`. So word *ends* are measured. Word *starts* are not: a word starts where the
  previous one ended, or, after a pause, one estimated word length (60 ms per letter) before its
  end. `ASR_VOXTRAL_TIME_SHIFT_MS` shifts every Voxtral time, for recalibrating another checkpoint.
- **Parakeet** is a transducer: every token has the encoder frame it was emitted in plus a TDT
  duration. At utterance edges those are loose. Sentence-final words ran 0.4–0.6 s into the
  following room noise, and a word after a pause was emitted 0.4 s before it was spoken. So each
  word is snapped to the frames that actually carry it: within 20 dB of the word's own peak and
  10 dB above the utterance's noise floor. The snap only ever shrinks a word.

The least precise value is **the start of the first word after a pause**, for every backend.
Whisper put the fixture's "Der" 1 s early, Parakeet 0.4 s early. Word *ends* and segment
boundaries are the values to key a media position on.

## Choosing a backend

- **Files, or quality first:** `whisper`. It has the best German WER and decodes about 80× faster
  than real time on an RTX 4090.
- **No GPU, or the GPU is busy:** `parakeet`. It runs 36–45× real time on four CPU threads. It is
  about one WER point behind Whisper in German, and it detects the language itself (25 European
  languages).
- **Text on screen while someone is still speaking:** `voxtral`. It is the only true streaming
  model. Streaming costs German quality: it commits to a word before it has heard what follows,
  and its WER is about twice Whisper's. It is the slowest for files (about 4× real time offline) and the heaviest, and only
  `ASR_VOXTRAL_MAX_STREAMS` sessions (default 1) can stream at once, because each one holds the
  model for as long as its speaker talks.

## Configuration

Every variable is read by `server.py` itself, including host and port.

| Variable | Default | Meaning |
|---|---|---|
| `ASR_HOST` / `ASR_PORT` | `0.0.0.0` / `9140` | bind address |
| `ASR_BACKENDS` | `whisper,parakeet,voxtral` | backends this process offers |
| `ASR_DEFAULT_MODEL` | `whisper` | backend for requests without `model` |
| `ASR_LANGUAGE` | `de` | Whisper's decode language; `auto` detects it. Parakeet and Voxtral always detect |
| `ASR_PRELOAD` | *(empty)* | backends to load at startup, e.g. `whisper,voxtral` |
| `ASR_WHISPER_MODEL` | `large-v3-turbo` | faster-whisper size, HF repo or local CTranslate2 dir |
| `ASR_WHISPER_DEVICE` / `ASR_WHISPER_COMPUTE_TYPE` | `auto` / `float16` on cuda, `int8` on cpu | |
| `ASR_WHISPER_BEAM_SIZE` | `5` | |
| `ASR_PARAKEET_MODEL` | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3` | HF repo or local dir; the `-int8` export works too |
| `ASR_PARAKEET_THREADS` / `ASR_PARAKEET_PROVIDER` | `4` / `cpu` | ONNX Runtime threads and provider |
| `ASR_VOXTRAL_MODEL` | `mistralai/Voxtral-Mini-4B-Realtime-2602` | |
| `ASR_VOXTRAL_DEVICE` / `ASR_VOXTRAL_DTYPE` | `cuda` / `bfloat16` | |
| `ASR_VOXTRAL_MAX_STREAMS` | `1` | concurrent Voxtral realtime sessions |
| `ASR_VOXTRAL_TIME_SHIFT_MS` | `0` | constant added to every Voxtral time |
| `ASR_MAX_SESSIONS` | `8` | concurrent realtime sessions, all backends |
| `ASR_TURN_DETECTION` | `server_vad` | session default; `none` = commits only |
| `ASR_SILENCE_MS` | `600` | pause that ends an utterance |
| `ASR_MAX_UTTERANCE_S` | `20` | longer speech is cut at its quietest recent frame |
| `ASR_PARTIAL_INTERVAL_MS` | `1000` | Whisper/Parakeet interim decodes; `0` turns them off |
| `ASR_MAX_UPLOAD_MB` | `512` | |

`container.sh` takes `ASR_IMAGE`, `ASR_NAME`, `ASR_PORT`, `ASR_GPU` (`all`, `0`, `1`, `none`),
`ASR_HF_CACHE` and `ASR_RUNTIME` (`docker` | `podman`), and forwards every other `ASR_*`
variable into the container.

Resources, measured on an RTX 4090 with all three backends loaded: **13.2 GB** of VRAM (Whisper +
Voxtral; Parakeet is on the CPU) and a 12.7 GB image.

## Tests

```bash
./container.sh test                                   # everything, inside the running container
python -m pytest tests -q --ignore=tests/test_live.py # unit tests, no model, in the venv
ASR_TEST_URL=http://localhost:9140 python -m pytest tests/test_live.py -v -s
```

- `test_timecodes.py`, `test_endpointing.py`, `test_voxtral_timer.py`: the transcript model, the
  renderings (including the exact `WhisperResult` JSON), the endpointer, and Voxtral's
  token-to-time rule replayed on a token stream the model really produced.
- `test_server.py`: the HTTP and WebSocket contract with fake backends that answer in
  utterance-relative time. It checks that the server turns that into absolute time, that every
  commit gets its `done`, and that an mp4 decodes.
- `test_live.py`: the real models on `tests/data/de_timeline.flac`, three German clips at known
  positions with 2.5 s gaps (FLEURS CC-BY-4.0, Common Voice CC0; rebuild it with
  `tests/make_fixture.py`). For every backend over HTTP and over a real-time stream it checks:
  WER per clip; every word inside its clip's window and none in a gap; word times agreeing with
  Whisper's; stream and file giving the same times; final segments within 3 s of the speech; and
  Voxtral text arriving while the clip is still being spoken.

Last run: 36 unit and 20 live tests passed, RTX 4090, 1 min 39 s.

## Limits

- **No authentication.** It binds `0.0.0.0`, like every sidecar here. Keep it on a private
  network.
- The endpointer is energy-based, not a neural VAD. In a noisy room it may not find pauses, and
  then utterances are cut at `ASR_MAX_UTTERANCE_S` instead, at the quietest recent frame.
- Voxtral does not take a language. It auto-detects among its 13, and German is one of them.
- While a Voxtral stream runs, HTTP requests with `model=voxtral` wait for the current utterance to
  finish.
- **Running this from inside a dev container** against the host's docker daemon: bind-mount
  paths are resolved on the host, so pass the host path of the HF cache in `ASR_HF_CACHE`.

## Licences

The code in this directory is Apache-2.0. Weights: Whisper **MIT**, Voxtral Mini 4B Realtime
**Apache-2.0**, Parakeet-TDT-0.6B-v3 **CC-BY-4.0** (commercial use allowed, attribution to NVIDIA
required). The test fixture is FLEURS (CC-BY-4.0) and Common Voice (CC0-1.0).
