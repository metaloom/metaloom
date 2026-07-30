# CUDA runtime base. LTX-2 needs CUDA >12.7 (model card); this matches the cu128 torch
# wheel installed below. Mismatching CUDA majors is the usual cause of a late torch/
# kernel failure.
FROM nvidia/cuda:12.8.1-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/root/.cache/huggingface

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv git ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# torch first, matched to the CUDA base image (see requirements.txt comment).
RUN pip3 install --break-system-packages --no-cache-dir \
    torch --index-url https://download.pytorch.org/whl/cu128

COPY requirements.txt .
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

COPY server.py ltx_loader.py ./

EXPOSE 9220
# The multi-GB checkpoint is NOT baked into the image — mount the HF cache instead:
#   docker run --gpus all -p 9220:9220 \
#     -v $HOME/.cache/huggingface:/root/.cache/huggingface ltx2-sidecar
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "9220", "--workers", "1"]
