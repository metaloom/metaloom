# MetaLoom Loom — Helm Chart

Deploys the **Loom backend server** — REST/gRPC/GraphQL API, database access, auth, storage, MCP,
the pipeline engine, the AI agent and the bundled web UI — into Kubernetes.

Pair it with the sibling [`helm/cortex`](../cortex) chart to add processing workers.

## Prerequisites

- Kubernetes 1.23+
- Helm 3
- A PostgreSQL database — either an external managed one (recommended for production) or the chart's
  optional bundled Postgres (quick starts / dev).
- A `StorageClass` for the persistent volumes (config, keystore, uploads).

## Install

### Quick start (bundled Postgres)

```bash
helm install loom ./helm/loom \
  --set postgresql.enabled=true \
  --set auth.initialPassword=<pick-a-password>
```

This runs a self-contained PostgreSQL (official `postgres` image — not a third-party subchart, so it
works offline) alongside Loom. **Use an external database in production instead.**

### Production (external database)

```bash
helm install loom ./helm/loom \
  --set database.host=postgres.internal \
  --set database.name=loom \
  --set database.user=loom \
  --set database.password=<db-password> \
  --set auth.initialPassword=<admin-password> \
  --set ingress.enabled=true \
  --set ingress.host=loom.example.com
```

Log in to the UI (`/ui/`) as `admin` with `auth.initialPassword`.

## What the chart covers (and two things not to miss)

- **The keystore volume is the JWT signing key.** `persistence.keystore` is enabled by default and
  **must stay persistent** — if the keystore is lost on a restart, every token issued before it stops
  verifying and all clients are logged out. Same for `persistence.config` (holds `loom.yml`).
- **The coding sandbox is a unit.** `sandbox.enabled=true` renders `LOOM_AGENT_SANDBOX_ENABLED` **and**
  the runner-namespace `Role`, `RoleBinding`, `ResourceQuota`, `LimitRange` and `NetworkPolicy`
  together. Setting the env var without that RBAC produces a server that cannot create runners (or
  creates unconstrained ones). Leave it disabled unless you use the chat agent's sandbox.

## Values

| Key | Default | Description |
|-----|---------|-------------|
| `replicaCount` | `1` | Keep at 1 — Loom is a single-writer server. |
| `image.repository` / `image.tag` | `metaloom/loom-server` / chart `appVersion` | Server image (`-native` tag for the GraalVM variant). |
| `auth.initialPassword` | `changeme` | Bootstrap admin password (`LOOM_INITIAL_PASSWORD`). **Change it.** |
| `auth.existingSecret` | `""` | Use an existing Secret (key `initial-password`). |
| `service.restPort` / `grpcPort` / `monitoringPort` | `8092` / `8091` / `8989` | Service ports. |
| `ingress.enabled` / `host` | `false` / — | Ingress for the REST/UI port. |
| `persistence.config` | enabled, 128Mi | Holds `loom.yml`. |
| `persistence.keystore` | enabled, 128Mi | **JWT signing keystore — keep persistent.** |
| `persistence.uploads` | enabled, 20Gi | Asset binary storage (`/uploads`). |
| `persistence.plugins` | disabled | Plugin drop directory. |
| `database.host` / `port` / `name` / `user` / `password` | — / `5432` / `loom` / `loom` / — | External DB connection. |
| `database.existingSecret` | `""` | External DB password Secret (key `db-password`). |
| `postgresql.enabled` | `false` | Bundle a self-contained Postgres (dev/quick start). |
| `postgresql.image` / `auth` / `persistence` | `postgres:17-alpine`, … | Bundled Postgres settings. |
| `ai.enabled` / `providerType` / `url` / `modelId` | `false` / … | Chat agent LLM provider (`LOOM_AI_*`). |
| `memory.enabled` | `false` | Agent memory bank (`LOOM_AGENT_MEMORY_ENABLED`). |
| `sandbox.enabled` | `false` | Coding sandbox + full runner-namespace RBAC/guardrails. |
| `sandbox.namespace` / `createNamespace` | `loom-runners` / `false` | Runner namespace. |
| `resources` | modest requests | CPU/memory. |
| `extraEnv` / `config` | `[]` / `{}` | Extra env vars / `loom.yml` passthrough. |
| `serviceAccount.create` | `true` | Create a ServiceAccount (also the sandbox RBAC subject). |
| `podSecurityContext` | uid 1000 / gid 0 | Matches the image user. |

See [`values.yaml`](./values.yaml) for the full, commented set.

More: the customer-facing [Helm Charts guide](https://metaloom.io/docs/deployment/helm/) and the
internal spec `spec/features/helm/HELM_LOOM.md`.
