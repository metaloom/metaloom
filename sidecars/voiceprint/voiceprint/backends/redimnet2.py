"""
ReDimNet2 (PalabraAI, Interspeech 2026, MIT) - the default backend.

The best commercially usable model in the voiceprint evaluation this sidecar comes from: B6
trained on VoxCeleb2 only scores 0.43 % composite EER on German/Austrian speakers (0.29 % on
VoxCeleb1-O), with 12.4M parameters and ~0.9 s per 10 s of audio on four CPU threads. The
vb2+vox2 and vb2+vox2+cnc2 checkpoints are better still (0.29 % / 0.26 %) but VoxBlink2 and
CN-Celeb are non-commercial training data, so they are opt-in through VOICEPRINT_REDIMNET2_DATASET.

The model code is vendored under third_party/redimnet2 (MIT) so that the image builds without
torch.hub reaching GitHub, and it is imported under its own top-level name because its modules
import each other as `redimnet2.layers...`. Weights are fetched from the GitHub release on first
use into TORCH_HOME/hub/checkpoints (mount it in the container) and loaded with weights_only=True.
"""

from __future__ import annotations

import logging
import os
import sys
from pathlib import Path
from typing import List

import numpy as np

from .. import config
from .base import Backend, resolve_device

logger = logging.getLogger("voiceprint.redimnet2")

THIRD_PARTY = Path(__file__).resolve().parents[2] / "third_party" / "redimnet2"


class ReDimNet2Backend(Backend):
    name = "redimnet2"
    default_threshold = 0.40
    licence = "MIT (code and weights); training data VoxCeleb2"

    def __init__(self):
        super().__init__()
        self.commercial = config.REDIMNET2_DATASET == "vox2"
        self.model = None

    @property
    def model_id(self) -> str:
        return f"redimnet2-{config.REDIMNET2_SIZE}-{config.REDIMNET2_DATASET}-{config.REDIMNET2_TRAIN}"

    @property
    def weight_name(self) -> str:
        return f"{config.REDIMNET2_SIZE}-{config.REDIMNET2_DATASET}-{config.REDIMNET2_TRAIN}.pt"

    def _load(self):
        import torch

        if str(THIRD_PARTY) not in sys.path:
            sys.path.insert(0, str(THIRD_PARTY))
        from redimnet2.redimnet2 import ReDimNet2Wrap  # noqa: E402 - vendored package

        url = config.REDIMNET2_WEIGHTS_URL.format(weight_name=self.weight_name)
        cache = Path(torch.hub.get_dir()) / "checkpoints"
        cache.mkdir(parents=True, exist_ok=True)
        path = cache / self.weight_name
        if not path.exists():
            logger.info("Downloading %s to %s", url, path)
            torch.hub.download_url_to_file(url, str(path), progress=False)
        # The release files are plain dicts (model_config + state_dict). weights_only=True refuses
        # anything else, which is the point: a checkpoint from a mirror must not be able to run code.
        full = torch.load(str(path), map_location="cpu", weights_only=True)
        model = ReDimNet2Wrap(**full["model_config"])
        res = model.load_state_dict(full["state_dict"])
        if res.missing_keys or res.unexpected_keys:
            raise RuntimeError(f"{self.weight_name}: missing={res.missing_keys[:5]} unexpected={res.unexpected_keys[:5]}")
        self.device = resolve_device()
        self.model = model.to(self.device).eval()
        torch.set_num_threads(int(os.environ.get("VOICEPRINT_TORCH_THREADS", "4")))

    def _embed(self, wavs: List[np.ndarray]) -> np.ndarray:
        import torch

        with torch.no_grad():
            x = torch.from_numpy(np.stack(wavs)).to(self.device)
            return self.model(x).float().cpu().numpy()
