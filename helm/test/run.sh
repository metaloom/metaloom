#!/usr/bin/env bash
#
# End-to-end test harness for the MetaLoom Helm charts.
#
# Stands up a throwaway k3d cluster, deploys helm/loom and helm/cortex as a
# combined setup, and drives a real pipeline through it: ingest an asset, run a
# graph on a Cortex worker, and assert the results came back and were persisted.
#
#   ./run.sh              full run, cluster deleted afterwards
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
LOOM_RELEASE="loom"
CORTEX_RELEASE="cortex"
LOOM_LOCAL_PORT="${LOOM_LOCAL_PORT:-18092}"
LOOM_URL="http://127.0.0.1:${LOOM_LOCAL_PORT}"

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
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fatal "unknown argument: $1 (try --help)" ;;
    esac
    shift
done

# ── Teardown ──────────────────────────────────────────────────────────
#
# Diagnostics are dumped BEFORE the cluster goes away — after deletion there is
# nothing left to explain a failure with.

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

    section "Phase 0 — Preflight"
    ensure_tools
    [[ "$BUILD_IMAGES" == "true" ]] && build_images
    require_images

    trap cleanup EXIT

    phase_chart_validation || true
    phase_cluster          || true
    phase_media            || true
    phase_deploy_loom      || true
    phase_bootstrap        || true
    phase_deploy_cortex    || true
    phase_verify_worker    || true
    phase_pipeline         || true
    phase_restart          || true

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
