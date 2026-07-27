"""
Sentiment sidecar — FastAPI HTTP server for the Cortex `sentiment` node.

One endpoint, `POST /v1/sentiment`, routes by language:

  * lang="de"  → oliverguhr/german-sentiment-bert                          (MIT)
  * lang="en"  → cardiffnlp/twitter-roberta-base-sentiment-latest          (CC-BY-4.0)
  * otherwise  → lxyuan/distilbert-base-multilingual-cased-sentiments-student (Apache-2.0)

All three are 3-class encoders, so their native label vocabularies normalise onto
one schema: POSITIVE | NEUTRAL | NEGATIVE plus a signed
`polarity = p(positive) - p(negative)` in [-1, 1].

Three responsibilities live here rather than in the Java node, so that swapping a
checkpoint never touches Java:

  1. Language routing — `lang="auto"` detects with lingua, constrained to
     SENTIMENT_LANGS.
  2. Chunking — every candidate is a 512-token encoder, but `tika_content` for a
     PDF is routinely far longer. Text is split on sentence boundaries into
     <= MAX_CHUNK_TOKENS word-piece chunks, each chunk is classified, and the
     per-chunk distributions are aggregated length-weighted into one document
     label.
  3. Label normalisation — see LABEL_ALIASES below.

The Java `SentimentNode` is a pure HTTP client of this server
(io.metaloom.cortex.node.sentiment).

Run:
  uvicorn server:app --host 0.0.0.0 --port 9110

Environment variables:
  SENTIMENT_MODEL_DE        German checkpoint      (default: oliverguhr/german-sentiment-bert)
  SENTIMENT_MODEL_EN        English checkpoint     (default: cardiffnlp/twitter-roberta-base-sentiment-latest)
  SENTIMENT_MODEL_FALLBACK  any other language     (default: lxyuan/distilbert-base-multilingual-cased-sentiments-student)
  SENTIMENT_LANGS           auto-detect candidates (default: de,en)
  MAX_CHUNK_TOKENS          word-pieces per chunk  (default: 400)
  MAX_CHUNKS                chunks per text        (default: 64)
  DEVICE                    torch device           (default: cuda if available else cpu)

Licence note: the English default is CC-BY-4.0 — commercial use is allowed but
requires attribution. The model id travels back in every response so the node can
record it. Deployments that cannot carry the attribution should set
SENTIMENT_MODEL_EN to the Apache-2.0 fallback model.
"""

import os
import re
import logging
from typing import Dict, List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("sentiment-server")

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
MODEL_DE = os.environ.get("SENTIMENT_MODEL_DE", "oliverguhr/german-sentiment-bert")
MODEL_EN = os.environ.get(
    "SENTIMENT_MODEL_EN", "cardiffnlp/twitter-roberta-base-sentiment-latest"
)
MODEL_FALLBACK = os.environ.get(
    "SENTIMENT_MODEL_FALLBACK",
    "lxyuan/distilbert-base-multilingual-cased-sentiments-student",
)
LANGS = [s.strip() for s in os.environ.get("SENTIMENT_LANGS", "de,en").split(",") if s.strip()]
MAX_CHUNK_TOKENS = int(os.environ.get("MAX_CHUNK_TOKENS", "400"))
MAX_CHUNKS = int(os.environ.get("MAX_CHUNKS", "64"))


def _default_device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("DEVICE") or _default_device()

POSITIVE = "POSITIVE"
NEUTRAL = "NEUTRAL"
NEGATIVE = "NEGATIVE"

# Every native label any of the supported checkpoints can emit, lowercased.
# german-sentiment-bert -> positive/negative/neutral
# twitter-roberta-*-latest -> negative/neutral/positive (also LABEL_0..2 on older revs)
# distilbert-*-sentiments-student -> positive/neutral/negative
LABEL_ALIASES: Dict[str, str] = {
    "positive": POSITIVE, "pos": POSITIVE, "label_2": POSITIVE, "5 stars": POSITIVE, "4 stars": POSITIVE,
    "neutral": NEUTRAL, "neu": NEUTRAL, "label_1": NEUTRAL, "3 stars": NEUTRAL,
    "negative": NEGATIVE, "neg": NEGATIVE, "label_0": NEGATIVE, "1 star": NEGATIVE, "2 stars": NEGATIVE,
}


# --------------------------------------------------------------------------- #
# Model registry — checkpoints load lazily, on first use of their language
# --------------------------------------------------------------------------- #
_pipelines: Dict[str, object] = {}
_detector = None


def _model_id(lang: str, overrides: Optional[Dict[str, str]] = None) -> str:
    """Resolve the checkpoint for `lang`. Per-request `overrides` (keyed by language) win over the env defaults."""
    if overrides:
        override = overrides.get(lang)
        if override:
            return override
    if lang == "de":
        return MODEL_DE
    if lang == "en":
        return MODEL_EN
    return MODEL_FALLBACK


def _pipeline(model_id: str):
    """Load (once) and return a top-k text-classification pipeline for `model_id`."""
    pipe = _pipelines.get(model_id)
    if pipe is None:
        from transformers import pipeline as hf_pipeline

        logger.info("Loading sentiment model %s on %s", model_id, DEVICE)
        pipe = hf_pipeline(
            "text-classification",
            model=model_id,
            top_k=None,  # return the full distribution, not just the argmax
            device=0 if DEVICE.startswith("cuda") else -1,
            truncation=True,
        )
        _pipelines[model_id] = pipe
    return pipe


