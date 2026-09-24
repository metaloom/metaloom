"""
Cortex ASR sidecar - time-coded German/English speech recognition over HTTP and WebSocket.

Three backends behind one API, all of them returning time codes (see asr/timecodes.py):

  whisper   OpenAI Whisper large-v3-turbo (faster-whisper, GPU)   best German WER, the default
  parakeet  NVIDIA Parakeet-TDT-0.6B-v3 (sherpa-onnx, CPU)       native transducer timings, no GPU
  voxtral   Mistral Voxtral Mini 4B Realtime (transformers, GPU)  true streaming, ~0.5 s behind

  GET  /health                     -> backends, which are loaded, defaults
  GET  /v1/models                  -> OpenAI-style model list
  POST /v1/audio/transcriptions    -> OpenAI-compatible; multipart `file`, `model`, `language`,
                                      `response_format` = verbose_json (default) | json | text |
                                      loom | srt | vtt
  WS   /v1/realtime?model=...      -> OpenAI/vLLM realtime-shaped transcription session

Realtime protocol (every time is seconds since the session's first sample):

  client -> server   session.update             {"session": {"model", "language",
                                                  "turn_detection": {"type": "server_vad",
                                                  "silence_duration_ms"} | null}}
                                                 (flat {"model", "language"} as vLLM sends it
                                                  is accepted too)
  client -> server   input_audio_buffer.append  {"audio": base64 PCM16 LE mono 16 kHz}
  client -> server   input_audio_buffer.commit  {}
  server -> client   session.created / session.updated {"session": {...}}
  server -> client   input_audio_buffer.speech_started {"item_id", "audio_start_ms"}
  server -> client   input_audio_buffer.speech_stopped {"item_id", "audio_end_ms"}
  server -> client   input_audio_buffer.committed      {"item_id"}
  server -> client   transcription.delta    {"item_id", "delta", "end"}            voxtral only
  server -> client   transcription.partial  {"item_id", "text", "start", "end"}    whisper, parakeet
  server -> client   transcription.segment  {"item_id", "segment": {id, start, end, start_ms,
                                              end_ms, text, words: [{word, start, end}]}}
  server -> client   transcription.done     {"item_id", "text", "start", "end", "segments"}
  server -> client   error                  {"error": {"message"}}

Run:
  python server.py                 # binds ASR_HOST:ASR_PORT (default 0.0.0.0:9140)
"""

from __future__ import annotations

import asyncio
import base64
import logging
import os
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, PlainTextResponse

from asr import audio as audio_io
from asr import backends, config, timecodes
from asr.endpointing import EndpointerConfig, split
from asr.session import RealtimeSession

