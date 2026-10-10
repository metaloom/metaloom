"""
WeSpeaker ONNX exports (VoxCeleb2, CC-BY-4.0).

The default repo is Wespeaker/wespeaker-voxceleb-resnet34-LM - the same weights pyannote's
speaker-diarization 3.1 embeds, 6.6M parameters, 0.74 % composite EER in the evaluation and the
fastest model there (0.3 s per 10 s on CPU). Point VOICEPRINT_WESPEAKER_REPO at
wespeaker-voxceleb-resnet293-LM (0.69 %) or wespeaker-voxceleb-campplus-LM for the other exports.

The export takes Kaldi fbank features: 80 mel bins, 25/10 ms, hamming window, computed on
int16-scale samples (WeSpeaker loads audio with normalize=False) and mean-normalised over time.
That is reproduced here with torchaudio's kaldi-compatible fbank, so the vectors match what
WeSpeaker and pyannote produce for the same audio. Runs on onnxruntime, CUDA when the GPU build is
installed, CPU otherwise.
"""

from __future__ import annotations

from typing import List

import numpy as np

from .. import config
from .base import Backend, resolve_device


class WeSpeakerBackend(Backend):
    name = "wespeaker"
    default_threshold = 0.40
    licence = "CC-BY-4.0 (weights, after the VoxCeleb licence); Apache-2.0 code"
    commercial = True

    def __init__(self):
        super().__init__()
        self.session = None
        self.input_name = None
        self.fbank_bins = 80

    @property
    def model_id(self) -> str:
        return config.WESPEAKER_REPO

    def _load(self):
        import onnxruntime as ort
        from huggingface_hub import hf_hub_download, list_repo_files

        onnx = config.WESPEAKER_ONNX
        if not onnx:
            candidates = [f for f in list_repo_files(config.WESPEAKER_REPO) if f.endswith(".onnx")]
            if not candidates:
                raise RuntimeError(f"{config.WESPEAKER_REPO} ships no .onnx export; set VOICEPRINT_WESPEAKER_ONNX")
            onnx = sorted(candidates)[0]
        path = hf_hub_download(config.WESPEAKER_REPO, onnx)
        try:
            cfg_path = hf_hub_download(config.WESPEAKER_REPO, "config.yaml")
            import yaml

            with open(cfg_path) as f:
                cfg = yaml.safe_load(f) or {}
            self.fbank_bins = int(cfg.get("dataset_args", {}).get("fbank_args", {}).get("num_mel_bins", 80))
        except Exception:  # noqa: BLE001 - the config is optional, 80 bins is the export default
            self.fbank_bins = 80

        device = resolve_device()
        providers = ["CPUExecutionProvider"]
        if device.startswith("cuda") and "CUDAExecutionProvider" in ort.get_available_providers():
            providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        self.session = ort.InferenceSession(path, sess_options=opts, providers=providers)
        self.input_name = self.session.get_inputs()[0].name
        self.device = "cuda" if providers[0] == "CUDAExecutionProvider" else "cpu"

    def _features(self, wav: np.ndarray) -> np.ndarray:
        import torch
        import torchaudio.compliance.kaldi as kaldi

        pcm = torch.from_numpy(np.asarray(wav, dtype=np.float32) * 32768.0).unsqueeze(0)
        f = kaldi.fbank(pcm, num_mel_bins=self.fbank_bins, frame_length=25, frame_shift=10, sample_frequency=16000,
                        window_type="hamming", dither=0.0)
        return (f - f.mean(dim=0)).numpy()

    def _embed(self, wavs: List[np.ndarray]) -> np.ndarray:
        feats = np.stack([self._features(w) for w in wavs]).astype(np.float32)
        out = self.session.run(None, {self.input_name: feats})[0]
        return np.asarray(out, dtype=np.float32).reshape(len(wavs), -1)
