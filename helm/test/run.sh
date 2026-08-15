#!/usr/bin/env bash
#
# End-to-end test harness for the MetaLoom Helm charts.
#
# Stands up a throwaway k3d cluster, deploys helm/loom and helm/cortex as a
# combined setup, and drives a real pipeline through it: ingest an asset, run a
# graph on a Cortex worker, and assert the results came back and were persisted.
#
#   ./run.sh              core + extended suites, cluster deleted afterwards
#   ./run.sh --core       core only (deploy, pipeline, restart) — the fast path
#   ./run.sh --suite X,Y  pick suites explicitly (core, extended)
#   ./run.sh --keep       leave the cluster up for poking at
#   ./run.sh --reuse      reuse an existing cluster (implies --keep)
#   ./run.sh --build      build the container images first
#   ./run.sh --down       just delete the cluster and exit
#
# See README.md for what each phase asserts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK_DIR="$SCRIPT_DIR/.work"
BIN_DIR="$SCRIPT_DIR/.bin"

# ── Configuration ─────────────────────────────────────────────────────

CLUSTER_NAME="${CLUSTER_NAME:-metaloom-helm-test}"
NAMESPACE="${NAMESPACE:-metaloom-test}"
CLUSTER_AGENTS="${CLUSTER_AGENTS:-2}"
LOOM_RELEASE="loom"
CORTEX_RELEASE="cortex"
LOOM_LOCAL_PORT="${LOOM_LOCAL_PORT:-18092}"
LOOM_URL="http://127.0.0.1:${LOOM_LOCAL_PORT}"

# Extended-suite releases and endpoints.
LOOM_EXT_RELEASE="loom-ext"
LOOM_EXT_LOCAL_PORT="${LOOM_EXT_LOCAL_PORT:-18093}"
LOOM_NODB_RELEASE="loom-nodb"
INGRESS_LOCAL_PORT="${INGRESS_LOCAL_PORT:-18080}"
INGRESS_HOST="loom.harness.test"
SANDBOX_NAMESPACE="loom-runners"

# Backs a hostPath mount on every node — the RWX stand-in local-path cannot give.
SHARED_MEDIA_VOLUME="${SHARED_MEDIA_VOLUME:-metaloom-helm-test-media}"
SHARED_MEDIA_PATH="/shared-media"

# Which phase groups to run. core = 1-9 (deploy + pipeline + restart);
# extended = 10-15 (scale-out, ingress, sandbox, upgrade, external DB, no-DB).
SUITES="${SUITES:-core,extended}"

# Must match auth.initialPassword in values/loom.yaml.
LOOM_ADMIN_PASSWORD="helm-harness-admin-pw"
LOOM_TOKEN=""

KEEP_CLUSTER=false
REUSE_CLUSTER=false
BUILD_IMAGES=false
DOWN_ONLY=false

mkdir -p "$WORK_DIR"
export KUBECONFIG="$WORK_DIR/kubeconfig"

# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/cluster.sh
source "$SCRIPT_DIR/lib/cluster.sh"
# shellcheck source=lib/loom.sh
source "$SCRIPT_DIR/lib/loom.sh"

# ── Arguments ─────────────────────────────────────────────────────────

while (( $# > 0 )); do
    case "$1" in
        --keep)  KEEP_CLUSTER=true ;;
        --reuse) REUSE_CLUSTER=true; KEEP_CLUSTER=true ;;
        --build) BUILD_IMAGES=true ;;
        --down)  DOWN_ONLY=true ;;
        --core)  SUITES="core" ;;
        --suite) shift; SUITES="${1:-core}" ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fatal "unknown argument: $1 (try --help)" ;;
    esac
    shift
done

# ── Teardown ──────────────────────────────────────────────────────────
#
# Diagnostics are dumped BEFORE the cluster goes away — after deletion there is
# nothing left to explain a failure with.

_suite_enabled() { [[ ",$SUITES," == *",$1,"* ]]; }

cleanup() {
    local rc=$?
    port_forward_stop
    if (( TESTS_FAILED > 0 )) || (( rc != 0 )); then
        dump_diagnostics 2>/dev/null || true
    fi
    if [[ "$KEEP_CLUSTER" == "true" ]]; then
        printf '\n%s\n' "${C_YELLOW}Cluster '$CLUSTER_NAME' left running.${C_RESET}"
        printf '%s\n' "  export KUBECONFIG=$KUBECONFIG"
        printf '%s\n' "  kubectl -n $NAMESPACE get pods"
        printf '%s\n' "  $SCRIPT_DIR/run.sh --down    # when finished"
    else
        cluster_down
    fi
}

# ── Phases ────────────────────────────────────────────────────────────

