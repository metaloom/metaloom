# Loom Agent — coding sandbox deployment

The agentic loop can run coding tasks (`run_shell` / `write_file` / `read_file` / `list_files`)
inside a hardened, per-chat **Session Runner** container. One runner is provisioned lazily on the
first coding tool call of a chat and reaped on idle-TTL / max-session (the `SandboxReaper`).

## Backends

Selected via `LOOM_AGENT_SANDBOX_BACKEND`:

- **`podman`** (local dev) — `io.metaloom.loom.agent.sandbox.backend.PodmanBackend`. Runs a rootless,
  hardened container and reaches it on `127.0.0.1:<published-port>`.
- **`kubernetes`** (prod, incl. OpenShift) — `KubernetesBackend`. Creates the Pod below using the
  Loom pod's mounted service-account token, and reaches it on the pod IP. Needs RBAC allowing the
  Loom service account to `create/get/delete` pods in `LOOM_AGENT_SANDBOX_NAMESPACE`, plus (recommended)
  a `ResourceQuota`, `LimitRange` and a default-deny `NetworkPolicy` on the `app: loom-session-runner`
  label.

## Image

Build and push the runner image (must match `LOOM_AGENT_SANDBOX_IMAGE`):

```bash
../session-runner/build.sh
```

## Config (LOOM_AGENT_SANDBOX_*)

| Env | Default | Meaning |
|---|---|---|
| `LOOM_AGENT_SANDBOX_ENABLED` | `false` | Master switch for the coding tools |
| `LOOM_AGENT_SANDBOX_BACKEND` | `podman` | `podman` \| `kubernetes` |
| `LOOM_AGENT_SANDBOX_IMAGE` | `metaloom/loom-session-runner:latest` | Runner image |
| `LOOM_AGENT_SANDBOX_NAMESPACE` | (SA namespace) | k8s namespace for runners |
| `LOOM_AGENT_SANDBOX_IDLE_TTL_S` | `900` | Reap after this much inactivity |
| `LOOM_AGENT_SANDBOX_MAX_SESSION_S` | `3600` | Hard session time-box |
| `LOOM_AGENT_SANDBOX_EXEC_TIMEOUT_S` | `120` | Default per-exec wall-clock |
| `LOOM_AGENT_SANDBOX_MAX_CONCURRENT` | `10` | Per-deployment cap on live runners |
| `LOOM_AGENT_SANDBOX_READY_TIMEOUT_S` | `60` | Wait for the runner to become healthy |

`session-runner-pod-template.yaml` is a reference of the Pod the backend POSTs at runtime.
