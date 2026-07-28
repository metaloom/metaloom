#!/bin/bash
#
# Start a local MinIO for developing against the s3-source node.
#
# MinIO is an S3-compatible object store, so the same code path that talks to AWS S3 in
# production is exercised here - no separate "local mode".
#
# Usage:
#   ./start-minio.sh              start MinIO and create the default bucket
#   ./start-minio.sh /path/to/media   ... and upload everything in that directory
#
set -o errexit
set -o pipefail

NAME=${MINIO_CONTAINER_NAME:-minio-dev}
IMAGE=${MINIO_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}
API_PORT=${MINIO_API_PORT:-9000}
CONSOLE_PORT=${MINIO_CONSOLE_PORT:-9001}
ACCESS_KEY=${MINIO_ROOT_USER:-minioadmin}
SECRET_KEY=${MINIO_ROOT_PASSWORD:-minioadmin}
BUCKET=${MINIO_BUCKET:-media}
MEDIA_DIR="${1:-}"

if docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
	echo "MinIO container '$NAME' is already running."
else
	docker rm -f "$NAME" >/dev/null 2>&1 || true
	echo "Starting MinIO ($IMAGE) on ports $API_PORT (API) and $CONSOLE_PORT (console)..."
	docker run -d --name "$NAME" \
		-p "${API_PORT}:9000" \
		-p "${CONSOLE_PORT}:9001" \
		-e "MINIO_ROOT_USER=${ACCESS_KEY}" \
		-e "MINIO_ROOT_PASSWORD=${SECRET_KEY}" \
		"$IMAGE" server /data --console-address ":9001" >/dev/null
fi

echo -n "Waiting for MinIO to become ready"
for _ in $(seq 1 60); do
	if curl -fsS "http://localhost:${API_PORT}/minio/health/live" >/dev/null 2>&1; then
		echo " ok"
		break
	fi
	echo -n "."
	sleep 1
done

# The mc client is optional; without it the bucket has to be created by hand or by the app.
if command -v mc >/dev/null 2>&1; then
	mc alias set metaloom-dev "http://localhost:${API_PORT}" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null
	mc mb --ignore-existing "metaloom-dev/${BUCKET}" >/dev/null
	echo "Bucket '${BUCKET}' is ready."
	if [ -n "$MEDIA_DIR" ]; then
		echo "Uploading ${MEDIA_DIR} to ${BUCKET}/ ..."
		mc cp --recursive "${MEDIA_DIR}/" "metaloom-dev/${BUCKET}/"
	fi
else
	echo "NOTE: 'mc' (MinIO client) is not installed, so the bucket was not created."
	echo "      Install it from https://min.io/docs/minio/linux/reference/minio-mc.html, or"
	echo "      create the bucket via the console at http://localhost:${CONSOLE_PORT}"
fi

cat <<EOF

MinIO is running.
  API:     http://localhost:${API_PORT}
  Console: http://localhost:${CONSOLE_PORT}  (user: ${ACCESS_KEY}, password: ${SECRET_KEY})

Point a Cortex worker at it:

  export CORTEX_S3_ENDPOINT=http://localhost:${API_PORT}
  export CORTEX_S3_REGION=us-east-1
  export CORTEX_S3_ACCESS_KEY=${ACCESS_KEY}
  export CORTEX_S3_SECRET_KEY=${SECRET_KEY}
  export CORTEX_S3_PATH_STYLE=true
  ./start-cortex.sh

Then build a pipeline whose source is 's3-source' with bucket '${BUCKET}'.

To publish produced files (thumbnails, depth maps) back into the bucket, add an
's3-sink' node at the end of a pipeline:

  filesystem-source -> sha512 -> thumbnail -> s3-sink (bucket=${BUCKET})

Then check what it wrote and confirm the content type survived:

  mc ls --recursive metaloom-dev/${BUCKET}/cortex/
  mc stat metaloom-dev/${BUCKET}/cortex/thumbnail/thumbnail_path/<shard>/<sha512>.thumb
      -> Content-Type must be image/jpeg, not application/octet-stream

Re-run the pipeline and confirm Last Modified is unchanged - that is the skip that
makes re-processing a published library nearly free.

To have runs skip listing the bucket entirely, enable bucket notifications:

  export CORTEX_S3_EVENTS_ENABLED=true
  export CORTEX_S3_EVENTS_WEBHOOK_SECRET=dev-secret
  mc admin config set metaloom-dev notify_webhook:cortex \\
      endpoint="http://host.docker.internal:8093/s3-events" auth_token="dev-secret"
  mc admin service restart metaloom-dev
  mc event add metaloom-dev/${BUCKET} arn:minio:sqs::cortex:webhook --event put,delete

Stop it with: docker rm -f ${NAME}
EOF
