"""
Mistral Voxtral Mini 4B Realtime (mistralai/Voxtral-Mini-4B-Realtime-2602) through transformers.

The streaming backend: a causal audio encoder and a Mistral decoder that emit exactly one token
per 80 ms of audio, so text appears ~0.5 s behind the speaker instead of after the utterance.

WHERE THE TIME CODES COME FROM
------------------------------
vLLM's realtime API returns Voxtral's text without any timing (vllm-project/vllm#39735; the
segment-timestamp PR #50783 is unmerged as of 2026-09). The timing is nevertheless in the token
stream, because the decoder never skips a frame. Generated token k (0-based, after the prompt)
is the decoder's output for audio frame k, and every frame produces one of:

  [STREAMING_PAD]    nothing new was said
  [STREAMING_WORD]   a word boundary follows
  a text token       the next piece of text

Measured on tests/data/de_timeline.flac against Whisper's DTW word timings, the **first text
token of a word marks that word's end**: end = (k - 1) * 80 ms, within about +/-0.1 s for every
one of the 35 words. Continuation tokens of the same word ("Strom" "aus" "fälle") follow in the
next frames and do not move it. So word *ends* are measured; word *starts* are not - a word
starts where the previous one ended, or, after a pause, one estimated word length before its end
(`_estimate_duration`). tests/test_live.py checks both against Whisper on every run.

`ASR_VOXTRAL_TIME_SHIFT_MS` adds a constant to every time, for recalibrating against a different
checkpoint without a code change.
"""

from __future__ import annotations

import os
import re
from typing import Callable, List, Optional

import numpy as np

from .. import config
from ..timecodes import Transcript, Word, WordBuilder, segments_from_words
from .base import SAMPLE_RATE, Backend

_CONTROL = re.compile(r"^\[[A-Z_]+\]$")
TIME_SHIFT_S = float(os.environ.get("ASR_VOXTRAL_TIME_SHIFT_MS", "0")) / 1000.0


MAX_WORD_S = 1.2


def _estimate_duration(text: str) -> float:
    """A German word's spoken length from its spelling: ~60 ms per letter, 0.15 - 1.2 s."""
    letters = sum(c.isalnum() for c in text)
    return min(MAX_WORD_S, max(0.15, 0.06 * letters + 0.08))


class TokenTimer:
    """
    Turn Voxtral's per-frame token stream into timed words.

    `step(k, piece)` is called for every generated token, control tokens included (pass piece=None
    for those) - the frame index is the clock, so every step counts. It returns the words that
    became complete.
    """

    def __init__(self, frame_s: float, shift_s: float = TIME_SHIFT_S):
        self.frame_s = frame_s
        self.shift_s = shift_s
        # A continuation token arrives in the frames right after its word's first token; three
        # quiet frames mean the word is finished.
        self.builder = WordBuilder(settle=3 * frame_s)
        self.prev_end = 0.0
        self.current_end: Optional[float] = None

    def time_of(self, k: int) -> float:
        return max(0.0, (k - 1) * self.frame_s + self.shift_s)

    def clock(self, k: int) -> Optional[float]:
        """
        The pause clock after step k: the latest time by which the speaker has *certainly* not
        started another word. A word's first token arrives when the word ends, so at frame k a
        word may be in progress that began up to MAX_WORD_S ago and will only be emitted later -
        measured from time_of(k) itself, a long word ("uninteressantesten", 0.9 s) looks exactly
        like a pause. None while a word is still arriving.
        """
        if self.builder.current is not None:
            return None
        return self.time_of(k) - MAX_WORD_S

    def step(self, k: int, piece: Optional[str]) -> List[Word]:
        now = self.time_of(k)
        done: List[Word] = []
        if piece and piece.strip():
            starts_word = piece[0].isspace() or self.current_end is None
            if starts_word:
                self.current_end = now
            done += self.builder.push(piece, self.current_end, self.current_end)
        else:
            done += self.builder.tick(now)
        return self._assign_starts(done)

    def finish(self) -> List[Word]:
        return self._assign_starts(self.builder.finish())

    def _assign_starts(self, words: List[Word]) -> List[Word]:
        for w in words:
            w.start = max(self.prev_end, w.end - _estimate_duration(w.text))
            w.start = min(w.start, w.end)
            self.prev_end = w.end
        return words


class _StepStreamer:
    """generate()'s streamer hook: the first put() is the prompt, every later one is one frame."""

    def __init__(self, on_id: Callable[[int], None]):
        self.on_id = on_id
        self.prompt_seen = False

    def put(self, value):
        if not self.prompt_seen:
            self.prompt_seen = True
            return
        for tid in value.reshape(-1).tolist():
            self.on_id(int(tid))

    def end(self):
        pass


