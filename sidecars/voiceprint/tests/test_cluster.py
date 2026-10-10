"""
The clustering arithmetic: known centroids come back as that many speakers, ordered by speaking
time; max_speakers caps the cut; overlapping windows merge into segments; the pooled vector is a
unit vector.
"""

import numpy as np

from voiceprint import cluster

RATE = 16000


def _around(center, n, rng, spread=0.08):
    return cluster.normalize(np.stack([center + rng.standard_normal(len(center)) * spread for _ in range(n)]))


def _centroids(k, dim, rng):
    # Orthogonal centroids: random directions in a low dimension can sit 0.5 apart, which is
    # exactly the threshold these tests cut at.
    return np.eye(dim, dtype=np.float32)[:k]


def test_three_voices_become_three_speakers_longest_first():
    rng = np.random.default_rng(1)
    c = _centroids(3, 32, rng)
    # speaker sizes 6, 3, 2 windows but placed in the order 3, 6, 2 to test the sort
    vectors = np.concatenate([_around(c[0], 3, rng), _around(c[1], 6, rng), _around(c[2], 2, rng)])
    bounds = [(i * 2 * RATE, (i * 2 + 3) * RATE) for i in range(len(vectors))]
    speakers, labels = cluster.speakers(vectors, bounds, threshold=0.5)
    assert [len(s.windows) for s in speakers] == [6, 3, 2]
    assert [s.index for s in speakers] == [0, 1, 2]
    assert labels.tolist() == [1] * 3 + [0] * 6 + [2] * 2
    assert speakers[0].total_ms > speakers[1].total_ms > speakers[2].total_ms
    for s in speakers:
        assert abs(np.linalg.norm(s.vector) - 1.0) < 1e-5
        assert 0.9 < s.cohesion <= 1.0


def test_max_speakers_caps_the_cut():
    rng = np.random.default_rng(2)
    c = _centroids(4, 16, rng)
    vectors = np.concatenate([_around(ci, 4, rng) for ci in c])
    bounds = [(i * RATE, (i + 1) * RATE) for i in range(len(vectors))]
    assert len(cluster.speakers(vectors, bounds, 0.5)[0]) == 4
    assert len(cluster.speakers(vectors, bounds, 0.5, max_speakers=2)[0]) == 2


def test_single_window_is_one_speaker_with_cohesion_one():
    speakers, labels = cluster.speakers(np.array([[1.0, 0.0]]), [(0, RATE)], 0.5)
    assert len(speakers) == 1 and labels.tolist() == [0]
    assert speakers[0].cohesion == 1.0
    assert speakers[0].total_ms == 1000


def test_no_windows():
    speakers, labels = cluster.speakers(np.zeros((0, 8)), [], 0.5)
    assert speakers == [] and len(labels) == 0
    assert cluster.pooled(np.zeros((0, 8))) is None


def test_segments_merge_overlapping_windows():
    merged = cluster.merge_segments([(0, 30), (15, 45), (45, 60), (100, 130), (90, 110)])
    assert merged == [(0, 60), (90, 130)]


def test_pooled_is_a_unit_vector_near_the_mean():
    rng = np.random.default_rng(3)
    c = _centroids(1, 8, rng)[0]
    p = cluster.pooled(_around(c, 10, rng))
    assert abs(np.linalg.norm(p) - 1.0) < 1e-5
    assert float(p @ c) > 0.95


def test_cosine():
    assert cluster.cosine([1, 0], [1, 0]) == 1.0
    assert abs(cluster.cosine([1, 0], [0, 1])) < 1e-9
    assert cluster.cosine([0, 0], [1, 1]) == 0.0
