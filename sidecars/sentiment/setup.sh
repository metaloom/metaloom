#!/usr/bin/env bash
# Create the venv. All three checkpoints are ungated and are pulled from the
# Hugging Face Hub on the first request for their language - no HF_TOKEN needed.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"

if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt

cat <<'EOF'

OK. Models download lazily on the first /v1/sentiment call for each language:
  de        oliverguhr/german-sentiment-bert                              (MIT)
  en        cardiffnlp/twitter-roberta-base-sentiment-latest              (CC-BY-4.0, attribution required)
  fallback  lxyuan/distilbert-base-multilingual-cased-sentiments-student  (Apache-2.0)

If the deployment cannot carry the CC-BY attribution, set
  SENTIMENT_MODEL_EN=lxyuan/distilbert-base-multilingual-cased-sentiments-student
EOF
