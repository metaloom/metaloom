"""
SpeechBrain ECAPA-TDNN (VoxCeleb1+2, Apache-2.0).

The most downloaded open speaker model and the de-facto baseline: 1.28 % composite EER in the
evaluation, so the weakest of the three here, but Apache-2.0 end to end and installed by
`pip install speechbrain` with no vendored code. Kept as the fallback for a deployment that cannot
carry the CC-BY attribution of the WeSpeaker export and does not want vendored model code.
"""

from __future__ import annotations

import os
from typing import List

import numpy as np

from .. import config
from .base import Backend, resolve_device


class SpeechBrainBackend(Backend):
    name = "speechbrain"
    default_threshold = 0.35
    licence = "Apache-2.0 (code and weights); training data VoxCeleb1+2"
    commercial = True

    def __init__(self):
        super().__init__()
        self.classifier = None

    @property
    def model_id(self) -> str:
        return config.SPEECHBRAIN_REPO

    def _load(self):
        from speechbrain.inference.speaker import EncoderClassifier

        self.device = resolve_device()
        savedir = os.path.join(os.environ.get("HF_HOME", os.path.expanduser("~/.cache/huggingface")), "speechbrain",
                               config.SPEECHBRAIN_REPO.replace("/", "--"))
        self.classifier = EncoderClassifier.from_hparams(source=config.SPEECHBRAIN_REPO, savedir=savedir,
                                                         run_opts={"device": self.device + ":0" if self.device == "cuda" else self.device})

    def _embed(self, wavs: List[np.ndarray]) -> np.ndarray:
        import torch

        with torch.no_grad():
            x = torch.from_numpy(np.stack(wavs)).to(self.device)
            return self.classifier.encode_batch(x).squeeze(1).float().cpu().numpy()