# Renders every template with the flags a deploy does not reach, so the
# conditional blocks (ingress, sandbox RBAC, S3/cloud secrets, PDB, external DB)
# are at least proven to produce valid YAML.
phase_chart_validation() {
    section "Phase 1 — Chart validation (offline)"

    check "helm lint loom"   helm lint "$REPO_ROOT/helm/loom" || true
    check "helm lint cortex" helm lint "$REPO_ROOT/helm/cortex" || true

    _renders() { helm template "$@" >/dev/null; }

    check "loom renders with bundled postgres" \
        _renders loom "$REPO_ROOT/helm/loom" --set postgresql.enabled=true || true
    check "loom renders with an external database" \
        _renders loom "$REPO_ROOT/helm/loom" \
            --set database.host=pg.example.com --set database.password=x || true
    check "loom renders with ingress enabled" \
        _renders loom "$REPO_ROOT/helm/loom" \
            --set ingress.enabled=true --set ingress.host=loom.example.com || true
    check "loom renders with the sandbox RBAC bundle" \
        _renders loom "$REPO_ROOT/helm/loom" \
            --set sandbox.enabled=true --set sandbox.createNamespace=true || true

    check "cortex renders with a token" \
        _renders cortex "$REPO_ROOT/helm/cortex" --set loom.token=t || true
    check "cortex renders with S3 enabled" \
        _renders cortex "$REPO_ROOT/helm/cortex" \
            --set s3.enabled=true --set s3.accessKey=a --set s3.secretKey=b || true
    check "cortex renders with a custom image and node kinds" \
        _renders cortex "$REPO_ROOT/helm/cortex" \
            --set image.repository=metaloom/cortex-custom \
            --set 'nodeKinds={hello-world}' || true
    check "cortex renders with a PodDisruptionBudget" \
        _renders cortex "$REPO_ROOT/helm/cortex" \
            --set podDisruptionBudget.enabled=true \
            --set podDisruptionBudget.minAvailable=1 || true
}

phase_cluster() {
    section "Phase 2 — Cluster"
    cluster_up
    import_images

    # Server-side dry-run is the first thing that validates the manifests against
    # a real API server: schema, field names, admission. helm lint does not.
    check "loom passes server-side validation" \
        helm upgrade --install "$LOOM_RELEASE" "$REPO_ROOT/helm/loom" \
            -n "$NAMESPACE" -f "$SCRIPT_DIR/values/loom.yaml" --dry-run=server || true
    check "cortex passes server-side validation" \
        helm upgrade --install "$CORTEX_RELEASE" "$REPO_ROOT/helm/cortex" \
            -n "$NAMESPACE" -f "$SCRIPT_DIR/values/cortex.yaml" \
            --set loom.token=placeholder --dry-run=server || true
}

# The media fixture has to exist before Cortex mounts the claim: the volume is
# ReadWriteOnce, so the loader and the worker cannot hold it at the same time.
phase_media() {
    section "Phase 3 — Shared media volume"

    kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/manifests/media.yaml" >/dev/null \
        || fatal "could not create the media PVC and loader"

    wait_for 120 "media-loader pod" \
        kubectl -n "$NAMESPACE" wait --for=condition=Ready pod/media-loader --timeout=10s \
        || { fail "media loader never became ready"; return 1; }

    kubectl -n "$NAMESPACE" cp "$SCRIPT_DIR/fixtures/media/sample.jpg" \
        media-loader:/media/sample.jpg >/dev/null 2>&1 \
        || { fail "could not copy the fixture into the media volume"; return 1; }

    # Writable by Cortex's uid 1000, not merely readable: the hash nodes cache
    # their digest on the file as an extended attribute, and setting a user.*
    # xattr requires write permission on the file itself.
    kubectl -n "$NAMESPACE" exec media-loader -- sh -c \
        'chown -R 1000:0 /media && chmod -R u+rwX,g+rwX,o+rX /media' >/dev/null 2>&1 || true

    local listing
    listing="$(kubectl -n "$NAMESPACE" exec media-loader -- ls -l /media/sample.jpg 2>&1)"
    check_contains "fixture is present on the media volume" "$listing" "sample.jpg" || true

    # Detach so the RWO claim is free for the Cortex pod.
    kubectl -n "$NAMESPACE" delete pod media-loader --wait=true --timeout=60s >/dev/null 2>&1 || true
    log "media-loader removed, claim released"
}

phase_deploy_loom() {
    section "Phase 4 — Deploy Loom"

    helm upgrade --install "$LOOM_RELEASE" "$REPO_ROOT/helm/loom" \
        -n "$NAMESPACE" --create-namespace \
        -f "$SCRIPT_DIR/values/loom.yaml" >/dev/null \
        || { fail "helm install loom"; return 1; }
    pass "helm install loom"

    check "bundled postgres becomes ready" \
        kubectl -n "$NAMESPACE" rollout status "statefulset/${LOOM_RELEASE}-postgresql" --timeout=300s \
        || { dump_diagnostics; return 1; }

    check "loom deployment becomes ready" \
        kubectl -n "$NAMESPACE" rollout status "deployment/${LOOM_RELEASE}" --timeout=480s \
        || { dump_diagnostics; return 1; }

    # The chart claims three volumes; a chart that renders them but leaves them
    # unbound would still pass a rollout check on some provisioners.
    local bound
    bound="$(kubectl -n "$NAMESPACE" get pvc -o json \
        | jq -r '[.items[] | select(.metadata.name | startswith("loom-")) | select(.status.phase=="Bound")] | length')"
    check_eq "loom PVCs are bound (config, keystore, uploads, postgres data)" "4" "$bound" || true

    # The Service must resolve to the server pod and nothing else.
    #
    # This is a specific regression guard: the bundled Postgres StatefulSet
    # carries the same app.kubernetes.io/name and /instance labels as the server,
    # so a Service selector that does not also pin the component silently picks up
    # `loom-postgresql-0` as a second endpoint. The symptom is not a failed deploy
    # — it is every other REST, UI and Cortex connection being balanced onto a pod
    # that does not serve port 8092.
    local endpoints
    endpoints="$(kubectl -n "$NAMESPACE" get endpointslices \
        -l "kubernetes.io/service-name=${LOOM_RELEASE}" -o json \
        | jq -r '[.items[].endpoints[]?.targetRef.name] | sort | join(",")')"
    if [[ "$endpoints" == *"postgresql"* ]]; then
        fail "svc/${LOOM_RELEASE} resolves only to the server pod"
        printf '      the Service also selects the Postgres pod: %s\n' "$endpoints" >&2
        printf '      (add app.kubernetes.io/component: server to the selector)\n' >&2
    else
        pass "svc/${LOOM_RELEASE} resolves only to the server pod ($endpoints)"
    fi
}

