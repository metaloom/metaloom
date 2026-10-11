"""
Text chunking — the ONE place long text becomes several generations.

Every engine here has a duration ceiling, and the ceiling is a property of the engine rather than
of the language. Orpheus emits at most 4096 SNAC tokens per pass (~50 s of audio); Kartoffelbox's
sampling loop stops at 1000 speech tokens (~40 s). A generation that runs into its ceiling returns
a **well-formed file cut off mid-word**, and nothing downstream can tell. So the length decision is
made here, on text, instead of being made implicitly by a sampler running out of budget.

Three seams, tried in order, so a break always lands at the least audible place available:
sentence end -> clause end (`,;:`) -> word boundary. **Never mid-word** — that is the whole point,
and the failure mode it replaces was mid-word by definition.
"""

import re
from typing import List

#: A sentence ends at .!?… followed by whitespace, or at a blank line. The punctuation stays with
#: the sentence (lookbehind), so re-joining the pieces reproduces the text.
SENTENCE_END_RE = re.compile(r"(?<=[.!?…])\s+|\n{2,}")
#: Inside an over-long sentence these are the next-best seams.
CLAUSE_END_RE = re.compile(r"(?<=[,;:])\s+")
#: German abbreviations whose period is NOT a sentence end. Deliberately short and therefore
#: incomplete: a missed one costs a pause in the wrong place, never a lost word, so the list is
#: worth having and not worth perfecting. A single letter ("z. B.") is caught by length.
ABBREVIATIONS = {
    "abs", "abb", "bspw", "bzw", "ca", "dr", "etc", "evtl", "ggf", "inkl", "mio", "mrd",
    "nr", "prof", "sog", "st", "tsd", "usw", "uvm", "vgl", "zb",
}


def looks_like_an_abbreviation(piece: str) -> bool:
    """True when `piece` ends in a period that is not a sentence end ("z.", "bzw.").

    Checked on the LAST token only, which is where the false split would happen.
    """
    if not piece.endswith("."):
        return False
    word = piece[:-1].rsplit(None, 1)[-1] if piece[:-1].strip() else ""
    word = word.strip("(„“\"'—-").lower()
    return bool(word) and (len(word) == 1 or word in ABBREVIATIONS)


def pack(pieces: List[str], limit: int) -> List[str]:
    """Greedily join `pieces` into chunks of at most `limit` characters.

    Greedy rather than balanced on purpose: an even split would make every chunk shorter than it
    needs to be, and each extra chunk is another full generation and another seam.
    """
    chunks: List[str] = []
    for piece in pieces:
        piece = piece.strip()
        if not piece:
            continue
        if chunks and (len(chunks[-1]) + 1 + len(piece) <= limit
                       or looks_like_an_abbreviation(chunks[-1])):
            chunks[-1] = f"{chunks[-1]} {piece}"
        else:
            chunks.append(piece)
    return chunks


def split_for_synthesis(text: str, limit: int) -> List[str]:
    """`text` as a list of chunks, each at most `limit` characters.

    Nothing is dropped and nothing is reordered: `" ".join(result)` is the input with its
    whitespace normalised, which `tests/test_chunking.py` pins.
    """
    text = (text or "").strip()
    if not text:
        return []
    if limit <= 0 or len(text) <= limit:
        return [text]

    chunks: List[str] = []
    for chunk in pack(SENTENCE_END_RE.split(text), limit):
        if len(chunk) <= limit:
            chunks.append(chunk)
            continue
        # One sentence longer than the budget: fall to clauses, then to words. The last resort is
        # a hard slice, which only a single unbroken token longer than `limit` can reach — total
        # by construction, so no input can come back empty.
        for part in pack(CLAUSE_END_RE.split(chunk), limit):
            if len(part) <= limit:
                chunks.append(part)
                continue
            for word_group in pack(part.split(), limit):
                if len(word_group) <= limit:
                    chunks.append(word_group)
                else:
                    chunks.extend(word_group[i:i + limit]
                                  for i in range(0, len(word_group), limit))
    return chunks


def absorb_short_tail(chunks: List[str], minimum: int) -> List[str]:
    """Merge a too-short FINAL chunk into the one before it.

    `pack` is greedy, so every chunk but the last is near-full and the last can come out as
    `"Danke."` — a three-word generation, which is exactly the input a cloning engine such as
    Kartoffelbox handles badly. Engines that do not care set `min_chunk_chars = 0`.

    **The merged chunk needs no upper guard.** `split_for_synthesis` never returns a chunk longer
    than its limit, and this only fires when the tail is shorter than `minimum`, so the result is
    at most `limit + minimum` — 360 characters at the Kartoffelbox defaults, against a ceiling of
    roughly 600. A bound here would be a branch no input could reach.

    Note the limit of the fix: a REQUEST whose entire text is `"Ja."` has nothing to merge with.
    That is a property of the engine, not something this function can repair.
    """
    if minimum <= 0 or len(chunks) < 2 or len(chunks[-1]) >= minimum:
        return chunks
    return chunks[:-2] + [f"{chunks[-2]} {chunks[-1]}"]
