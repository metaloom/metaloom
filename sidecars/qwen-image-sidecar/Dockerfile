# CUDA runtime base. The cu128 torch wheel installed below wants CUDA 12.8; mismatching
# CUDA majors is the usual cause of a late torch/kernel failure.
FROM nvidia/cuda:12.8.1-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/root/.cache/huggingface

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# torch AND torchvision first, matched to the CUDA base image (see requirements.txt).
# torchvision is not optional: the Qwen3-VL processor this pipeline loads pulls in
# Qwen3VLVideoProcessor, which hard-requires it and fails the model load with
# "requires the Torchvision library but it was not found" - even though nothing here
# touches video. They must be installed together, from the same index, so their
# compiled ABIs match.
RUN pip3 install --break-system-packages --no-cache-dir \
    torch torchvision --index-url https://download.pytorch.org/whl/cu128

COPY requirements.txt .
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

# diffusers comes from GIT, not PyPI: QwenImage21Pipeline was merged on 2026-09-18
# (PR #14804) and is in no release up to 0.40.0, which shipped 2026-08-20. Pinned to
# the merge commit rather than main so this image is reproducible. `git` is in the
# apt list above solely for this line. Keep in step with qwen_loader.DIFFUSERS_GIT_REF.
ARG DIFFUSERS_GIT_REF=6256aa7666cedd47443adc8f82da9a10e110b09c
RUN pip3 install --break-system-packages --no-cache-dir \
    "diffusers @ git+https://github.com/huggingface/diffusers@${DIFFUSERS_GIT_REF}"

# Every module server.py imports, or the container dies at import time rather than at build
# time. mask_ops is easy to forget because it is the one file extracted for testability rather
# than added for a feature.
COPY server.py qwen_loader.py mask_ops.py qwen_smoke.py ./

EXPOSE 9230
# The 33 GB checkpoint is NOT baked into the image - mount the HF cache instead:
#   docker build -t qwen-image-sidecar .
#   docker run --gpus all -p 9230:9230 \
#     -v $HOME/.cache/huggingface:/root/.cache/huggingface qwen-image-sidecar
#
# Measured: ~46 GB resident at output_resolution 1024, ~66 GB at 2048 - well above the
# 33 GB of weights, because activations and the KV cache are not returned between
# requests. A100 80 GB or H200. On a smaller card add
#   -e QWENIMAGE_OFFLOAD=1
# Pin a specific card with -e CUDA_VISIBLE_DEVICES=1 (and --gpus all, not --gpus device=1,
# so the index the container sees matches what you asked for).
#
# LICENCE: Qwen RESEARCH LICENSE - non-commercial use only. See the README.
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "9230", "--workers", "1"]