phase_bootstrap() {
    section "Phase 5 — API bootstrap"

    port_forward_start

    api GET /api/v1/health
    check_eq "GET /api/v1/health returns 200" "200" "$API_CODE" || true

    if ! loom_login "$LOOM_ADMIN_PASSWORD"; then
        fail "admin login with auth.initialPassword from the chart"
        return 1
    fi
    pass "admin login with auth.initialPassword from the chart"

    local key
    key="$(loom_mint_api_key "helm-harness-$(date +%s)")" || true
    check_nonempty "POST /api/v1/tokens mints an API key" "$key" || true

    # Documented behaviour: "API keys behave like JWT tokens and are passed the
    # same way in the Authorization header" (docs/loom/authentication). They are
    # not — only MCPAuthenticationHandler resolves a key via TokenDao#findByToken,
    # so a key authenticates against /mcp/* and gets a 401 everywhere on REST.
    xfail "the minted API key authenticates against the REST API" \
        "REST has no API-key path; keys work only on /mcp/*" \
        _api_key_authenticates "$key"

    # So the worker gets the admin JWT, which is the other token the playbooks
    # name and the one the REST surface actually accepts. It expires after
    # LOOM_TOKEN_EXPIRATION_TIME (one hour), comfortably longer than a run.
    CORTEX_TOKEN="$LOOM_TOKEN"
}

_ledger_rows_written() {
    [[ "$1" == *sha512* && "$1" == *md5* && "$1" == *metadata* ]]
}

_api_key_authenticates() {
    curl -fsS --max-time 10 -o /dev/null \
        -H "Authorization: Bearer $1" "$LOOM_URL/api/v1/me" 2>/dev/null
}

phase_deploy_cortex() {
    section "Phase 6 — Deploy Cortex"

    helm upgrade --install "$CORTEX_RELEASE" "$REPO_ROOT/helm/cortex" \
        -n "$NAMESPACE" \
        -f "$SCRIPT_DIR/values/cortex.yaml" \
        --set-string "loom.token=$CORTEX_TOKEN" >/dev/null \
        || { fail "helm install cortex"; return 1; }
    pass "helm install cortex"

    # Readiness IS the registration assertion: the chart's readiness probe hits
    # /api/ready, which only answers 200 once the worker has registered with Loom.
    check "cortex statefulset becomes ready (implies it registered with Loom)" \
        kubectl -n "$NAMESPACE" rollout status "statefulset/${CORTEX_RELEASE}" --timeout=300s \
        || { dump_diagnostics; return 1; }

    # And the volume the whole combined setup depends on is really mounted.
    local ls_out
    ls_out="$(kubectl -n "$NAMESPACE" exec "${CORTEX_RELEASE}-0" -- ls /media 2>&1 || true)"
    check_contains "cortex can read the shared media volume" "$ls_out" "sample.jpg" || true
}

phase_verify_worker() {
    section "Phase 7 — Worker registration"

    # Loom's own view, rather than the worker's self-report.
    if wait_for 120 "the worker to appear in GET /api/v1/processors" \
        _processor_registered; then
        pass "loom lists the cortex worker"
    else
        fail "loom lists the cortex worker"
        printf '      last response: %s\n' "${API_BODY:0:400}" >&2
    fi

    api GET /api/v1/processors
    local node_id state
    node_id="$(json_field "$API_BODY" '.data[0].nodeId')"
    state="$(json_field "$API_BODY" '.data[0].state')"
    check_eq "worker node id is the StatefulSet pod name" "${CORTEX_RELEASE}-0" "$node_id" || true
    check_eq "worker state is ONLINE" "ONLINE" "$state" || true

    # The whitelist from values/cortex.yaml has to have made it through the chart
    # into CORTEX_NODE_WHITELIST and up to Loom.
    local whitelist
    whitelist="$(printf '%s' "$API_BODY" | jq -r '.data[0].nodeWhitelist // [] | sort | join(",")')"
    check_eq "chart nodeKinds reached loom as the worker whitelist" \
        "filesystem-source,md5,metadata,sha512" "$whitelist" || true
}

