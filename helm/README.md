# MetaLoom Helm Charts

Helm charts for deploying MetaLoom on Kubernetes. There are two, matching the two components:

| Chart | Deploys | Directory |
|-------|---------|-----------|
| **loom** | The Loom backend server (REST/gRPC/GraphQL API, DB, auth, storage, MCP, pipeline engine, AI agent) + the bundled web UI. Optionally a self-contained PostgreSQL. | [`loom/`](./loom) |
| **cortex** | One or more Cortex workers that register with Loom and run node tasks. The image is overridable, so the same chart deploys the stock worker or **a custom node image**. | [`cortex/`](./cortex) |

```
        ┌──────────────┐        registers over WS        ┌──────────────────┐
        │  helm/loom   │◀────────────────────────────────│   helm/cortex    │
        │  Loom server │        results over REST         │  worker(s) 0..N  │
        │  + UI + DB   │────────────────────────────────▶ │  (stock/custom)  │
        └──────────────┘                                  └──────────────────┘
```

## Prerequisites

- Kubernetes 1.23+ and Helm 3
- A `StorageClass` for Loom's persistent volumes
- The MetaLoom images reachable from your cluster (`metaloom/loom-server`, `metaloom/cortex-server`,
  and any custom worker image you build)
- A PostgreSQL database — external (recommended) or the loom chart's optional bundled Postgres

## Quick start

```bash
# 1. Loom + a bundled Postgres (dev / evaluation):
helm install loom ./helm/loom \
  --set postgresql.enabled=true \
  --set auth.initialPassword=<admin-password>

# 2. A Cortex worker pointed at that Loom Service:
helm install cortex ./helm/cortex \
  --set loom.host=loom \
  --set loom.token=<api-token>
```

Reach the UI by port-forwarding the Loom Service (`svc/loom`, port `8092`, path `/ui/`) or by
enabling an Ingress. Log in as `admin`.

> Use an **external managed database** in production (`--set database.host=…` instead of
> `postgresql.enabled=true`). The bundled Postgres uses the official `postgres` image and exists for
> quick starts; it is not a managed, backed-up database.

## Custom Cortex node image

The cortex chart's `image.repository` is the knob for running **your own** worker — a custom Java
daemon or a Python worker. Build one from the examples and point the chart at it:

```bash
# Build a custom worker image (see examples/):
examples/cortex-custom/build-image.sh      # -> metaloom/cortex-custom  (JVM, serves health probes)
examples/cortex-python/build-image.sh      # -> metaloom/cortex-python  (minimal, no health port)

# Deploy it:
helm install cortex ./helm/cortex \
  --set image.repository=metaloom/cortex-custom \
  --set loom.token=<token> \
  --set 'nodeKinds={hello-world}'
```

For a minimal worker that does not serve `/api/health` (e.g. the Python example), disable the HTTP
probes: `--set readinessProbe.enabled=false --set livenessProbe.type=tcpSocket`.

## Two things not to miss

- **Loom's keystore volume is the JWT signing key.** It is persisted by default and must stay that
  way — losing it on a restart invalidates every issued token. Same for the config volume.
- **The coding sandbox is a unit.** `sandbox.enabled=true` renders the runner-namespace RBAC and
  guardrails together with the env var; enabling the env var alone leaves a server that cannot create
  runners. Leave it off unless you use the chat agent's sandbox.

## Validate before installing

```bash
helm lint ./helm/loom ./helm/cortex
helm template loom ./helm/loom --set postgresql.enabled=true | less
helm template cortex ./helm/cortex --set image.repository=metaloom/cortex-custom | less
```

## More

- Per-chart values and details: [`loom/README.md`](./loom/README.md), [`cortex/README.md`](./cortex/README.md)
- Customer-facing guide: <https://metaloom.io/docs/deployment/helm/>
- Internal specs: `spec/features/helm/HELM_LOOM.md`, `spec/features/helm/HELM_CORTEX.md`
