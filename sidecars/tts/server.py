"""
TTS sidecar — FastAPI HTTP server for the Cortex `tts` node.

`POST /v1/tts` turns text into audio and routes by language. Which engine serves a language is a
property of the PROCESS, set by `TTS_ENGINE_DE` / `TTS_ENGINE_EN`:

  * lang="de" -> `orpheus` (Orpheus-3B/Kartoffel, a Llama-3.2 LM whose SNAC audio tokens are
                 decoded to a waveform here) or `kartoffelbox` (a Chatterbox fine-tune that
                 clones a reference clip — the better German voice, see spec §4)
  * lang="en" -> `kokoro` (Kokoro-82M, ONNX on CPU)

`POST`/`GET /v1/tts/stream` answers the same audio as a chunked response that starts before the
synthesis has finished. The GET form exists so a browser can play it with `<audio src="...">` and
no JavaScript at all.

`format` selects the container, `wav` (the default) or `mp3`. WAV stays the default because the
Java `TtsClient` reads the response body as a waveform — MP3 is opt-in per request and never
changes what an existing caller gets.

`GET /v1/models` reports what the running process can speak with, derived from its configuration
and loading nothing; `GET /voices` projects the same data in its older per-language shape.

Everything else lives in the `tts/` package next to this file: `tts/engines/` (one module per
model, plus the registry), `tts/chunking.py`, `tts/audio.py`, `tts/postfx.py`, `tts/synth.py`.
`tts/config.py` is the configuration reference; spec/sidecars/TTS_SIDECAR.md is the contract.

Run:
  ./run.sh                 # or: python server.py   — both honour TTS_HOST / TTS_PORT
"""

import logging
from contextlib import asynccontextmanager
from typing import Tuple

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from tts import config, engines, synth
from tts.audio import DEFAULT_FORMAT, FORMATS, STREAM_ENCODERS, encode

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("tts-server")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Says which engine, which checkpoint and which voices this process resolved to. Both
    # failures it prevents are silent ones: a Kartoffelbox process with no clips answers every
    # request in a voice nobody selected, and a relative path resolved against the wrong working
    # directory is how that happens.
    engines.log_startup()
    yield


app = FastAPI(title="Cortex TTS Server", lifespan=lifespan)


class TTSRequest(BaseModel):
    text: str
    lang: str = "de"               # routed by prefix: "de-AT" is German
    voice: str = "Jakob"
    format: str = DEFAULT_FORMAT   # "wav" | "mp3"


def _validate(text: str, lang: str, fmt: str) -> Tuple[str, str]:
    """The request contract, shared by the blocking and the streaming routes.

    It is one function because a stream must refuse EXACTLY what the file refuses. The two routes
    are the same request with a different delivery, and a text that is a 400 on one and a broken
    stream on the other would be worse than either behaviour on its own.

    Returns the normalised `(language, format)`; the messages quote what the caller sent.
    """
    if not text or not text.strip():
        raise HTTPException(status_code=400, detail="text is required")

    fmt_norm = (fmt or DEFAULT_FORMAT).lower()
    # Rejected rather than quietly defaulted to wav: silently ignoring `format` is the defect this
    # endpoint fixed, and a caller that asked for something we cannot produce needs to hear so,
    # not receive a different container labelled as its own.
    if fmt_norm not in FORMATS:
        raise HTTPException(
            status_code=400,
            detail=f"unsupported format: {fmt} (supported: {', '.join(sorted(FORMATS))})")

    try:
        lang_norm = engines.resolve_language(lang)
    except KeyError:
        raise HTTPException(
            status_code=400,
            detail=f"unsupported language: {lang} (supported: {', '.join(engines.languages())})")

    return lang_norm, fmt_norm


# --------------------------------------------------------------------------- #
# Catalogue — derived from configuration, never hard-coded
# --------------------------------------------------------------------------- #
@app.get("/health")
async def health():
    """Liveness only — it loads nothing and checks no engine.

    A process that will fail every German request still answers `ok` here; `/v1/models` carries
    the per-engine readiness (`loaded` / `available`).
    """
    return {
        "status": "ok",
        # Which engine actually answers for each language in this process.
        "engines": dict(config.ENGINE_BY_LANG),
        # Kept although it means nothing when Kartoffelbox is bound: it is a published field, and
        # removing one is a breaking change for a probe nobody versions.
        "orpheus_backend": config.ORPHEUS_BACKEND,
        "device": config.DEVICE,
        "formats": sorted(FORMATS),
        "streaming": True,
        "postfx": config.TTS_POSTFX,
    }


@app.get("/v1/models")
async def models():
    """What this process can speak with, derived from its running configuration.

    Nothing here loads a model: the German voices are a directory listing or a property of the
    configured checkpoint, and the English ones come from the npz *index* of the voices file. A
    readiness probe must not trigger a multi-GB download.
    """
    bound = engines.bound()
    return {
        "default_lang": next(iter(config.ENGINE_BY_LANG), "de"),
        "default_format": DEFAULT_FORMAT,
        "formats": sorted(FORMATS),
        "languages": list(config.ENGINE_BY_LANG),
        # The post-production mode every response of this process goes through (`off` = none).
        "postfx": {
            "mode": config.TTS_POSTFX,
            "modes": list(config.POSTFX_MODES),
            "target_lufs": config.TTS_POSTFX_LUFS if config.TTS_POSTFX != "off" else None,
        },
        # So a client can find out whether this process streams without probing for a 404, and
        # whether it does so inside a generation or only between chunks.
        "streaming": {
            "endpoint": "/v1/tts/stream",
            "formats": sorted(STREAM_ENCODERS),
            "chunk_chars": config.TTS_STREAM_CHUNK_CHARS,
            "incremental": any(m["incremental"] for m in (e.info() for e in bound)),
        },
        "models": [engine.info() for engine in bound],
    }


