# Cortex sentiment sidecar

FastAPI server behind the Cortex `sentiment` node. One endpoint,
`POST /v1/sentiment`, routes by language:

| `lang` | Model | Licence |
|---|---|---|
| `de` | **`oliverguhr/german-sentiment-bert`** | MIT |
| `en` | **`cardiffnlp/twitter-roberta-base-sentiment-latest`** | CC-BY-4.0 — commercial use allowed, **attribution required** |
| anything else | **`lxyuan/distilbert-base-multilingual-cased-sentiments-student`** | Apache-2.0 |

The Java `SentimentNode` (`io.metaloom.cortex.node.sentiment`) is a pure HTTP client
of this server — it never loads a model. This mirrors the `tts` sidecar.

See [NODE_SENTIMENT.md](../../spec/features/nodes/sentiment/NODE_SENTIMENT.md)
for the model survey, including the checkpoints that were rejected on licence
grounds.

## Why a sidecar and not a JVM inference stack

Cortex has no ONNX/DJL/HF runtime on the JVM — the only in-process model is
whisper.cpp via JNA. Keeping the encoder in Python means swapping a checkpoint is
configuration, not Java code. Three responsibilities live here rather than in the
node:

1. **Language routing** — `lang="auto"` (the node default) detects with
   [lingua](https://github.com/pemistahl/lingua-py), constrained to `SENTIMENT_LANGS`.
2. **Chunking** — all three models are 512-token encoders, but `tika_content` for a
   PDF is routinely far longer. Text is split on sentence boundaries into
   `MAX_CHUNK_TOKENS` word-piece chunks, each chunk is classified, and the
   distributions are aggregated **length-weighted** into one document label.
3. **Label normalisation** — native labels map onto `POSITIVE|NEUTRAL|NEGATIVE`,
   plus a signed `polarity = p(positive) - p(negative)` in `[-1, 1]`.

## Setup

```bash
./setup.sh        # venv only; weights download lazily per language
```

## Run

```bash
./run.sh          # port 9110 (the SentimentNode default; 9100 is TTS)
```

## Test

```bash
curl -s localhost:9110/v1/sentiment -H 'Content-Type: application/json' \
  -d '{"texts":["Der Kundenservice war eine Katastrophe."],"lang":"auto"}'
```

```json
[{"label":"NEGATIVE","score":0.973,"polarity":-0.969,
  "scores":{"positive":0.004,"neutral":0.023,"negative":0.973},
  "lang":"de","model":"oliverguhr/german-sentiment-bert","chunks":1,"truncated":false}]
```

`GET /health` reports the device, the configured model ids and which of them are
already loaded.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SENTIMENT_MODEL_DE` | `oliverguhr/german-sentiment-bert` | German checkpoint |
| `SENTIMENT_MODEL_EN` | `cardiffnlp/twitter-roberta-base-sentiment-latest` | English checkpoint |
| `SENTIMENT_MODEL_FALLBACK` | `lxyuan/distilbert-base-multilingual-cased-sentiments-student` | Any other detected language |
| `SENTIMENT_LANGS` | `de,en` | Languages the auto-detector is constrained to |
| `MAX_CHUNK_TOKENS` | `400` | Word-piece budget per chunk (headroom under the 512 limit) |
| `MAX_CHUNKS` | `64` | Chunks per text; excess is truncated and flagged in the response |
| `DEVICE` | `cuda` if available else `cpu` | torch device |
| `SENTIMENT_HOST` / `SENTIMENT_PORT` | `0.0.0.0` / `9110` | Bind address (`run.sh`) |

## Licence note

The English default is **CC-BY-4.0**: commercial use is allowed but the model must
be credited. The model id travels back in every response, and the node records it
as the `producerVersion` and in the component payload. If a deployment cannot carry
the attribution, point `SENTIMENT_MODEL_EN` at the Apache-2.0 fallback model.

Do **not** substitute `tabularisai/multilingual-sentiment-analysis`: it is the top
multilingual hit on the Hub and is **CC-BY-NC-4.0** (non-commercial).
