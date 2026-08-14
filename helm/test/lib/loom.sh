# shellcheck shell=bash
#
# Talking to the deployed Loom over its REST API.
#
# Every call goes through api(), which leaves the parsed result in two globals:
#
#   API_CODE   the HTTP status
#   API_BODY   the response body
#
# Returning both matters because the interesting assertions here are about
# status codes (a 503 from /run means "no worker accepted the graph") as much as
# about payloads.
#
# Sourced by run.sh — not executable on its own.

API_CODE=""
API_BODY=""
PF_PID=""

# ── Port-forward ──────────────────────────────────────────────────────
#
# port-forward rather than a NodePort or the k3d loadbalancer: it needs no
# cluster-side objects the charts do not already create, and it works the same
# on a remote cluster.

port_forward_start() {
    port_forward_stop

    kubectl -n "$NAMESPACE" port-forward "svc/$LOOM_RELEASE" "$LOOM_LOCAL_PORT:8092" \
        >"$WORK_DIR/port-forward.log" 2>&1 &
    PF_PID=$!

    if ! wait_for 60 "port-forward on :$LOOM_LOCAL_PORT" _pf_probe; then
        printf '%s\n' "--- port-forward log ---" >&2
        cat "$WORK_DIR/port-forward.log" >&2
        fatal "could not port-forward to svc/$LOOM_RELEASE"
    fi
}

_pf_probe() {
    # /api/v1/health is the one unauthenticated route that also proves the DB is
    # reachable, so a 200 here means more than "the port is open".
    curl -fsS --max-time 5 "$LOOM_URL/api/v1/health" >/dev/null 2>&1
}

port_forward_stop() {
    if [[ -n "$PF_PID" ]] && kill -0 "$PF_PID" 2>/dev/null; then
        kill "$PF_PID" 2>/dev/null || true
        wait "$PF_PID" 2>/dev/null || true
    fi
    PF_PID=""
}

# ── REST ──────────────────────────────────────────────────────────────

# api <method> <path> [json-body]
#
# Always returns 0. Callers inspect API_CODE; a transport failure surfaces as
# API_CODE=000, which no assertion will mistake for success.
api() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-sS -X "$method" --max-time 60 -w '\n%{http_code}')

    [[ -n "${LOOM_TOKEN:-}" ]] && args+=(-H "Authorization: Bearer $LOOM_TOKEN")
    if [[ -n "$body" ]]; then
        args+=(-H 'Content-Type: application/json' -d "$body")
    fi

    local raw
    raw="$(curl "${args[@]}" "$LOOM_URL$path" 2>/dev/null)" || raw=$'\n000'
    API_CODE="${raw##*$'\n'}"
    API_BODY="${raw%$'\n'*}"
}

# api_ok <method> <path> [body] [expected-code]
#
# Same as api() but returns non-zero and prints the body when the status is not
# the expected one — the shape most call sites want.
api_ok() {
    local method="$1" path="$2" body="${3:-}" expect="${4:-200}"
    api "$method" "$path" "$body"
    if [[ "$API_CODE" != "$expect" ]]; then
        printf '      %s %s -> HTTP %s (expected %s)\n      %s\n' \
            "$method" "$path" "$API_CODE" "$expect" "${API_BODY:0:400}" >&2
        return 1
    fi
    return 0
}

# ── Auth ──────────────────────────────────────────────────────────────

# Log in as admin and leave a JWT in LOOM_TOKEN.
loom_login() {
    local pw="$1"
    LOOM_TOKEN=""
    api POST /api/v1/login "$(jq -nc --arg u admin --arg p "$pw" '{username:$u,password:$p}')"
    [[ "$API_CODE" == "200" ]] || {
        printf '      login -> HTTP %s: %s\n' "$API_CODE" "${API_BODY:0:300}" >&2
        return 1
    }
    LOOM_TOKEN="$(json_field "$API_BODY" .token)"
    [[ -n "$LOOM_TOKEN" ]]
}

# Mint a long-lived API key. The worker gets this rather than the JWT: the JWT
# expires (LOOM_TOKEN_EXPIRATION_TIME, one hour by default) and a worker whose
# token expired stops persisting results while still looking healthy.
loom_mint_api_key() {
    local name="$1"
    api POST /api/v1/tokens "$(jq -nc --arg n "$name" '{name:$n}')"
    [[ "$API_CODE" == "200" || "$API_CODE" == "201" ]] || {
        printf '      token create -> HTTP %s: %s\n' "$API_CODE" "${API_BODY:0:300}" >&2
        return 1
    }
    json_field "$API_BODY" .token
}

# ── Ingest ────────────────────────────────────────────────────────────

loom_create_library() {
    local name="$1"
    api POST /api/v1/libraries "$(jq -nc --arg n "$name" '{name:$n}')"
    [[ "$API_CODE" == "200" || "$API_CODE" == "201" ]] || {
        printf '      library create -> HTTP %s: %s\n' "$API_CODE" "${API_BODY:0:300}" >&2
        return 1
    }
    json_field "$API_BODY" .uuid
}

# Upload the fixture so the asset exists before the pipeline runs.
#
# This is not optional plumbing: Cortex nodes attach results to assets Loom
# already knows, resolving them by SHA-512. Without the upload the run still
# reports success while persisting nothing, which is exactly the false green
# this harness exists to catch.
loom_upload_asset() {
    local file="$1" library_uuid="$2"
    local raw
    raw="$(curl -sS -X POST --max-time 120 -w '\n%{http_code}' \
        -H "Authorization: Bearer $LOOM_TOKEN" \
        -F "file=@${file}" \
        -F "libraryUuid=${library_uuid}" \
        "$LOOM_URL/api/v1/assets/upload" 2>/dev/null)" || raw=$'\n000'
    API_CODE="${raw##*$'\n'}"
    API_BODY="${raw%$'\n'*}"
    [[ "$API_CODE" == "200" || "$API_CODE" == "201" ]] || {
        printf '      upload -> HTTP %s: %s\n' "$API_CODE" "${API_BODY:0:300}" >&2
        return 1
    }
    json_field "$API_BODY" .uuid
}

# ── Pipelines ─────────────────────────────────────────────────────────

loom_create_pipeline() {
    local name="$1" definition_file="$2"
    local payload
    payload="$(jq -nc --arg n "$name" --slurpfile def "$definition_file" \
        '{name:$n, description:"helm chart harness", enabled:true, priority:10, definition:$def[0]}')"
    api POST /api/v1/pipelines "$payload"
    [[ "$API_CODE" == "200" || "$API_CODE" == "201" ]] || {
        printf '      pipeline create -> HTTP %s: %s\n' "$API_CODE" "${API_BODY:0:500}" >&2
        return 1
    }
    json_field "$API_BODY" .uuid
}

# Poll a run to a terminal state. Returns the final status on stdout.
loom_await_run() {
    local pipeline_uuid="$1" run_uuid="$2" timeout="${3:-300}"
    local deadline=$(( SECONDS + timeout )) status=""

    while (( SECONDS < deadline )); do
        api GET "/api/v1/pipelines/$pipeline_uuid/runs/$run_uuid"
        if [[ "$API_CODE" == "200" ]]; then
            status="$(json_field "$API_BODY" '.status')"
            case "$status" in
                COMPLETED|SUCCEEDED|SUCCESS|FAILED|CANCELLED|CANCELED|ERROR)
                    printf '%s' "$status"; return 0 ;;
            esac
        fi
        sleep 3
    done
    printf '%s' "${status:-TIMEOUT}"
    return 1
}