@app.get("/voices")
async def voices():
    """The per-language view of the same catalogue `/v1/models` reports.

    Kept in its original shape because it is a published route, but no longer hard-coded: it
    projects the engines, so the two can never disagree. The old static map advertised six German
    voices even for the single-speaker Thorsten checkpoint.
    """
    by_lang = {}
    for lang in config.ENGINE_BY_LANG:
        engine = engines.for_language(lang)
        by_lang[lang] = {
            "engine": engine.name,
            "repo": engine.repo,
            "voices": engine.voices(),
        }
    return by_lang


# --------------------------------------------------------------------------- #
# Synthesis
# --------------------------------------------------------------------------- #
@app.post("/v1/tts")
async def synthesize(req: TTSRequest):
    """The whole recording in one response. Raw audio bytes, never JSON or base64.

    The synthesis runs on a worker thread rather than the event loop, so `/health` keeps
    answering while a long recording is being made — and so the per-engine lock cannot block the
    loop a concurrent stream needs.
    """
    lang, fmt = _validate(req.text, req.lang, req.format)
    engine = engines.for_language(lang)
    logger.info("TTS request — lang=%s engine=%s voice=%s format=%s text=%r",
                lang, engine.name, req.voice, fmt, req.text[:80])

    try:
        audio, media_type = await run_in_threadpool(_render, engine, req.text, req.voice, fmt)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        logger.error("synthesis failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"synthesis error: {e}")

    logger.info("TTS response — %d bytes %s", len(audio), fmt)
    return Response(content=audio, media_type=media_type)


def _render(engine, text: str, voice: str, fmt: str):
    waveform, rate = synth.synthesize(engine, text, voice)
    return encode(waveform, rate, fmt)


# --------------------------------------------------------------------------- #
# Streaming — the same audio, delivered while it is still being made
# --------------------------------------------------------------------------- #
#: `X-Accel-Buffering: no` stops nginx from collecting the whole response and forwarding it in one
#: piece at the end, which would leave the endpoint streaming in name only. `no-store` stops a
#: caching proxy from keeping a body that was never complete.
_STREAM_HEADERS = {"Cache-Control": "no-store", "X-Accel-Buffering": "no"}

#: `next(gen, _NOTHING)` rather than catching StopIteration: StopIteration cannot cross the
#: threadpool boundary below, it would surface as a RuntimeError with no cause.
_NOTHING = object()


async def _stream_response(text: str, lang: str, voice: str, fmt: str) -> StreamingResponse:
    """Validate, produce the FIRST chunk, and only then start the response.

    Priming matters more than it looks. A status code can only be sent before the first byte, so
    anything that fails after that — the 503 for missing Kokoro weights, the 400 for an unknown
    English voice, a CUDA OOM on the first load — would reach the caller as an empty 200 that ends
    immediately. Pulling one chunk here keeps every refusal a real status code, and costs only the
    latency it was going to cost anyway.

    A stream that fails must not end cleanly: a truncated stream that ends politely is
    indistinguishable from a finished recording.
    """
    lang, fmt = _validate(text, lang, fmt)
    engine = engines.for_language(lang)
    logger.info("TTS stream request — lang=%s engine=%s voice=%s format=%s text=%r",
                lang, engine.name, voice, fmt, text[:80])

    chunks = synth.stream_bytes(engine, text, voice, fmt)
    try:
        first = await run_in_threadpool(next, chunks, _NOTHING)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        logger.error("streaming synthesis failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"synthesis error: {e}")

    if first is _NOTHING:
        # No piece at all. An empty 200 would be written out as a valid silent file.
        raise HTTPException(status_code=500, detail="synthesis error: no audio was produced")

    def _body():
        yield first
        yield from chunks

    return StreamingResponse(_body(), media_type=FORMATS[fmt][2], headers=_STREAM_HEADERS)


@app.post("/v1/tts/stream")
async def synthesize_stream(req: TTSRequest):
    """The same request body as `/v1/tts`, answered as a chunked response."""
    return await _stream_response(req.text, req.lang, req.voice, req.format)


@app.get("/v1/tts/stream")
async def synthesize_stream_get(
    text: str = Query(..., description="the text to speak"),
    lang: str = Query("de", description="a language this process serves, matched by prefix"),
    voice: str = Query("Jakob", description="a voice of that language"),
    format: str = Query(DEFAULT_FORMAT, description='"wav" or "mp3"'),
):
    """The streaming route as a plain URL, so a browser can play it with no JavaScript.

        <audio controls src="http://tts-host:9100/v1/tts/stream?text=Guten+Tag&format=mp3">

    That is the whole reason this exists. A POST cannot be the source of an `<audio>` element, so
    the alternative for every frontend is fetch + MediaSource and a few dozen lines of buffer
    plumbing to play bytes a browser already knows how to play.

    The cost is the URL length limit — about 2000 characters in practice, and the text has to
    survive percent-encoding. Longer text belongs in the POST.
    """
    return await _stream_response(text, lang, voice, format)


if __name__ == "__main__":
    import uvicorn

    # One worker, always. Each one would load its own full copy of the engine onto the same GPU.
    uvicorn.run(app, host=config.HOST, port=config.PORT)