class VoxtralBackend(Backend):
    name = "voxtral"
    streaming = True

    def __init__(self):
        super().__init__()
        self._model = None
        self._processor = None
        self._pieces = {}
        self.frame_s = 0.08

    @property
    def model_id(self) -> str:
        return config.VOXTRAL_MODEL

    def _load(self):
        import torch
        from transformers import VoxtralRealtimeForConditionalGeneration, VoxtralRealtimeProcessor

        processor = VoxtralRealtimeProcessor.from_pretrained(config.VOXTRAL_MODEL)
        if processor.feature_extractor.sampling_rate != SAMPLE_RATE:
            raise RuntimeError(f"model expects {processor.feature_extractor.sampling_rate} Hz audio")
        model = VoxtralRealtimeForConditionalGeneration.from_pretrained(
            config.VOXTRAL_MODEL, dtype=getattr(torch, config.VOXTRAL_DTYPE), device_map=config.VOXTRAL_DEVICE
        )
        model.eval()
        self._processor, self._model = processor, model
        self.frame_s = processor.raw_audio_length_per_tok / SAMPLE_RATE

    def info(self) -> dict:
        d = super().info()
        d.update(device=config.VOXTRAL_DEVICE, dtype=config.VOXTRAL_DTYPE, frame_ms=round(self.frame_s * 1000))
        return d

    @property
    def pad_samples(self) -> int:
        """
        Silence the model has to be fed after the last word of an utterance before it emits it:
        the encoder's right padding plus the transcription delay, plus one frame.
        """
        p = self._processor
        return (p.num_right_pad_tokens + p.num_delay_tokens + 1) * p.raw_audio_length_per_tok

    def piece(self, tid: int) -> Optional[str]:
        """Decoded text of one token, or None for control tokens. Cached - decode is not free."""
        if tid not in self._pieces:
            text = self._processor.tokenizer.decode([tid])
            self._pieces[tid] = None if (not text or _CONTROL.match(text.strip())) else text
        return self._pieces[tid]

    def _gen_kwargs(self) -> dict:
        return dict(do_sample=False, temperature=None, top_p=None, top_k=None)

    # -- offline --------------------------------------------------------------
    def _transcribe(self, audio: np.ndarray, language: Optional[str]) -> Transcript:
        import torch

        p, m = self._processor, self._model
        duration = len(audio) / SAMPLE_RATE
        x = np.pad(audio, (0, p.num_right_pad_tokens * p.raw_audio_length_per_tok))
        inputs = p(x, return_tensors="pt").to(m.device, dtype=m.dtype)
        frames = len(x) // p.raw_audio_length_per_tok
        with torch.no_grad():
            out = m.generate(**inputs, max_new_tokens=frames + 64, **self._gen_kwargs())
        generated = out[0, inputs["input_ids"].shape[1] :].tolist()
        timer = TokenTimer(self.frame_s)
        words: List[Word] = []
        for k, tid in enumerate(generated):
            words += timer.step(k, self.piece(tid))
        words += timer.finish()
        return Transcript(segments_from_words(words), None, duration, self.model_id)

    # -- streaming ------------------------------------------------------------
    def stream(
        self,
        take: Callable[[int, int], Optional[np.ndarray]],
        on_token: Callable[[str, float], None],
        on_words: Callable[[List[Word], float], None],
    ) -> None:
        """
        Decode one utterance while its audio is still arriving.

        `take(start, length)` blocks until `length` samples at `start` (utterance-relative) are
        available and returns them - zero-filled past the end of a closed utterance, None once it
        is exhausted, which ends the run. `on_token(piece, t)` sees every text token the moment it
        is decoded (t = the end of the word it belongs to); `on_words(words, now)` receives words
        as they complete, and is called once per frame so the caller's clock advances through
        pauses (`now` is `TokenTimer.clock`: None while a word is still arriving). Holds the
        backend lock for the whole utterance.
        """
        import torch

        self.ensure_loaded()
        p, m = self._processor, self._model
        hop = p.feature_extractor.hop_length
        win = p.feature_extractor.win_length

        with self.lock:
            first = take(0, p.num_samples_first_audio_chunk)
            if first is None:
                return
            first_inputs = p(first, is_streaming=True, is_first_audio_chunk=True, return_tensors="pt").to(
                m.device, dtype=m.dtype
            )

            def features():
                yield first_inputs.input_features
                mel_frame_idx = p.num_mel_frames_first_audio_chunk
                while True:
                    start = mel_frame_idx * hop - win // 2
                    chunk = take(start, p.num_samples_per_audio_chunk)
                    if chunk is None:
                        return
                    inputs = p(chunk, is_streaming=True, is_first_audio_chunk=False, return_tensors="pt").to(
                        m.device, dtype=m.dtype
                    )
                    yield inputs.input_features
                    mel_frame_idx += p.audio_length_per_tok

            timer = TokenTimer(self.frame_s)
            k = [0]

            def on_id(tid: int):
                piece = self.piece(tid)
                step = k[0]
                k[0] += 1
                if piece and piece.strip():
                    starts_word = piece[0].isspace() or timer.current_end is None
                    on_token(piece, timer.time_of(step) if starts_word else timer.current_end)
                on_words(timer.step(step, piece), timer.clock(step))

            with torch.no_grad():
                m.generate(
                    input_ids=first_inputs.input_ids,
                    input_features=features(),
                    num_delay_tokens=first_inputs.num_delay_tokens,
                    streamer=_StepStreamer(on_id),
                    **self._gen_kwargs(),
                )
            on_words(timer.finish(), timer.time_of(k[0]))