logging.basicConfig(level=os.environ.get("ASR_LOG_LEVEL", "INFO"), format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("asr")

SAMPLE_RATE = 16000


def endpointer_config(silence_ms: Optional[int] = None) -> EndpointerConfig:
    return EndpointerConfig(silence_ms=silence_ms or config.SILENCE_MS, max_utterance_s=config.MAX_UTTERANCE_S)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Created here, not at import: an asyncio.Semaphore binds to the loop it is first used on.
    app.state.session_slots = asyncio.Semaphore(config.MAX_SESSIONS)
    app.state.stream_slots = asyncio.Semaphore(config.VOXTRAL_MAX_STREAMS)
    loop = asyncio.get_running_loop()
    for name in config.PRELOAD:
        await loop.run_in_executor(None, backends.get(name).ensure_loaded)
    yield


app = FastAPI(title="Metaloom ASR sidecar", lifespan=lifespan)


def _backend_or_400(model: Optional[str]):
    try:
        return backends.get(model)
    except KeyError:
        raise HTTPException(status_code=400, detail=f"unknown or disabled model {model!r}, enabled: {config.BACKENDS}")


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
@app.get("/health")
def health():
    return {
        "status": "ok",
        "default_model": config.DEFAULT_MODEL,
        "language": config.LANGUAGE,
        "backends": {name: b.info() for name, b in backends.available().items()},
    }


@app.get("/v1/models")
def models():
    return {
        "object": "list",
        "data": [{"id": name, "object": "model", "owned_by": "metaloom", "root": b.model_id}
                 for name, b in backends.available().items()],
    }


def transcribe_file(backend, samples, language: Optional[str]) -> timecodes.Transcript:
    """
    Cut the file with the same endpointer the realtime path uses, decode each span on its own and
    shift its times to the span's position. Long files never reach a model in one piece, and a
    file and a stream of the same audio are cut in the same places.
    """
    parts = []
    for start, end in split(samples, endpointer_config()):
        t = backend.transcribe(samples[start:end], language)
        t = timecodes.offset(t, start / SAMPLE_RATE)
        parts.append(timecodes.clamp(t, start / SAMPLE_RATE, end / SAMPLE_RATE))
    return timecodes.merge(parts, len(samples) / SAMPLE_RATE, backend.model_id, language if backend.takes_language else None)


@app.post("/v1/audio/transcriptions")
def transcriptions(
    file: UploadFile = File(...),
    model: Optional[str] = Form(None),
    language: Optional[str] = Form(None),
    response_format: str = Form("verbose_json"),
    timestamp_granularities: Optional[List[str]] = Form(None, alias="timestamp_granularities[]"),
    prompt: Optional[str] = Form(None),
    temperature: Optional[float] = Form(None),
):
    """
    OpenAI-compatible transcription. Unlike OpenAI's, the default response_format is
    verbose_json, and word *and* segment timestamps are always produced -
    `timestamp_granularities[]` is accepted for compatibility and changes nothing.
    `prompt` and `temperature` are accepted and ignored.
    """
    if response_format not in timecodes.RESPONSE_FORMATS:
        raise HTTPException(status_code=400, detail=f"response_format must be one of {timecodes.RESPONSE_FORMATS}")
    backend = _backend_or_400(model)
    data = file.file.read(config.MAX_UPLOAD_MB * 1024 * 1024 + 1)
    if len(data) > config.MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"upload larger than ASR_MAX_UPLOAD_MB={config.MAX_UPLOAD_MB}")
    try:
        samples = audio_io.decode(data)
    except audio_io.AudioDecodeError as e:
        raise HTTPException(status_code=400, detail=str(e))
    lang = language or config.LANGUAGE
    transcript = transcribe_file(backend, samples, None if lang == "auto" else lang)
    body, media_type = timecodes.render(transcript, response_format)
    headers = {"X-Model-Id": backend.model_id, "X-Backend": backend.name}
    if isinstance(body, dict):
        return JSONResponse(body, headers=headers)
    return PlainTextResponse(body, media_type=media_type, headers=headers)


# ---------------------------------------------------------------------------
# Realtime
# ---------------------------------------------------------------------------
def _parse_update(event: dict) -> dict:
    """session.update in OpenAI shape ({"session": {...}}) or vLLM's flat shape."""
    s = event.get("session") if isinstance(event.get("session"), dict) else event
    out = {}
    if s.get("model"):
        out["model"] = s["model"]
    lang = s.get("language") or (s.get("input_audio_transcription") or {}).get("language")
    if lang:
        out["language"] = lang
    if "turn_detection" in s:
        td = s["turn_detection"]
        out["turn_detection"] = bool(td) and (not isinstance(td, dict) or td.get("type", "server_vad") == "server_vad")
        if isinstance(td, dict) and td.get("silence_duration_ms"):
            out["silence_ms"] = int(td["silence_duration_ms"])
    return out


