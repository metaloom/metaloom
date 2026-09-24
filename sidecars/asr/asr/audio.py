"""
Audio in: whatever the caller uploads, out as 16 kHz mono float32.

libsndfile reads WAV, FLAC, OGG/Opus and MP3. Anything else - above all the video containers a
Loom asset usually is (mp4, mkv, webm, mov) - goes through ffmpeg, which the container image
ships. Realtime audio is always raw PCM16 LE mono 16 kHz, as in the OpenAI/vLLM realtime API.
"""

from __future__ import annotations

import io
import shutil
import subprocess
import tempfile

import numpy as np

SAMPLE_RATE = 16000


class AudioDecodeError(ValueError):
    pass


def decode(data: bytes) -> np.ndarray:
    """Decode an uploaded file to 16 kHz mono float32."""
    if not data:
        raise AudioDecodeError("empty upload")
    try:
        import soundfile as sf

        audio, rate = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
        return resample(audio.mean(axis=1), rate)
    except Exception as sf_error:  # noqa: BLE001 - fall through to ffmpeg for any container
        if shutil.which("ffmpeg") is None:
            raise AudioDecodeError(f"could not decode audio ({sf_error}) and ffmpeg is not installed") from sf_error
        return _ffmpeg(data)


def _ffmpeg(data: bytes) -> np.ndarray:
    # A temp file, not stdin: an mp4 written the usual way keeps its index (moov) at the END, and
    # ffmpeg cannot seek back to it on a pipe ("partial file", "Invalid data found").
    with tempfile.NamedTemporaryFile(suffix=".media") as f:
        f.write(data)
        f.flush()
        proc = subprocess.run(
            ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", f.name, "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "pipe:1"],
            capture_output=True,
            check=False,
        )
    if proc.returncode != 0 or not proc.stdout:
        raise AudioDecodeError(f"ffmpeg could not decode audio: {proc.stderr.decode(errors='replace').strip()[:500]}")
    return np.frombuffer(proc.stdout, dtype="<f4").astype(np.float32)


def resample(audio: np.ndarray, rate: int) -> np.ndarray:
    audio = np.asarray(audio, dtype=np.float32)
    if rate == SAMPLE_RATE:
        return audio
    import soxr

    return soxr.resample(audio, rate, SAMPLE_RATE).astype(np.float32)


def pcm16_to_float(raw: bytes) -> np.ndarray:
    if len(raw) % 2:
        raw = raw[:-1]
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0


def float_to_pcm16(audio: np.ndarray) -> bytes:
    return (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