_processor_registered() {
    api GET /api/v1/processors
    [[ "$API_CODE" == "200" ]] && \
        [[ "$(printf '%s' "$API_BODY" | jq -r '[.data[]? | select(.state=="ONLINE")] | length')" != "0" ]]
}

phase_pipeline() {
    section "Phase 8 — Pipeline execution"

    local fixture="$SCRIPT_DIR/fixtures/media/sample.jpg"
    local local_sha
    local_sha="$(sha512sum "$fixture" | awk '{print $1}')"

    local library_uuid
    library_uuid="$(loom_create_library "harness-library")" || { fail "create a library"; return 1; }
    check_nonempty "create a library" "$library_uuid" || true

    # The asset must exist before the run: nodes attach results to known assets,
    # resolved by SHA-512. They never create them.
    local asset_uuid
    asset_uuid="$(loom_upload_asset "$fixture" "$library_uuid")" || { fail "upload the fixture asset"; return 1; }
    check_nonempty "upload the fixture asset" "$asset_uuid" || true

    local pipeline_uuid
    pipeline_uuid="$(loom_create_pipeline "harness-pipeline" "$SCRIPT_DIR/fixtures/pipeline.json")" \
        || { fail "create the pipeline"; return 1; }
    check_nonempty "create the pipeline" "$pipeline_uuid" || true

    # A graph no online worker accepts is rejected with 503 rather than queued,
    # so a 202 here already proves scheduling saw a capable worker.
    api POST "/api/v1/pipelines/$pipeline_uuid/run" '{}'
    if [[ "$API_CODE" != "202" && "$API_CODE" != "200" ]]; then
        fail "trigger the pipeline run (HTTP $API_CODE)"
        printf '      %s\n' "${API_BODY:0:400}" >&2
        return 1
    fi
    pass "trigger the pipeline run (HTTP $API_CODE)"

    local run_uuid dispatched
    run_uuid="$(json_field "$API_BODY" .runUuid)"
    dispatched="$(json_field "$API_BODY" .dispatched)"
    check_nonempty "run was assigned a uuid" "$run_uuid" || true
    check_eq "run was dispatched to a worker" "true" "$dispatched" || true
    [[ -n "$run_uuid" ]] || return 1

    local status
    status="$(loom_await_run "$pipeline_uuid" "$run_uuid" 300)" || true
    case "$status" in
        COMPLETED|SUCCEEDED|SUCCESS) pass "run reached a successful terminal state ($status)" ;;
        *) fail "run reached a successful terminal state (was: $status)"
           api GET "/api/v1/pipelines/$pipeline_uuid/runs/$run_uuid"
           printf '      %s\n' "${API_BODY:0:600}" >&2 ;;
    esac

    # A run whose source enumerated nothing still reports SUCCESS, so the item
    # count is a separate assertion. This is how a worker that cannot read the
    # media volume — or cannot write its own index — shows up.
    api GET "/api/v1/pipelines/$pipeline_uuid/runs/$run_uuid/items"
    local item_count
    item_count="$(printf '%s' "$API_BODY" | jq -r '[.data[]?] | length' 2>/dev/null || echo 0)"
    if [[ "${item_count:-0}" -ge 1 ]]; then
        pass "the run processed at least one item ($item_count)"
    else
        fail "the run processed at least one item"
        printf '      the source enumerated nothing; the run went green over an empty set\n' >&2
    fi

    # The run reporting success is not the same as results being persisted —
    # persistence is best-effort by design, so it needs its own assertion.
    api GET "/api/v1/assets/$asset_uuid/node-results"
    check_eq "asset node-results are readable" "200" "$API_CODE" || true

    local kinds
    kinds="$(printf '%s' "$API_BODY" | jq -r '[.data[]?.nodeKind] | sort | unique | join(",")' 2>/dev/null || echo "")"
    log "node-result kinds recorded: ${kinds:-<none>}"

    # Every node calls recordNodeResult, but it is a silent no-op when the node's
    # injected LoomClient is null or the asset does not resolve
    # (AbstractMediaNode#recordNodeResult: `if (asset == null || client() == null)
    # return;`). Nothing here is chart-side: the worker registers, its token
    # authenticates against REST, and from inside the pod
    # GET /api/v1/assets/sha512/<hash> returns 200 for this very asset.
    xfail "the run persisted node-result ledger rows" \
        "runs succeed but write no ledger rows; recordNodeResult no-ops silently — see README" \
        _ledger_rows_written "$kinds"

    # Cross-check the worker hashed the same bytes the harness uploaded. This is
    # what proves the shared media path really pointed at the right file rather
    # than at some other file that happened to exist.
    # The hash lives under .hashes, and the filename/size under .file — the asset
    # model is grouped, not flat.
    api GET "/api/v1/assets/$asset_uuid"
    local stored_sha stored_name
    stored_sha="$(json_field "$API_BODY" .hashes.sha512)"
    stored_name="$(json_field "$API_BODY" .file.filename)"
    check_eq "asset sha512 matches the fixture" "$local_sha" "$stored_sha" || true
    check_eq "asset filename survived the upload" "sample.jpg" "$stored_name" || true
}

