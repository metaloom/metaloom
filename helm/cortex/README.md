# MetaLoom Cortex — Helm Chart

Deploys one or more **Cortex workers** into Kubernetes. A worker dials **out** to a Loom backend over
the processor WebSocket, registers, and runs the node tasks Loom dispatches (hashing, thumbnails, face
detection, transcription, LLM/VLM, …). Loom never connects in to the worker.

The chart's headline knob is **`image.repository`**: point it at the stock worker
(`metaloom/cortex-server`) or at **your own image** that advertises custom node kinds — the same chart
deploys either. See [Custom node image](#custom-node-image).

## Prerequisites

- Kubernetes 1.23+
- Helm 3
- A reachable Loom backend (in-cluster Service or external host). Deploy Loom with the sibling
  [`helm/loom`](../loom) chart.
- The worker image pushed to a registry your cluster can pull from.

## Install

```bash
# Stock worker, registering with an in-cluster Loom Service named "loom":
helm install cortex ./helm/cortex \
  --set loom.host=loom \
  --set loom.token=<jwt-or-api-token>
```

Scale out by raising `replicaCount`; each replica registers as its own worker with a stable id taken
from its StatefulSet pod name.

## Custom node image

To run a worker that implements **your own** nodes, build an image (see the
[`examples/cortex-custom`](../../examples/cortex-custom) Java daemon or
[`examples/cortex-python`](../../examples/cortex-python) Python worker) and point the chart at it:

```bash
# A JVM custom daemon built on the stock base image (serves /api/health + /api/ready):
helm install cortex ./helm/cortex \
  --set image.repository=metaloom/cortex-custom \
  --set loom.token=<token> \
  --set 'nodeKinds={hello-world}'

# A minimal Python worker that does NOT serve a monitoring port — disable the HTTP probes:
helm install cortex-py ./helm/cortex \
  --set image.repository=metaloom/cortex-python \
  --set loom.token=<token> \
  --set readinessProbe.enabled=false \
  --set livenessProbe.type=tcpSocket \
  --set 'nodeKinds={py-hello}'
```

`nodeKinds` is rendered into both `CORTEX_NODE_WHITELIST` and `CORTEX_NODE_KINDS` so it works for the
JVM worker and the Python example alike. Use `command`/`args` if your image needs a different
entrypoint, and `extraEnv` for image-specific configuration.

## Media access

Loom hands the worker a **path** to media, never the bytes. Any source/hash/whisper-style node must be
able to read that path, so mount the same storage Loom uses for uploads:

```bash
helm install cortex ./helm/cortex \
  --set media.enabled=true \
  --set media.existingClaim=loom-uploads \
  --set media.mountPath=/media
```

`media` also supports `hostPath` and `nfs` sources.

## Values

| Key | Default | Description |
|-----|---------|-------------|
| `replicaCount` | `1` | Number of worker replicas (each is its own registered worker). |
| `image.repository` | `metaloom/cortex-server` | **Worker image — override for a custom node image.** |
| `image.tag` | `""` (chart `appVersion`) | Image tag. |
| `image.pullPolicy` | `IfNotPresent` | Image pull policy. |
| `command` / `args` | `[]` | Entrypoint override for custom images. |
| `loom.host` | `loom` | Loom host / Service name. |
| `loom.port` | `8092` | Loom REST + WebSocket port. |
| `loom.token` | `""` | API token for registration + result write-back (stored in a Secret). |
| `loom.existingSecret` | `""` | Use an existing Secret (key `token`) instead. |
| `nodeId` | `""` (pod name) | Stable worker id; defaults to the StatefulSet ordinal. |
| `nodeKinds` | `[]` | Node kinds to advertise (`CORTEX_NODE_WHITELIST` + `CORTEX_NODE_KINDS`). |
| `nodeBlacklist` | `[]` | Node kinds to disable (`CORTEX_NODE_BLACKLIST`). |
| `meta.path` | `/meta` | Local metadata directory (`CORTEX_META_PATH`). |
| `meta.persistence.enabled` | `false` | Persist the meta directory via a volumeClaimTemplate. |
| `media.enabled` | `false` | Mount shared source media into the worker. |
| `media.mountPath` | `/media` | Where media is mounted (must match Loom's paths). |
| `media.existingClaim` / `hostPath` / `nfs` | `""` | Media volume source (pick one). |
| `monitoring.port` | `8093` | `CORTEX_MONITORING_PORT` — health/readiness/metrics. |
| `livenessProbe.*` | httpGet `/api/health` | Liveness probe (type `httpGet`/`tcpSocket`/`exec`, or disable). |
| `readinessProbe.*` | httpGet `/api/ready` | Readiness probe (ready once registered with Loom). |
| `service.enabled` | `true` | Headless Service fronting the monitoring port. |
| `resources` | small requests | CPU/memory; add `limits.nvidia.com/gpu` for GPU nodes. |
| `extraEnv` | `[]` | Extra environment variables. |
| `serviceAccount.create` | `true` | Create a ServiceAccount. |
| `podSecurityContext` | uid 1000 / gid 0 | Matches the image user. |
| `nodeSelector` / `tolerations` / `affinity` | `{}` / `[]` / `{}` | Scheduling. |
| `podDisruptionBudget.enabled` | `false` | Optional PDB. |

See [`values.yaml`](./values.yaml) for the full, commented set.

## Notes

- Cortex runs as a **StatefulSet** on purpose: `CORTEX_NODE_ID` comes from the stable pod name so a
  restart keeps the same Loom lease. Do not swap it for a Deployment with a random suffix.
- Without a token the worker still registers and answers tasks but skips result persistence — the same
  graceful degradation as a token-less offline worker.

More: the customer-facing [Helm Charts guide](https://metaloom.io/docs/deployment/helm/) and the
internal spec `spec/features/helm/HELM_CORTEX.md`.
