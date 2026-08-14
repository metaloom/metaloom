# Helm Chart Test Harness

End-to-end tests for [`helm/loom`](../loom) and [`helm/cortex`](../cortex). The harness stands up a
throwaway [k3d](https://k3d.io) cluster, deploys both charts as one combined setup, and drives a real
pipeline through it — ingest an asset, run a graph on a Cortex worker, assert the results came back
and were persisted.

The point is to test the **charts**, not the application: everything it builds and runs comes from
the repository's own build scripts and container images, so a green run means "these charts deploy
this code", not "these charts render valid YAML".

## Running it

```bash
cd helm/test
./run.sh
```

That is the whole interface. It creates the cluster, runs every phase, and deletes the cluster
again. A run takes roughly 6–10 minutes, most of it importing the 2.2 GB Cortex image.

| Flag | Effect |
|------|--------|
| `--keep` | Leave the cluster running afterwards, for poking at with `kubectl` |
| `--reuse` | Reuse an existing cluster instead of recreating it (implies `--keep`) |
| `--build` | Build the container images first, via the repo's own build scripts |
| `--down` | Delete the cluster and exit |

`--reuse` is the one to use while iterating: the helm operations are `upgrade --install`, so a second
run against a live cluster is idempotent and skips both the cluster creation and the image import.

## Prerequisites

- **Docker** — a daemon the current user can talk to
- **Helm 3** and **curl**, **jq**
- **The two images**, built from the working tree:

  ```bash
  mvn package -DskipTests
  (cd loom-ui && npm run build)
  (cd loom/containers  && ./build-containers.sh jvm server)   # metaloom/loom-server
  (cd cortex/container && ./build-container.sh)               # metaloom/cortex-server
  ```

  Or just pass `--build`. Note the Cortex image additionally needs a local OpenCV 5.1 build — see
  [`cortex/container/build-container.sh`](../../cortex/container/build-container.sh).

`kubectl` and `k3d` are **not** prerequisites: the harness downloads them into `test/.bin/` on first
run. Nothing is installed system-wide, and `~/.kube/config` is never touched — the cluster's
kubeconfig lives in `test/.work/kubeconfig`.

## What it asserts

| Phase | Checks |
|-------|--------|
| **1 — Chart validation** | `helm lint` both charts; both render under the flag combinations a plain install never reaches — external DB, ingress, sandbox RBAC, S3, custom worker image, PDB |
| **2 — Cluster** | k3d cluster comes up; images import; both releases pass `--dry-run=server` against a real API server (schema and admission, which `helm lint` cannot check) |
| **3 — Media volume** | The shared media claim is created and the fixture lands on it |
| **4 — Deploy Loom** | Bundled Postgres and the Loom Deployment roll out; all four PVCs bind; **`svc/loom` resolves to the server pod only** |
| **5 — API bootstrap** | `/api/v1/health` answers 200; admin login works with the chart's `auth.initialPassword`; a long-lived API key can be minted |
| **6 — Deploy Cortex** | The StatefulSet becomes ready — which *is* the registration assertion, since its readiness probe hits `/api/ready` and that only answers once the worker has registered with Loom; the worker can read the shared media volume |
| **7 — Registration** | Loom's own `/api/v1/processors` lists the worker, `ONLINE`, with the pod name as its node id and the chart's `nodeKinds` as its whitelist |
| **8 — Pipeline** | Create a library, upload the fixture, create a `filesystem-source → sha512 / md5 / metadata` pipeline, run it, wait for a terminal state, and assert **node-result ledger rows were persisted for all three kinds** and that the stored SHA-512 matches the fixture |
| **9 — Restart** | Loom comes back after a `rollout restart` against its existing database and volumes, and Cortex re-registers |

Phase 8's persistence assertions are the ones that matter most. Cortex persists results
**best-effort** — a worker that cannot resolve the asset logs and moves on while still reporting the
task as successful. A test that only checked the run status would go green on a setup that stored
nothing.

## Design notes

**Single node.** Loom's uploads volume and the media volume are `ReadWriteOnce`, which is all k3d's
local-path provisioner offers. A second node would let the scheduler place Loom and Cortex apart and
leave one unable to bind — a k3d artefact, not a chart bug. Multi-node belongs in a test with a RWX
StorageClass.

**`pullPolicy: Never`.** Images are side-loaded with `k3d image import`. `IfNotPresent` would
silently fall back to a registry pull and test a published image instead of the working tree.

**The media loader.** Cortex mounts the media volume read-only, and there is no way to write into a
PVC except through a pod, so [`manifests/media.yaml`](manifests/media.yaml) ships a short-lived
`busybox` pod that holds the claim while the fixture is copied in. It is deleted before Cortex
starts, because the claim is `ReadWriteOnce` and the two cannot hold it at once.

**Why the fixture is uploaded as well as mounted.** Loom hands workers a *path*, never bytes, and
Cortex nodes attach their results to assets Loom already knows, resolved by SHA-512. The same file
therefore has to be both on the media volume (so the worker can read it) and ingested through the API
(so there is an asset to attach to). The final assertion — stored SHA-512 equals the fixture's — is
what proves the worker hashed the file the harness meant rather than something else on the volume.

**Restricted `nodeKinds`.** The stock worker advertises every built-in kind, including `whisper`,
`vlm` and `facedetect`, which want model weights and a GPU. [`values/cortex.yaml`](values/cortex.yaml)
whitelists only the four kinds the test uses, so a failure means the chart is broken rather than that
a GPU node could not load its weights.

## What building this found

The harness was written against the charts as they were, and four defects had to be fixed before a
combined deployment worked at all. They are listed here because each one is a regression the suite
now guards.

**1 — `svc/loom` also selected the Postgres pod** (fixed, `helm/loom` 0.2.0). The bundled Postgres
StatefulSet carries the same `app.kubernetes.io/name` and `/instance` labels as the server, and
neither the Service nor the Deployment selector pinned a component. `svc/loom` therefore had two
endpoints and balanced REST, gRPC, WebSocket and UI traffic onto a pod listening on 5432 — and
`kubectl logs deployment/loom` resolved to Postgres. Both selectors now pin
`app.kubernetes.io/component: server`. Phase 4 asserts the Service has exactly one endpoint.

> `spec.selector` is immutable, so this is not an in-place upgrade. Reinstall the release, or
> `helm upgrade --force`.

**2 — Loom wedged when it started before its database** (fixed, `helm/loom` 0.2.0). Nothing orders
the Deployment after the Postgres StatefulSet, so on a fresh install Loom usually won the race.
Flyway exhausted its retries in about two seconds (`Retrying in 0 sec...`) and the process then
neither exited nor opened port 8092 — so Kubernetes saw no crash to restart and the pod sat unready
until the liveness probe killed it minutes later. The chart now ships a `wait-for-database` init
container (`waitForDatabase.enabled`, on by default).

**3 — Cortex could not write its meta directory** (fixed, `helm/cortex` 0.2.0). With
`meta.persistence.enabled: false` — the chart default — nothing was mounted at `meta.path`, leaving
the image's root-owned `/meta` unwritable by the container's uid 1000. Every `filesystem-source` run
died with `AccessDeniedException: /meta/filesystem-index` **while the run still reported SUCCESS with
zero items**. The chart now always mounts `meta`, as an `emptyDir` when persistence is off. Phase 8
asserts the run processed at least one item, which is what catches this class of false green.

**4 — `media.readOnly: true` breaks the hash nodes** (documented, `helm/cortex`). `sha512`, `md5` and
`sha256` cache their digest on the source file as an extended attribute (`loom_sha512`), so a
read-only mount fails every hash task with `Error writing extended attribute ... Read-only file
system`. The default is now `false`, and the value's documentation corrects the claim — in
[`values.yaml`](../cortex/values.yaml) and in the Docker playbook — that only file-moving nodes such
as dedup need write access.

## Known issues (reported, not worked around)

These are application-side and are reported by the harness as `⊘` without failing the run, so that a
pre-existing bug cannot mask a chart regression. If one starts passing, the harness fails and tells
you to promote it to a real check.

**Flyway migrates into a different schema on every boot after the first.** `V1__db_setup.sql:1` runs
`CREATE SCHEMA IF NOT EXISTS loom`, and Postgres resolves an unqualified schema through `search_path`,
which defaults to `"$user", public`. The database user is `loom` — the default in the
[Containerfile](../../loom/containers/server/Containerfile), the chart and the Docker playbook. So the
first boot migrates into `public` (schema `loom` does not exist yet) and every later boot resolves to
`loom`, finds an empty history there, and re-runs every migration from V1. It dies in
[`V2.55__remove_webhook.sql`](../../loom/db/flyway/src/main/resources/db/migration/V2.55__remove_webhook.sql):

```
PSQLException: ERROR: duplicate key value violates unique constraint "pg_enum_typid_label_index"
  Detail: Key (enumtypid, enumlabel)=(…, CREATE_ANNOTATION) already exists.
```

That migration rebuilds the `loom_permission` enum from `pg_enum` joined to `pg_type` **filtered only
by `typname`** (lines 21–26). With two `loom_permission` types in two schemas it aggregates both label
sets into one `CREATE TYPE`, which then violates the unique index. The result is one database with two
Flyway histories and a Loom pod that never becomes ready again — on any restart, not just here.

Two independent fixes: pin Flyway's schema explicitly instead of letting `search_path` choose it, and
add a schema predicate to V2.55's label query. Until then
[`values/loom.yaml`](values/loom.yaml) sets the DB username to `loomapp` so `"$user"` never resolves
to a schema. **Set it back to `loom` to reproduce.**

**API keys do not authenticate against the REST API.** `POST /api/v1/tokens` returns an 8-character
key, and [the docs](../../website/content/english/docs/loom/authentication/index.adoc) say "API keys
behave like JWT tokens and are passed the same way in the `Authorization` header". They do not: only
`MCPAuthenticationHandler` resolves a key (via `TokenDao#findByToken`), so a key works on `/mcp/*` and
gets a 401 everywhere on REST, in every header form. The Docker playbook's advice to give a Cortex
worker a long-lived API key therefore does not work — the harness passes the admin JWT instead.

**Runs succeed without persisting node-result ledger rows.** The pipeline completes, the worker hashes
the file and writes the `loom_sha512` xattr, and `/api/v1/assets/{uuid}/node-results` stays empty.
`AbstractMediaNode#recordNodeResult` is a silent no-op when the asset does not resolve or the node's
injected `LoomClient` is null. Nothing points at the charts: the worker registers, its token
authenticates, and from inside the Cortex pod `GET /api/v1/assets/sha512/<hash>` returns 200 for the
very asset in question. Worth noting that this is the failure mode the persistence assertions exist
for — a run reporting SUCCESS while storing nothing.