# A deployment that only works until the first pod restart is not deployable.
# Kubernetes restarts pods routinely — node drains, evictions, liveness probes,
# `helm upgrade` — so surviving one is part of what these charts have to prove.
#
# The volumes and the database are deliberately kept: this asserts that Loom
# comes back against ITS OWN existing state, which is the whole point.
phase_restart() {
    section "Phase 9 — Restart resilience"

    kubectl -n "$NAMESPACE" rollout restart "deployment/${LOOM_RELEASE}" >/dev/null 2>&1 \
        || { fail "trigger a loom rollout restart"; return 1; }

    if kubectl -n "$NAMESPACE" rollout status "deployment/${LOOM_RELEASE}" --timeout=300s >/dev/null 2>&1; then
        pass "loom comes back after a restart"
    else
        fail "loom comes back after a restart"
        printf '      %s\n' "the pod did not become ready again against its existing database" >&2
        kubectl -n "$NAMESPACE" logs "deployment/${LOOM_RELEASE}" --tail=15 2>&1 \
            | grep -iE "error|caused by|exception" | head -5 | sed 's/^/      /' >&2
        return 1
    fi

    # The API has to work again, not just the probe.
    port_forward_start
    api GET /api/v1/health
    check_eq "loom serves the API again after a restart" "200" "$API_CODE" || true

    # And the worker has to re-establish its session rather than sit there
    # believing it is still connected.
    if wait_for 120 "the worker to re-register" _processor_registered; then
        pass "cortex re-registers after the loom restart"
    else
        fail "cortex re-registers after the loom restart"
    fi
}

# ── Extended suite ────────────────────────────────────────────────────

# Several phases upgrade the Loom release. helm does not remember --set between
# upgrades, so settings accumulate here and every upgrade replays all of them.
LOOM_EXTRA_SET=()

_loom_upgrade() {
    helm upgrade --install "$LOOM_RELEASE" "$REPO_ROOT/helm/loom" \
        -n "$NAMESPACE" -f "$SCRIPT_DIR/values/loom.yaml" \
        ${LOOM_EXTRA_SET[@]+"${LOOM_EXTRA_SET[@]}"} "$@" >/dev/null
}

# Two things at once: more than one worker, and workers that are not pinned to the
# node holding a ReadWriteOnce claim.
#
# The media claim used by the core phases is RWO and local-path pins its PV to one
# node, so a second replica could only ever land there. Switching to the shared
# hostPath — the same Docker named volume mounted into every node — removes that
# constraint, which is what makes this a real multi-node test rather than two pods
# on one machine. mountPath stays /media so fixtures/pipeline.json is unchanged.
phase_scale_out() {
    section "Phase 10 — Multi-node and worker scale-out"

    local nodes
    nodes="$(kubectl get nodes --no-headers | wc -l | tr -d ' ')"
    if [[ "${nodes:-1}" -ge 2 ]]; then
        pass "cluster is multi-node ($nodes nodes)"
    else
        fail "cluster is multi-node"
        printf '      only %s node(s); scale-out cannot prove cross-node scheduling\n' "$nodes" >&2
    fi

    helm upgrade --install "$CORTEX_RELEASE" "$REPO_ROOT/helm/cortex" \
        -n "$NAMESPACE" -f "$SCRIPT_DIR/values/cortex.yaml" \
        --set-string "loom.token=$CORTEX_TOKEN" \
        --set replicaCount=2 \
        --set media.existingClaim= \
        --set "media.hostPath=$SHARED_MEDIA_PATH" \
        --set media.mountPath=/media \
        --set media.readOnly=false >/dev/null \
        || { fail "scale cortex to 2 replicas"; return 1; }
    pass "scale cortex to 2 replicas on the shared media volume"

    check "both worker replicas become ready" \
        kubectl -n "$NAMESPACE" rollout status "statefulset/${CORTEX_RELEASE}" --timeout=300s \
        || { dump_diagnostics; return 1; }

    # CORTEX_NODE_ID comes from metadata.name via fieldRef. Loom keys leases and
    # run attribution on it and rejects a duplicate, so uniqueness is the property
    # that makes the StatefulSet the right kind for this workload.
    local ids
    if wait_for 180 "both workers to register" _two_processors_online; then
        pass "both workers registered with loom"
    else
        fail "both workers registered with loom"
    fi

    api GET /api/v1/processors
    ids="$(printf '%s' "$API_BODY" | jq -r '[.data[]?.nodeId] | sort | join(",")')"
    check_eq "each replica registered under its own pod name" \
        "${CORTEX_RELEASE}-0,${CORTEX_RELEASE}-1" "$ids" || true

    local distinct total
    distinct="$(printf '%s' "$API_BODY" | jq -r '[.data[]?.nodeId] | unique | length')"
    total="$(printf '%s' "$API_BODY" | jq -r '[.data[]?.nodeId] | length')"
    check_eq "node ids are unique across replicas" "$total" "$distinct" || true

    # Both pods read the same media through a hostPath on whichever node they run.
    local seen=0 pod
    for pod in "${CORTEX_RELEASE}-0" "${CORTEX_RELEASE}-1"; do
        kubectl -n "$NAMESPACE" exec "$pod" -- ls /media 2>/dev/null | grep -q sample.jpg && seen=$((seen + 1))
    done
    check_eq "every replica can read the shared media volume" "2" "$seen" || true

    local placement
    placement="$(kubectl -n "$NAMESPACE" get pods -l app.kubernetes.io/name=cortex \
        -o jsonpath='{range .items[*]}{.metadata.name}={.spec.nodeName}{" "}{end}')"
    log "worker placement: $placement"
}

