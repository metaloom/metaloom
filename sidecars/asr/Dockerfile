# Cortex ASR sidecar: Voxtral Realtime, Whisper large-v3-turbo and Parakeet-TDT-v3 behind one
# HTTP + WebSocket API, every result carrying time codes. See README.md.
#
# cudnn-runtime rather than plain runtime: CTranslate2 (faster-whisper) links cuDNN 9 and cuBLAS 12
# from the system and does not find torch's private copies.
FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/root/.cache/huggingface

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv libsndfile1 ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# torch first, matched to the CUDA base image (see requirements.txt).
RUN pip3 install --break-system-packages --no-cache-dir \
    torch --index-url https://download.pytorch.org/whl/cu128

COPY requirements.txt .
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

COPY asr/ ./asr/
COPY server.py ./
COPY tests/ ./tests/

EXPOSE 9140
# Weights are NOT baked into the image - mount the HF cache instead. First use downloads
# Voxtral (~9 GB), Whisper large-v3-turbo (~1.6 GB) and Parakeet (~2.4 GB) into it.
#   docker build -t metaloom/asr-sidecar .
#   docker run --gpus all -p 9140:9140 \
#     -v $HOME/.cache/huggingface:/root/.cache/huggingface metaloom/asr-sidecar
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
    CMD curl -fs http://localhost:9140/health || exit 1
# One process: the models live in module globals, a second worker would load a second copy.
CMD ["python3", "server.py"]
