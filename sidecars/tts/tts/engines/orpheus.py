"""
Orpheus-3B / Kartoffel (German engine A) — a Llama-3.2 backbone that emits SNAC audio tokens.

This engine is the reason the sidecar exists at all: Orpheus does not produce audio, it produces
*audio tokens*. vLLM or llama.cpp can run the language model perfectly well, but something still
has to de-interleave the 7-token SNAC frames and run `SNAC.decode()` to get a waveform. **That
decode always happens here**, in both backends. With `ORPHEUS_BACKEND=vllm` this engine does
nothing else: vLLM generates, this process turns tokens into sound.

Invariants that produce noise instead of speech when broken:

1. **The token layout is a contract with the checkpoint, not a preference.** `TOK_AUDIO_BASE`,
   `CODEBOOK_SIZE`, `FRAME` and the 1/2/3/3/2/3/3 interleave must match what the checkpoint was
   trained to emit. Change them only together with the checkpoint, never to "make the range check
   pass".
2. **The range check in `to_snac_layers` is the tripwire — keep it.** Every de-interleaved code
   must land in `[0, 4096)`. When it does not, the layout and the checkpoint disagree, and the only
   honest outcome is an error. Clamping or `% 4096` would turn a loud failure into audible garbage.
3. **Both backends must produce the same code values.** The transformers path computes
   `id - 128266`; the vLLM path parses `<custom_token_N>` and computes `N - 10`. These agree
   *because* `custom_token_N` has id `128256 + N`. Touch one mapping and re-derive the other.
4. **A partial trailing frame is dropped, never padded.** Padding injects codes the model never
   emitted. Fewer than one whole frame is an error, not empty audio.
"""

import json
import logging
import queue
import re
import threading
from typing import Iterator, List

import httpx
from fastapi import HTTPException

from .. import config
from .base import Engine

logger = logging.getLogger("tts.engine.orpheus")

# Orpheus SNAC token layout.
TOK_START_HUMAN = 128259  # <|begin_of_human|>
TOK_END_TEXT = 128009     # <|eot_id|>
TOK_END_HUMAN = 128260    # <|end_of_human|>
TOK_START_AUDIO = 128257  # <|begin_of_audio|>
TOK_END_AUDIO = 128258    # <|end_of_audio|> (also the generation EOS)
TOK_AUDIO_BASE = 128266   # first SNAC code token
CODEBOOK_SIZE = 4096
FRAME = 7                 # SNAC codes per frame, interleaved 1/2/3/3/2/3/3

