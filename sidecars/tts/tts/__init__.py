"""
The TTS sidecar's internals. `server.py` next to this package is the HTTP surface.

| Module | What |
|---|---|
| `config` | every environment variable, read once at import |
| `engines/` | one module per speech model, behind a small `Engine` interface, plus the registry |
| `chunking` | text -> chunks short enough for one generation (sentence/clause/word seams) |
| `audio` | the ONE place a waveform becomes bytes, and the waveform stages around it |
| `postfx` | the optional post-production chain (EQ, RMS compressor, loudness, limiter) |
| `synth` | the engine-agnostic chunk loop for a file and for a stream |

Everything reads its settings as `config.NAME` rather than importing the value, so a test (and
only a test) can change one without reloading the process.
"""
