# CUDA runtime base, matching the torch wheel's CUDA major version (torch 2.13 default
# wheels are cu130). Mismatching these is what breaks a flash-attn build; we do not
# build flash-attn here (see README - the SDPA fallback is used instead), but keeping
# the versions aligned means `pip install flash-attn` in a derived image can work.
FROM nvidia/cuda:13.0.1-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/root/.cache/huggingface

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

# The model code is not on PyPI. --no-deps so it cannot re-resolve the pins above.
ARG MAGEFLOW_GIT_REF=acf55fab9a6c3ef215ec0f52ca49112a99036959
RUN pip3 install --break-system-packages --no-cache-dir --no-deps \
    "mage-flow @ git+https://github.com/microsoft/Mage.git@${MAGEFLOW_GIT_REF}#subdirectory=mage_flow"

COPY server.py mage_loader.py ./

EXPOSE 9210
# The 17.5 GB checkpoint is NOT baked into the image - mount the HF cache instead:
#   docker run --gpus all -p 9210:9210 \
#     -v $HOME/.cache/huggingface:/root/.cache/huggingface mage-flow-sidecar
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "9210", "--workers", "1"]