_two_processors_online() {
    api GET /api/v1/processors
    [[ "$API_CODE" == "200" ]] && \
        [[ "$(printf '%s' "$API_BODY" | jq -r '[.data[]? | select(.state=="ONLINE")] | length')" == "2" ]]
}

# Renders correctly is not the same as routes correctly. This drives a real
# request through the cluster's ingress controller to the Service and back.
phase_ingress() {
    section "Phase 11 — Ingress"

    LOOM_EXTRA_SET+=(--set ingress.enabled=true --set "ingress.host=$INGRESS_HOST")
    _loom_upgrade || { fail "enable the ingress"; return 1; }
    pass "enable the ingress"

    local backend
    backend="$(kubectl -n "$NAMESPACE" get ingress "$LOOM_RELEASE" \
        -o jsonpath='{.spec.rules[0].http.paths[0].backend.service.name}:{.spec.rules[0].http.paths[0].backend.service.port.number}' 2>/dev/null)"
    check_eq "the ingress targets the loom service on the REST port" "${LOOM_RELEASE}:8092" "$backend" || true

    local host
    host="$(kubectl -n "$NAMESPACE" get ingress "$LOOM_RELEASE" -o jsonpath='{.spec.rules[0].host}' 2>/dev/null)"
    check_eq "the ingress carries the configured host" "$INGRESS_HOST" "$host" || true

    # Through k3d's published loadbalancer port, not a port-forward: this is the
    # only assertion in the suite that exercises the real ingress data path.
    if wait_for 150 "the ingress to route" _ingress_serves; then
        pass "GET /api/v1/health through the ingress returns 200"
    else
        fail "GET /api/v1/health through the ingress returns 200"
        printf '      last status: %s (host %s via 127.0.0.1:%s)\n' \
            "$(curl -s -o /dev/null -w '%{http_code}' -H "Host: $INGRESS_HOST" \
                "http://127.0.0.1:${INGRESS_LOCAL_PORT}/api/v1/health" 2>/dev/null)" \
            "$INGRESS_HOST" "$INGRESS_LOCAL_PORT" >&2
        kubectl -n kube-system get pods 2>&1 | grep -i traefik | sed 's/^/      /' >&2
    fi
}

_ingress_serves() {
    [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        -H "Host: $INGRESS_HOST" \
        "http://127.0.0.1:${INGRESS_LOCAL_PORT}/api/v1/health" 2>/dev/null)" == "200" ]]
}

# The sandbox is a unit: the env var alone leaves a server that cannot create
# runners. This asserts the guardrail bundle actually grants — and confines — what
# the chat agent needs, without involving the chat agent.
phase_sandbox() {
    section "Phase 12 — Sandbox RBAC"

    LOOM_EXTRA_SET+=(--set sandbox.enabled=true --set sandbox.createNamespace=true)
    _loom_upgrade || { fail "enable the sandbox bundle"; return 1; }
    pass "enable the sandbox bundle"

    check "the runner namespace exists" \
        kubectl get namespace "$SANDBOX_NAMESPACE" || true

    local kind missing=""
    for kind in role rolebinding resourcequota limitrange networkpolicy; do
        kubectl -n "$SANDBOX_NAMESPACE" get "$kind" -o name 2>/dev/null | grep -q . \
            || missing="$missing $kind"
    done
    check_eq "the guardrail bundle is complete (Role, RoleBinding, Quota, LimitRange, NetworkPolicy)" \
        "" "$missing" || true

    local sa="system:serviceaccount:${NAMESPACE}:${LOOM_RELEASE}"

    check "loom's service account may create runner pods" \
        kubectl auth can-i create pods --as="$sa" -n "$SANDBOX_NAMESPACE" || true
    check "loom's service account may delete runner pods" \
        kubectl auth can-i delete pods --as="$sa" -n "$SANDBOX_NAMESPACE" || true

    # The other half of the grant: it must not reach beyond that namespace.
    if kubectl auth can-i create pods --as="$sa" -n "$NAMESPACE" >/dev/null 2>&1; then
        fail "the grant is confined to the runner namespace"
        printf '      the service account can also create pods in %s\n' "$NAMESPACE" >&2
    else
        pass "the grant is confined to the runner namespace"
    fi

    # And it works in practice, not just according to the authorizer.
    if kubectl -n "$SANDBOX_NAMESPACE" --as="$sa" run runner-probe \
        --image=busybox:1.36 --restart=Never --command -- sleep 60 >/dev/null 2>&1; then
        pass "a runner pod can be created as loom's service account"
    else
        fail "a runner pod can be created as loom's service account"
    fi

    # The LimitRange is what stops a runner from being unbounded, so a pod created
    # without resources must come back with them.
    local defaulted
    defaulted="$(kubectl -n "$SANDBOX_NAMESPACE" get pod runner-probe \
        -o jsonpath='{.spec.containers[0].resources.limits.cpu}' 2>/dev/null)"
    check_nonempty "the LimitRange defaulted the runner's cpu limit" "$defaulted" || true

    local quota
    quota="$(kubectl -n "$SANDBOX_NAMESPACE" get resourcequota -o jsonpath='{.items[0].status.hard.pods}' 2>/dev/null)"
    check_nonempty "the ResourceQuota caps the number of runner pods" "$quota" || true

    kubectl -n "$SANDBOX_NAMESPACE" delete pod runner-probe --wait=false >/dev/null 2>&1 || true
}

