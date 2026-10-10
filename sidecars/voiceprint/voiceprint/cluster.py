"""
Who is who: group window embeddings into speakers, and turn window labels into a timeline.

Agglomerative clustering with average linkage on cosine distance, cut at `1 - threshold`, where
`threshold` is the cosine similarity at or above which two windows are the same voice. Every
backend names its own default threshold because the score distributions differ between models
(the eval harness measured them per model; see README.md). `max_speakers` > 0 caps the cut.

Speakers are numbered by speaking time, longest first, so speaker 0 is the main voice of the
recording. Each speaker carries a `vector` - the L2-normalised mean of its windows, which is the
voiceprint a later identification compares against - and a `cohesion`, the mean cosine of its
windows to that vector (1.0 is a single window; a low value means the cluster is probably two
voices the threshold did not separate).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

import numpy as np


def normalize(vectors: np.ndarray) -> np.ndarray:
    vectors = np.asarray(vectors, dtype=np.float32)
    norms = np.linalg.norm(vectors, axis=-1, keepdims=True)
    return vectors / np.maximum(norms, 1e-12)


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    if denom == 0:
        return 0.0
    return float(np.dot(a, b) / denom)


def labels(vectors: np.ndarray, threshold: float, max_speakers: int = 0) -> np.ndarray:
    """Cluster label per row of `vectors` (already normalised), 0-based, not yet ordered."""
    n = len(vectors)
    if n == 0:
        return np.zeros(0, dtype=int)
    if n == 1:
        return np.zeros(1, dtype=int)
    from scipy.cluster.hierarchy import fcluster, linkage

    link = linkage(vectors, method="average", metric="cosine")
    out = fcluster(link, t=max(0.0, 1.0 - threshold), criterion="distance") - 1
    if max_speakers > 0 and out.max() + 1 > max_speakers:
        out = fcluster(link, t=max_speakers, criterion="maxclust") - 1
    return out.astype(int)


@dataclass
class Speaker:
    index: int
    windows: List[int]
    vector: np.ndarray
    cohesion: float
    segments: List[Tuple[int, int]] = field(default_factory=list)
    total_ms: int = 0


def speakers(vectors: np.ndarray, window_bounds: Sequence[Tuple[int, int]], threshold: float, max_speakers: int = 0,
             sample_rate: int = 16000) -> Tuple[List[Speaker], np.ndarray]:
    """
    Group the windows into speakers ordered by speaking time, and return (speakers, window labels).

    `window_bounds` are (start, end) sample positions per window, in the same order as `vectors`.
    """
    vectors = normalize(vectors)
    raw = labels(vectors, threshold, max_speakers)
    groups = {}
    for i, lab in enumerate(raw.tolist()):
        groups.setdefault(lab, []).append(i)

    built: List[Speaker] = []
    for members in groups.values():
        segs = merge_segments([window_bounds[i] for i in members])
        total = sum(e - s for s, e in segs)
        centroid = normalize(vectors[members].mean(axis=0))
        cohesion = float(np.mean(vectors[members] @ centroid)) if len(members) > 1 else 1.0
        built.append(Speaker(-1, members, centroid, cohesion, segs, int(round(total * 1000 / sample_rate))))
    # Longest speaker first; ties by first appearance so the numbering is stable across runs.
    built.sort(key=lambda s: (-s.total_ms, s.windows[0]))
    remap = np.zeros(len(vectors), dtype=int)
    for index, speaker in enumerate(built):
        speaker.index = index
        for i in speaker.windows:
            remap[i] = index
    return built, remap


def merge_segments(bounds: Sequence[Tuple[int, int]]) -> List[Tuple[int, int]]:
    """Union of overlapping or touching (start, end) ranges, sorted."""
    out: List[Tuple[int, int]] = []
    for s, e in sorted(bounds):
        if out and s <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], e))
        else:
            out.append((s, e))
    return out


def pooled(vectors: np.ndarray) -> Optional[np.ndarray]:
    """One voiceprint for the whole recording: the normalised mean of every window. None when there is none."""
    if len(vectors) == 0:
        return None
    return normalize(normalize(vectors).mean(axis=0))