class _Connection:
    """The WebSocket side of one session: owns the session, its backend slot, and the event pump."""

    def __init__(self, ws: WebSocket, loop: asyncio.AbstractEventLoop):
        self.ws = ws
        self.stream_slots: asyncio.Semaphore = ws.app.state.stream_slots
        self.loop = loop
        self.out: asyncio.Queue = asyncio.Queue()
        self.session: Optional[RealtimeSession] = None
        self.backend = None
        self.language = config.LANGUAGE
        self.turn_detection = config.TURN_DETECTION == "server_vad"
        self.silence_ms = config.SILENCE_MS
        self.holds_stream = False
        self.audio_seen = False

    def emit(self, event: dict):
        self.loop.call_soon_threadsafe(self.out.put_nowait, event)

    def describe(self) -> dict:
        return {
            "model": self.backend.name,
            "model_id": self.backend.model_id,
            "language": self.language,
            "sample_rate": SAMPLE_RATE,
            "turn_detection": {"type": "server_vad", "silence_duration_ms": self.silence_ms} if self.turn_detection else None,
        }

    async def use(self, model: Optional[str]):
        backend = backends.get(model)  # KeyError handled by caller
        if self.backend is backend and self.session is not None:
            return
        await self.release()
        if backend.streaming:
            if self.stream_slots.locked():
                raise RuntimeError(f"all {config.VOXTRAL_MAX_STREAMS} {backend.name} stream(s) are in use")
            await self.stream_slots.acquire()
            self.holds_stream = True
        self.backend = backend
        await self.loop.run_in_executor(None, backend.ensure_loaded)
        self.session = RealtimeSession(
            backend,
            self.emit,
            language=None if self.language == "auto" else self.language,
            turn_detection=self.turn_detection,
            endpointer=endpointer_config(self.silence_ms),
            partial_interval_ms=config.PARTIAL_INTERVAL_MS,
        )

    async def release(self):
        if self.session is not None:
            await self.loop.run_in_executor(None, self.session.close)
            self.session = None
        if self.holds_stream:
            self.stream_slots.release()
            self.holds_stream = False

    async def pump(self):
        try:
            while True:
                await self.ws.send_json(await self.out.get())
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - the socket went away; the receive loop will notice
            logger.debug("event pump stopped", exc_info=True)

    async def handle(self, event: dict):
        kind = event.get("type", "")
        if kind == "input_audio_buffer.append":
            self.audio_seen = True
            pcm = audio_io.pcm16_to_float(base64.b64decode(event.get("audio", "")))
            self.session.append(pcm)
        elif kind == "input_audio_buffer.commit":
            item_id = self.session.commit()
            self.emit({"type": "input_audio_buffer.committed", "item_id": item_id})
        elif kind == "session.update":
            update = _parse_update(event)
            if "language" in update:
                self.language = update["language"]
            if "turn_detection" in update:
                self.turn_detection = update["turn_detection"]
            if "silence_ms" in update:
                self.silence_ms = update["silence_ms"]
            model = update.get("model")
            if model and backends.resolve(model) != self.backend.name:
                if self.audio_seen:
                    raise RuntimeError("the model can only be changed before the first audio")
                await self.use(model)
            else:
                self.session.update(
                    language=None if self.language == "auto" else self.language,
                    turn_detection=self.turn_detection,
                    silence_ms=self.silence_ms,
                )
            self.emit({"type": "session.updated", "session": self.describe()})
        else:
            # The rest of the realtime API is about conversations and speech output.
            logger.debug("ignoring event %s", kind)


@app.websocket("/v1/realtime")
async def realtime(ws: WebSocket):
    await ws.accept()
    slots: asyncio.Semaphore = ws.app.state.session_slots
    if slots.locked():
        await ws.send_json({"type": "error", "error": {"message": f"all {config.MAX_SESSIONS} sessions are in use"}})
        await ws.close()
        return
    async with slots:
        conn = _Connection(ws, asyncio.get_running_loop())
        try:
            await conn.use(ws.query_params.get("model"))
        except (KeyError, RuntimeError) as e:
            message = f"unknown or disabled model {ws.query_params.get('model')!r}" if isinstance(e, KeyError) else str(e)
            await ws.send_json({"type": "error", "error": {"message": message}})
            await ws.close()
            await conn.release()
            return
        await ws.send_json({"type": "session.created", "session": conn.describe()})
        pump = asyncio.create_task(conn.pump())
        try:
            while True:
                event = await ws.receive_json()
                try:
                    await conn.handle(event)
                except (KeyError, RuntimeError, ValueError) as e:
                    conn.emit({"type": "error", "error": {"message": str(e)}})
        except WebSocketDisconnect:
            logger.info("realtime client disconnected")
        except Exception:  # noqa: BLE001
            logger.exception("realtime session failed")
        finally:
            await conn.release()
            # Let the events of utterances finished during release() go out if the socket lives.
            await asyncio.sleep(0)
            pump.cancel()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=config.HOST, port=config.PORT, workers=1, ws_max_size=16 * 1024 * 1024)