# Phases 11 and 12 already upgraded the release twice; this asserts the property
# those upgrades depend on and that the server survived them.
phase_upgrade() {
    section "Phase 13 — Upgrade path"

    local revisions
    revisions="$(helm history "$LOOM_RELEASE" -n "$NAMESPACE" -o json 2>/dev/null | jq -r 'length')"
    if [[ "${revisions:-0}" -ge 2 ]]; then
        pass "the release upgraded in place across revisions ($revisions)"
    else
        fail "the release upgraded in place across revisions"
    fi

    check_eq "the latest revision is deployed" "deployed" \
        "$(helm history "$LOOM_RELEASE" -n "$NAMESPACE" -o json 2>/dev/null | jq -r '.[-1].status')" || true

    # A value change that touches the pod template, so the rollout is real work
    # rather than a no-op recorded as a revision.
    LOOM_EXTRA_SET+=(--set podAnnotations.harness-upgrade=phase13)
    _loom_upgrade || { fail "upgrade with a pod-template change"; return 1; }
    pass "upgrade with a pod-template change"

    check "the deployment rolls out after the upgrade" \
        kubectl -n "$NAMESPACE" rollout status "deployment/${LOOM_RELEASE}" --timeout=420s \
        || { dump_diagnostics; return 1; }

    port_forward_start
    api GET /api/v1/health
    check_eq "the API still answers after the upgrade" "200" "$API_CODE" || true

    # spec.selector is immutable, so the 0.1.0 -> 0.2.0 component-label change
    # cannot be an in-place upgrade at all. Nothing to assert against a released
    # 0.1.0 here; the constraint is recorded in helm/loom/Chart.yaml and
    # spec/features/helm/HELM_LOOM.md.
    log "note: upgrading a pre-0.2.0 release fails on the immutable selector — reinstall instead"
}

# The database.* branch, deployed rather than rendered. Everything about how Loom
# is wired to its database changes here: no bundled StatefulSet, and the password
# comes from a different secret under a different key.
phase_external_db() {
    section "Phase 14 — External database"

    kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/manifests/external-postgres.yaml" >/dev/null \
        || { fail "deploy the external postgres"; return 1; }
    check "the external postgres becomes ready" \
        kubectl -n "$NAMESPACE" rollout status deployment/external-postgres --timeout=300s \
        || return 1

    helm upgrade --install "$LOOM_EXT_RELEASE" "$REPO_ROOT/helm/loom" \
        -n "$NAMESPACE" -f "$SCRIPT_DIR/values/loom-external-db.yaml" >/dev/null \
        || { fail "install loom against the external database"; return 1; }
    pass "install loom against the external database"

    # templates/postgresql.yaml must render nothing for this release.
    if kubectl -n "$NAMESPACE" get statefulset "${LOOM_EXT_RELEASE}-postgresql" >/dev/null 2>&1; then
        fail "no bundled postgres is created when postgresql.enabled is false"
    else
        pass "no bundled postgres is created when postgresql.enabled is false"
    fi

    # The external branch uses a different secret and key than the bundled one.
    local key
    key="$(kubectl -n "$NAMESPACE" get secret "${LOOM_EXT_RELEASE}-db" \
        -o jsonpath='{.data.db-password}' 2>/dev/null)"
    check_nonempty "the db-password secret key exists for the external branch" "${key:0:8}" || true

    check "loom becomes ready against the external database" \
        kubectl -n "$NAMESPACE" rollout status "deployment/${LOOM_EXT_RELEASE}" --timeout=480s \
        || { dump_diagnostics; return 1; }

    port_forward_start "$LOOM_EXT_RELEASE" "$LOOM_EXT_LOCAL_PORT"
    api GET /api/v1/health
    check_eq "the external-database instance serves its API" "200" "$API_CODE" || true

    # Proves it really migrated and seeded that database, not merely opened a port.
    if loom_login "$LOOM_ADMIN_PASSWORD"; then
        pass "the external-database instance migrated and seeded its schema"
    else
        fail "the external-database instance migrated and seeded its schema"
    fi

    # Hand the forward back to the main release for anything that follows.
    port_forward_start
    loom_login "$LOOM_ADMIN_PASSWORD" >/dev/null 2>&1 || true
}

