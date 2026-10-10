# Cortex voiceprint sidecar: ReDimNet2, WeSpeaker (ONNX) and SpeechBrain ECAPA speaker embeddings
# behind one HTTP API. See README.md.
#
# cudnn-runtime rather than plain runtime: onnxruntime-gpu links cuDNN 9 and cuBLAS 12 from the
# system and does not find torch's private copies.
FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/root/.cache/huggingface \
    TORCH_HOME=/root/.cache/torch

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv libsndfile1 ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# torch first, matched to the CUDA base image (see requirements.txt).
RUN pip3 install --break-system-packages --no-cache-dir \
    torch torchaudio --index-url https://download.pytorch.org/whl/cu128

COPY requirements.txt .
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

COPY third_party/ ./third_party/
COPY voiceprint/ ./voiceprint/
COPY server.py ./
COPY tests/ ./tests/

EXPOSE 9150
# Weights are NOT baked into the image - mount the caches instead. First use downloads ReDimNet2
# (~50 MB, torch hub), the WeSpeaker ONNX export (~25 MB) and SpeechBrain ECAPA (~80 MB) into them.
#   docker build -t metaloom/voiceprint-sidecar .
#   docker run --gpus all -p 9150:9150 \
#     -v $HOME/.cache/huggingface:/root/.cache/huggingface \
#     -v $HOME/.cache/torch:/root/.cache/torch metaloom/voiceprint-sidecar
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
    CMD curl -fs http://localhost:9150/health || exit 1
# One process: the models live in module globals, a second worker would load a second copy.
CMD ["python3", "server.py"]