#: The generation budget, and what it means in seconds: `max_new_tokens // FRAME` frames, each
#: 2048 samples at 24 kHz. Both backends pass the same number, so it is named once.
MAX_NEW_TOKENS = 4096
MAX_SECONDS = (MAX_NEW_TOKENS // FRAME) * 2048 / 24000   # 49.92

CUSTOM_TOKEN_RE = re.compile(r"<custom_token_(\d+)>")

#: Multi-speaker Kartoffel speakers — a convention of the checkpoint's training data rather than
#: an enumerable property of it, so this list informs but never rejects.
KARTOFFEL_VOICES = ["Jakob", "Anton", "Julian", "Sophie", "Marie"]
#: The ungated Apache-2.0 alternative is single-speaker.
THORSTEN_REPO = "Thorsten-Voice/tv-orpheus-v1"
THORSTEN_VOICES = ["thorsten"]


# --------------------------------------------------------------------------- #
# SNAC decode — shared by both backends
# --------------------------------------------------------------------------- #
def to_snac_layers(codes: List[int]):
    """De-interleave flat SNAC codes into the three hierarchical codebooks."""
    import torch

    usable = (len(codes) // FRAME) * FRAME
    if usable == 0:
        raise RuntimeError(
            f"Orpheus emitted {len(codes)} audio tokens, fewer than one {FRAME}-token frame")
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
                f"SNAC code out of range [{layer.min()}, {layer.max()}] — token layout mismatch")
    return layers


class TokenStreamer:
    """A `transformers` streamer that hands sampled token ids to the consuming thread.

    The streamer protocol is two methods: `put` once per generation step and `end` when generation
    stops. The FIRST `put` carries the prompt rather than a generated token — every streamer
    shipped with transformers skips it, and so does this one.

    Raw ids, not text: the built-in `TextIteratorStreamer` would decode each audio token to
    `<custom_token_N>` only for us to parse the number back out, and the id IS the number.
    """

    def __init__(self):
        self._queue = queue.Queue()
        self._prompt_seen = False

    def put(self, value):
        if not self._prompt_seen:
            self._prompt_seen = True
            return
        for token_id in value.reshape(-1).tolist():
            self._queue.put(token_id)

    def end(self):
        self._queue.put(None)

    def __iter__(self):
        return self

    def __next__(self):
        token_id = self._queue.get()
        if token_id is None:
            raise StopIteration
        return token_id


class OrpheusEngine(Engine):
    name = "orpheus"
    languages = ("de",)
    sample_rate = 24000
    #: The default checkpoint is HF-gated and not commercially clean; `Thorsten-Voice/tv-orpheus-v1`
    #: is Apache-2.0 and ungated (single speaker).
    licence = "gated, non-commercial (Apache-2.0 with ORPHEUS_REPO_DE=Thorsten-Voice/tv-orpheus-v1)"
    commercial = False
    max_seconds = MAX_SECONDS
    incremental = True

    def __init__(self):
        super().__init__()
        self._model = None
        self._tokenizer = None
        self._snac = None

    # -- catalogue --------------------------------------------------------------
    @property
    def backend(self) -> str:
        return config.ORPHEUS_BACKEND

    @property
    def repo(self) -> str:
        return config.ORPHEUS_REPO_DE

    @property
    def chunk_chars(self) -> int:
        return config.TTS_CHUNK_CHARS

    @property
    def loaded(self) -> bool:
        # With the vLLM backend there is nothing local to load but the SNAC decoder, and the LM
        # is somebody else's process — so "loaded" means "ready to serve", which it is.
        return self._model is not None or config.ORPHEUS_BACKEND == "vllm"

    def voices(self) -> List[str]:
        """The speaker names the configured checkpoint was trained on.

        Derived from the checkpoint id, not hard-coded per request: the old static list advertised
        six German voices even for the single-speaker Thorsten checkpoint.
        """
        return THORSTEN_VOICES if config.ORPHEUS_REPO_DE == THORSTEN_REPO else KARTOFFEL_VOICES

    # -- loading ----------------------------------------------------------------
    def _load(self):
        if self._model is not None:
            return
        with self._load_lock:
            if self._model is not None:
                return
            import torch
            from transformers import AutoModelForCausalLM, AutoTokenizer

            logger.info("Loading Orpheus checkpoint %s on %s ...",
                        config.ORPHEUS_REPO_DE, config.DEVICE)
            tokenizer = AutoTokenizer.from_pretrained(config.ORPHEUS_REPO_DE)
            model = AutoModelForCausalLM.from_pretrained(config.ORPHEUS_REPO_DE,
                                                         dtype=torch.bfloat16)
            self._tokenizer = tokenizer
            # Assigned last: a failed download leaves `None` and the next request retries.
            self._model = model.to(config.DEVICE).eval()

    def _snac_decode(self, codes: List[int]):
        """Decode raw SNAC code values into a `(waveform, sample_rate)` pair.

        Returns the waveform rather than encoded bytes so the container stays a request-level
        decision. 24 000 Hz is a property of the SNAC checkpoint — swapping `SNAC_REPO` for a
        32 kHz variant means changing `sample_rate` here.
        """
        import torch
        from snac import SNAC

        if self._snac is None:
            logger.info("Loading SNAC decoder %s on %s ...", config.SNAC_REPO, config.DEVICE)
            self._snac = SNAC.from_pretrained(config.SNAC_REPO).eval().to(config.DEVICE)

        layers = [layer.to(config.DEVICE) for layer in to_snac_layers(codes)]
        with torch.inference_mode():
            audio = self._snac.decode(layers)
        return audio.squeeze().float().cpu().numpy(), self.sample_rate

    def _snac_decode_stream(self, codes: Iterator[int]):
        """Decode SNAC codes AS THEY ARRIVE, yielding `(waveform, rate)` pieces.

        `_snac_decode` needs every code before it can produce a single sample, which is what puts
        a whole generation between the request and the first sound. This decodes a sliding window
        instead, so audio leaves while the model is still generating.

        **Every frame is emitted exactly once and in order**, so the pieces concatenate into the
        same frame sequence a one-shot decode would return. What differs is the context each frame
        was decoded with — SNAC is convolutional, and a frame at the edge of a window has less of
        its neighbourhood available. That is the entire trade, and it is why the window is not one
        frame wide:

            window 0 = frames [0 .. w-1]   -> emit frames 0 and 1  (nothing precedes them)
            window k = frames [k .. k+w-1] -> emit frame k+1       (one frame of left context,
                                                                    w-2 frames of right)
            flush                          -> emit the final window's last w-2 frames

        The cost is that each frame is decoded `window` times instead of once, so SNAC does ~4x
        the work for the same audio. That is affordable only because SNAC is the cheap half — the
        Llama-3.2 backbone is what the 1.7x real time is made of.
        """
        # Below two the emission arithmetic has no middle to emit and the first window would claim
        # frames it does not contain.
        window = max(2, config.TTS_STREAM_WINDOW_FRAMES)
        buf: List[int] = []
        audio = None
        rate = 0
        per_frame = 0
        emitted = 0

        for code in codes:
            buf.append(code)
            if len(buf) % FRAME or len(buf) < window * FRAME:
                continue
            audio, rate = self._snac_decode(buf[-window * FRAME:])
            # Asked of the decoder rather than assumed from FRAME/2048, so a SNAC checkpoint with
            # a different hop does not silently emit overlapping or gapped audio.
            per_frame = len(audio) // window
            if emitted == 0:
                yield audio[:2 * per_frame], rate
                emitted = 2
            else:
                yield audio[per_frame:2 * per_frame], rate
                emitted += 1

        if audio is None:
            # Never reached a full window: this generation is shorter than the context we wanted,
            # so decode it in one go and stream nothing. Short is not an error — too short for one
            # FRAME is, and `to_snac_layers` says so.
            audio, rate = self._snac_decode(buf)
            yield audio, rate
            return

        # The tail of the final window has only ever served as right-context for others.
        if len(audio) > 2 * per_frame:
            yield audio[2 * per_frame:], rate

    # -- the prompt -------------------------------------------------------------
    @staticmethod
    def prompt_text(text: str, voice: str) -> str:
        """Multi-speaker Orpheus prompt framing: ``{voice}: {text}``.

        Both backends must agree on it: `transformers` tokenizes this and wraps it in raw token
        ids, `vllm` sends the literal `<|begin_of_human|>…<|eot_id|><|end_of_human|>` string. A
        change here is a change in both.
        """
        return f"{voice}: {text}"

    def _warn_unknown_voice(self, voice: str):
        """Warned, never rejected.

        The name is interpolated into the prompt, so Orpheus accepts anything and merely degrades;
        the speaker list is a convention of the training data, not an enumerable property of the
        checkpoint, and a 400 here would refuse a Kartoffel speaker this file happens not to name.
        English is different — there the list comes out of the voices file and is authoritative.
        """
        if voice not in self.voices():
            logger.warning("unknown German voice %r for %s — synthesising anyway, quality may "
                           "degrade", voice, config.ORPHEUS_REPO_DE)

    # -- generation -------------------------------------------------------------
    def _generate(self, text: str, voice: str):
        self._warn_unknown_voice(voice)
        if config.ORPHEUS_BACKEND == "vllm":
            return self._generate_vllm(text, voice)
        return self._generate_transformers(text, voice)

    def _generate_transformers(self, text: str, voice: str):
        import torch

        self._load()
        ids = self._tokenizer(self.prompt_text(text, voice), return_tensors="pt").input_ids
        start = torch.tensor([[TOK_START_HUMAN]], dtype=torch.int64)
        end = torch.tensor([[TOK_END_TEXT, TOK_END_HUMAN]], dtype=torch.int64)
        input_ids = torch.cat([start, ids, end], dim=1).to(config.DEVICE)

        with torch.inference_mode():
            generated = self._model.generate(
                input_ids,
                attention_mask=torch.ones_like(input_ids),
                max_new_tokens=MAX_NEW_TOKENS,
                do_sample=True,
                temperature=0.6,
                top_p=0.95,
                repetition_penalty=1.1,
                eos_token_id=TOK_END_AUDIO,
                pad_token_id=TOK_END_AUDIO,
            )

        row = generated[0].tolist()
        # No end-of-audio token means the budget ran out rather than the sentence ending: the
        # audio stops mid-word. Chunking is what normally prevents it, so reaching here means a
        # single chunk was still too long — say so, because the bytes never will.
        if TOK_END_AUDIO not in row[input_ids.shape[1]:]:
            logger.warning("Orpheus hit max_new_tokens=%d (~%.1f s) without an end-of-audio token "
                           "— this chunk is cut off mid-word: %r", MAX_NEW_TOKENS, MAX_SECONDS,
                           text[:80])
        if TOK_START_AUDIO in row:
            row = row[len(row) - 1 - row[::-1].index(TOK_START_AUDIO) + 1:]
        codes = [t - TOK_AUDIO_BASE for t in row if t != TOK_END_AUDIO and t >= TOK_AUDIO_BASE]
        return self._snac_decode(codes)

    def _generate_vllm(self, text: str, voice: str):
        """Generate SNAC tokens on a vLLM/llama.cpp OpenAI-compatible endpoint, decode here.

        vLLM returns the audio tokens as ``<custom_token_N>`` strings in the completion text; we
        map them back to raw SNAC code values (`N - 10`, the Orpheus tokenizer's custom-token
        offset) and decode locally.
        """
        with httpx.Client(timeout=120.0) as client:
            resp = client.post(self._vllm_url(), json=self._vllm_payload(text, voice))
            if resp.status_code != 200:
                raise HTTPException(status_code=502,
                                    detail=f"Orpheus vLLM backend error: {resp.text[:500]}")
            choice = resp.json()["choices"][0]
            completion = choice["text"]

        # The vLLM side of the same truncation check: `length` means the budget ended the
        # generation, `stop` means <|end_of_audio|> did.
        if choice.get("finish_reason") == "length":
            logger.warning("Orpheus vLLM hit max_tokens=%d (~%.1f s) — this chunk is cut off "
                           "mid-word: %r", MAX_NEW_TOKENS, MAX_SECONDS, text[:80])

        codes = [int(n) - 10 for n in CUSTOM_TOKEN_RE.findall(completion)]
        return self._snac_decode([c for c in codes if c >= 0])

    def _vllm_url(self) -> str:
        return f"{config.ORPHEUS_LLM_ENDPOINT}/v1/completions"

    def _vllm_payload(self, text: str, voice: str, stream: bool = False) -> dict:
        prompt = (f"<|begin_of_human|>{self.prompt_text(text, voice)}"
                  f"<|eot_id|><|end_of_human|>")
        payload = {
            "model": config.ORPHEUS_REPO_DE_MODEL,
            "prompt": prompt,
            "max_tokens": MAX_NEW_TOKENS,
            "temperature": 0.6,
            "top_p": 0.95,
            "stop": ["<|end_of_audio|>"],
        }
        if stream:
            payload["stream"] = True
        return payload

    # -- incremental generation -------------------------------------------------
    def stream(self, text: str, voice: str):
        """One generation, streamed. Yields `(waveform, rate)` pieces as they decode.

        With `TTS_STREAM_INCREMENTAL` off it yields the whole generation as a single piece. The
        response still streams — at chunk granularity, which is what the chunk loop gives it — but
        nothing is asked of the engine. That switch exists because the incremental path is the one
        part of this sidecar that has never run against a live GPU.

        No edge trim and no headroom stage here: a piece from inside one generation would be cut
        at a word rather than at the edge of the speech, and the pieces are too short for a gain
        that does not pump. Measured peaks on this path are at most 0.94.
        """
        if not config.TTS_STREAM_INCREMENTAL:
            yield self.generate(text, voice)
            return
        self._warn_unknown_voice(voice)
        with self.lock:
            codes = (self._codes_vllm(text, voice) if config.ORPHEUS_BACKEND == "vllm"
                     else self._codes_transformers(text, voice))
            yield from self._snac_decode_stream(codes)

    def _codes_transformers(self, text: str, voice: str) -> Iterator[int]:
        """Yield SNAC code values as the in-process model samples them.

        `model.generate` has no incremental return value, so it runs on its own thread and posts
        each step through `TokenStreamer`. An exception there would otherwise be swallowed with the
        thread, so it is captured and re-raised on THIS thread, where the request can still turn it
        into a 500.

        If the caller disconnects, this generator is abandoned and the thread runs to completion,
        filling the queue with tokens nobody reads. That is bounded by `MAX_NEW_TOKENS` and one
        chunk of GPU time, so it is wasteful rather than dangerous — but it is why a cancellation
        path, if one is ever added, belongs here.
        """
        import torch

        self._load()
        ids = self._tokenizer(self.prompt_text(text, voice), return_tensors="pt").input_ids
        start = torch.tensor([[TOK_START_HUMAN]], dtype=torch.int64)
        end = torch.tensor([[TOK_END_TEXT, TOK_END_HUMAN]], dtype=torch.int64)
        input_ids = torch.cat([start, ids, end], dim=1).to(config.DEVICE)

        streamer = TokenStreamer()
        failure: List[BaseException] = []

        def _run():
            try:
                with torch.inference_mode():
                    self._model.generate(
                        input_ids,
                        attention_mask=torch.ones_like(input_ids),
                        max_new_tokens=MAX_NEW_TOKENS,
                        do_sample=True,
                        temperature=0.6,
                        top_p=0.95,
                        repetition_penalty=1.1,
                        eos_token_id=TOK_END_AUDIO,
                        pad_token_id=TOK_END_AUDIO,
                        streamer=streamer,
                    )
            except BaseException as e:  # noqa: BLE001 — re-raised below, on the consumer
                failure.append(e)
            finally:
                streamer.end()

        thread = threading.Thread(target=_run, name="orpheus-generate", daemon=True)
        thread.start()

        steps = 0
        ended = False
        for token_id in streamer:
            steps += 1
            if token_id == TOK_END_AUDIO:
                ended = True
                break
            # The blocking path slices at the LAST <|begin_of_audio|>; a stream cannot look ahead
            # for it, so it filters on the id range instead. The two agree because every id at or
            # above TOK_AUDIO_BASE is an audio code and no other token is.
            if token_id >= TOK_AUDIO_BASE:
                yield token_id - TOK_AUDIO_BASE

        thread.join()
        if failure:
            raise failure[0]
        if not ended:
            logger.warning("Orpheus hit max_new_tokens=%d (~%.1f s) without an end-of-audio token "
                           "after %d steps — this chunk is cut off mid-word: %r",
                           MAX_NEW_TOKENS, MAX_SECONDS, steps, text[:80])

    def _codes_vllm(self, text: str, voice: str) -> Iterator[int]:
        """Yield SNAC code values as the vLLM/llama.cpp endpoint streams them back.

        Same request as the blocking path with `stream: true`, so the tokens arrive as SSE deltas
        of completion text instead of one finished string. The only new hazard is that a delta can
        end in the MIDDLE of a `<custom_token_N>`: scanning it would either miss the token or,
        worse, read a truncated number as a valid code. So only the text up to the last `>` is
        ever scanned and the remainder waits for the next delta.
        """
        finish_reason = None
        with httpx.Client(timeout=120.0) as client:
            with client.stream("POST", self._vllm_url(),
                               json=self._vllm_payload(text, voice, stream=True)) as resp:
                if resp.status_code != 200:
                    resp.read()   # a streamed body is not loaded until asked for
                    raise HTTPException(status_code=502,
                                        detail=f"Orpheus vLLM backend error: {resp.text[:500]}")
                pending = ""
                for line in resp.iter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[len("data:"):].strip()
                    if data == "[DONE]":
                        break
                    try:
                        choice = json.loads(data)["choices"][0]
                    except (ValueError, KeyError, IndexError):
                        logger.warning("unparsable SSE line from the vLLM backend: %r", line[:120])
                        continue
                    finish_reason = choice.get("finish_reason") or finish_reason
                    pending += choice.get("text") or ""
                    cut = pending.rfind(">")
                    if cut < 0:
                        continue
                    head, pending = pending[:cut + 1], pending[cut + 1:]
                    for n in CUSTOM_TOKEN_RE.findall(head):
                        code = int(n) - 10
                        if code >= 0:
                            yield code

        if finish_reason == "length":
            logger.warning("Orpheus vLLM hit max_tokens=%d (~%.1f s) — this chunk is cut off "
                           "mid-word: %r", MAX_NEW_TOKENS, MAX_SECONDS, text[:80])