def _detect_lang(text: str) -> str:
    """Detect the language, constrained to SENTIMENT_LANGS. Falls back to the first configured language."""
    global _detector
    if len(LANGS) == 1:
        return LANGS[0]
    if _detector is None:
        from lingua import LanguageDetectorBuilder, Language

        wanted = [lang for lang in Language if lang.iso_code_639_1.name.lower() in LANGS]
        _detector = LanguageDetectorBuilder.from_languages(*wanted).build()
    detected = _detector.detect_language_of(text)
    if detected is None:
        return LANGS[0]
    return detected.iso_code_639_1.name.lower()


# --------------------------------------------------------------------------- #
# Chunking
# --------------------------------------------------------------------------- #
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?…])\s+|\n{2,}")


def _chunk(text: str, tokenizer) -> List[str]:
    """
    Split `text` into chunks of at most MAX_CHUNK_TOKENS word-pieces, preferring
    sentence boundaries. A single sentence longer than the budget is hard-split on
    the tokenizer's own offsets so no text is silently dropped.
    """
    sentences = [s.strip() for s in _SENTENCE_SPLIT.split(text) if s.strip()]
    if not sentences:
        return []

    chunks: List[str] = []
    current: List[str] = []
    current_len = 0

    def token_len(s: str) -> int:
        return len(tokenizer.encode(s, add_special_tokens=False))

    for sentence in sentences:
        length = token_len(sentence)
        if length > MAX_CHUNK_TOKENS:
            # Flush what we have, then hard-split the oversized sentence on word boundaries.
            if current:
                chunks.append(" ".join(current))
                current, current_len = [], 0
            words = sentence.split()
            piece: List[str] = []
            for word in words:
                piece.append(word)
                if token_len(" ".join(piece)) >= MAX_CHUNK_TOKENS:
                    chunks.append(" ".join(piece))
                    piece = []
            if piece:
                chunks.append(" ".join(piece))
            continue

        if current_len + length > MAX_CHUNK_TOKENS and current:
            chunks.append(" ".join(current))
            current, current_len = [], 0
        current.append(sentence)
        current_len += length

    if current:
        chunks.append(" ".join(current))
    return chunks


# --------------------------------------------------------------------------- #
# Scoring
# --------------------------------------------------------------------------- #
def _normalise(raw) -> Dict[str, float]:
    """Map one pipeline result (a list of {label, score}) onto the POSITIVE/NEUTRAL/NEGATIVE schema."""
    scores = {POSITIVE: 0.0, NEUTRAL: 0.0, NEGATIVE: 0.0}
    for entry in raw:
        key = LABEL_ALIASES.get(str(entry["label"]).strip().lower())
        if key is None:
            logger.warning("Unmapped sentiment label %r — ignored", entry["label"])
            continue
        scores[key] += float(entry["score"])
    total = sum(scores.values())
    if total > 0:
        scores = {k: v / total for k, v in scores.items()}
    return scores


def _score_text(text: str, lang: str, overrides: Optional[Dict[str, str]] = None) -> dict:
    model_id = _model_id(lang, overrides)
    pipe = _pipeline(model_id)

    chunks = _chunk(text, pipe.tokenizer)
    truncated = False
    if len(chunks) > MAX_CHUNKS:
        logger.warning("Text produced %d chunks, truncating to %d", len(chunks), MAX_CHUNKS)
        chunks = chunks[:MAX_CHUNKS]
        truncated = True
    if not chunks:
        raise HTTPException(status_code=400, detail="text is empty")

    raw_results = pipe(chunks)
    # A single-element batch may come back unwrapped depending on the transformers version.
    if raw_results and isinstance(raw_results[0], dict):
        raw_results = [raw_results]

    # Length-weighted aggregation: a long chunk should dominate a one-liner.
    weights = [max(len(c), 1) for c in chunks]
    total_weight = sum(weights)
    agg = {POSITIVE: 0.0, NEUTRAL: 0.0, NEGATIVE: 0.0}
    for raw, weight in zip(raw_results, weights):
        for key, value in _normalise(raw).items():
            agg[key] += value * weight / total_weight

    label = max(agg, key=agg.get)
    return {
        "label": label,
        "score": round(agg[label], 6),
        "polarity": round(agg[POSITIVE] - agg[NEGATIVE], 6),
        "scores": {
            "positive": round(agg[POSITIVE], 6),
            "neutral": round(agg[NEUTRAL], 6),
            "negative": round(agg[NEGATIVE], 6),
        },
        "lang": lang,
        "model": model_id,
        "chunks": len(chunks),
        "truncated": truncated,
    }


# --------------------------------------------------------------------------- #
# HTTP API
# --------------------------------------------------------------------------- #
class SentimentRequest(BaseModel):
    texts: List[str]
    lang: Optional[str] = "auto"
    # Optional per-request checkpoint overrides keyed by language, e.g.
    # {"de": "scherrmann/GermanFinBert_SC_Sentiment"}. Absent keys fall back to
    # the SENTIMENT_MODEL_* env defaults. This is how the node's modelDe/modelEn
    # options reach the sidecar without giving up `lang="auto"` routing.
    models: Optional[Dict[str, str]] = None


app = FastAPI(title="Cortex sentiment sidecar")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": DEVICE,
        "models": {"de": MODEL_DE, "en": MODEL_EN, "fallback": MODEL_FALLBACK},
        "loaded": sorted(_pipelines.keys()),
    }


@app.post("/v1/sentiment")
def sentiment(request: SentimentRequest):
    if not request.texts:
        raise HTTPException(status_code=400, detail="texts must not be empty")

    requested = (request.lang or "auto").strip().lower()
    results = []
    for text in request.texts:
        if not text or not text.strip():
            raise HTTPException(status_code=400, detail="texts must not contain blank entries")
        lang = _detect_lang(text) if requested in ("", "auto") else requested
        results.append(_score_text(text, lang, request.models))
    return results