# What Loom does when its database is simply not there, with the chart's guard
# switched off. The correct answer is to exit non-zero so Kubernetes restarts it.
phase_missing_db() {
    section "Phase 15 — Behaviour with no database"

    helm upgrade --install "$LOOM_NODB_RELEASE" "$REPO_ROOT/helm/loom" \
        -n "$NAMESPACE" -f "$SCRIPT_DIR/values/loom-nodb.yaml" >/dev/null \
        || { fail "install loom with an unreachable database"; return 1; }
    pass "install loom with an unreachable database"

    # It must at least schedule and start — a failure to do that would mean the
    # values are wrong rather than that Loom mishandles the outage.
    wait_for 180 "the pod to start" _nodb_container_started \
        || { fail "the no-database pod started"; return 1; }
    pass "the no-database pod started"

    log "watching for up to 150s to see whether the process exits on its own"
    local deadline=$((SECONDS + 150))
    while (( SECONDS < deadline )); do
        _nodb_exited_nonzero && break
        sleep 5
    done

    xfail "loom exits non-zero when its database is unreachable" \
        "LoomImpl#run logs the boot failure then calls dontExit(); the process stays alive with no listener" \
        _nodb_exited_nonzero

    log "observed state: $(_nodb_state)"
    log "restart count: $(_nodb_restarts) (a healthy crash-loop would be climbing)"
}

_nodb_pod() {
    kubectl -n "$NAMESPACE" get pods -l "app.kubernetes.io/instance=${LOOM_NODB_RELEASE}" \
        -o jsonpath='{.items[0].metadata.name}' 2>/dev/null
}

_nodb_container_started() {
    local p; p="$(_nodb_pod)"
    [[ -n "$p" ]] && kubectl -n "$NAMESPACE" get pod "$p" \
        -o jsonpath='{.status.containerStatuses[0].started}' 2>/dev/null | grep -q true
}

_nodb_state() {
    local p; p="$(_nodb_pod)"
    [[ -n "$p" ]] && kubectl -n "$NAMESPACE" get pod "$p" \
        -o jsonpath='{.status.containerStatuses[0].state}' 2>/dev/null
}

_nodb_restarts() {
    local p; p="$(_nodb_pod)"
    [[ -n "$p" ]] && kubectl -n "$NAMESPACE" get pod "$p" \
        -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null
}

_nodb_exited_nonzero() {
    local p code; p="$(_nodb_pod)"
    [[ -n "$p" ]] || return 1
    code="$(kubectl -n "$NAMESPACE" get pod "$p" \
        -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}' 2>/dev/null)"
    [[ -z "$code" ]] && code="$(kubectl -n "$NAMESPACE" get pod "$p" \
        -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null)"
    [[ -n "$code" && "$code" != "0" ]]
}

# ── Main ──────────────────────────────────────────────────────────────

main() {
    if [[ "$DOWN_ONLY" == "true" ]]; then
        ensure_tools
        cluster_down
        exit 0
    fi

    printf '%s\n' "${C_BOLD}MetaLoom Helm chart harness${C_RESET}"
    log "repo:      $REPO_ROOT"
    log "cluster:   $CLUSTER_NAME (namespace $NAMESPACE)"
    log "suites:    $SUITES"

    section "Phase 0 — Preflight"
    ensure_tools
    [[ "$BUILD_IMAGES" == "true" ]] && build_images
    require_images

    trap cleanup EXIT

    phase_chart_validation || true
    phase_cluster          || true

    if _suite_enabled core; then
        phase_media            || true
        phase_deploy_loom      || true
        phase_bootstrap        || true
        phase_deploy_cortex    || true
        phase_verify_worker    || true
        phase_pipeline         || true
        phase_restart          || true
    fi

    # Extended builds on what core deployed: the worker token, the running Loom
    # and the media fixture all come from those phases.
    if _suite_enabled extended; then
        if ! _suite_enabled core; then
            fatal "the extended suite builds on core — run --suite core,extended"
        fi
        phase_scale_out    || true
        phase_ingress      || true
        phase_sandbox      || true
        phase_upgrade      || true
        phase_external_db  || true
        phase_missing_db   || true
    fi

    section "Summary"
    local issue
    if (( KNOWN_ISSUES > 0 )); then
        printf '%s\n' "${C_YELLOW}${KNOWN_ISSUES} known issue(s), not counted as failures:${C_RESET}"
        for issue in "${KNOWN_NAMES[@]}"; do
            printf '%s\n' "  ${C_YELLOW}⊘${C_RESET} $issue"
        done
        printf '\n'
    fi
    if (( TESTS_FAILED == 0 )); then
        printf '%s\n\n' "${C_GREEN}${C_BOLD}All ${TESTS_RUN} checks passed.${C_RESET}"
        exit 0
    fi
    printf '%s\n' "${C_RED}${C_BOLD}${TESTS_FAILED} of ${TESTS_RUN} checks failed:${C_RESET}"
    local name
    for name in "${FAILED_NAMES[@]}"; do
        printf '%s\n' "  ${C_RED}✗${C_RESET} $name"
    done
    printf '\n'
    exit 1
}

main "$@"
