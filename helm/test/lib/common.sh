# shellcheck shell=bash
#
# Shared plumbing for the Helm chart test harness: logging, assertions, waiting
# and failure diagnostics.
#
# Sourced by run.sh — not executable on its own.

# ── Output ────────────────────────────────────────────────────────────

if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'
else
    C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_DIM=""; C_BOLD=""
fi

log()      { printf '%s\n' "${C_DIM}    $*${C_RESET}"; }
info()     { printf '%s\n' "${C_BLUE}==>${C_RESET} $*"; }
warn()     { printf '%s\n' "${C_YELLOW}WARN:${C_RESET} $*" >&2; }

section() {
    printf '\n%s\n' "${C_BOLD}${C_BLUE}━━━ $* ━━━${C_RESET}"
}

# ── Test accounting ───────────────────────────────────────────────────
#
# Every assertion runs through pass/fail so the harness can report a total
# rather than dying at the first problem and leaving the count unknown.

TESTS_RUN=0
TESTS_FAILED=0
FAILED_NAMES=()
KNOWN_ISSUES=0
KNOWN_NAMES=()

pass() {
    TESTS_RUN=$((TESTS_RUN + 1))
    printf '%s\n' "  ${C_GREEN}✓${C_RESET} $*"
}

fail() {
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    FAILED_NAMES+=("$1")
    printf '%s\n' "  ${C_RED}✗ $*${C_RESET}" >&2
}

# fatal is for harness problems (missing tool, cluster would not start) as
# opposed to a failed assertion about the charts. It aborts.
fatal() {
    printf '\n%s\n' "${C_RED}${C_BOLD}FATAL:${C_RESET} $*" >&2
    exit 1
}

# check <name> <command...> — run a command, record pass/fail, never abort.
check() {
    local name="$1"; shift
    local out
    if out=$("$@" 2>&1); then
        pass "$name"
        return 0
    fi
    fail "$name"
    [[ -n "$out" ]] && printf '%s\n' "$out" | sed 's/^/      /' >&2
    return 1
}

# check_eq <name> <expected> <actual>
check_eq() {
    local name="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        pass "$name"
        return 0
    fi
    fail "$name"
    printf '      expected: %s\n      actual:   %s\n' "$expected" "$actual" >&2
    return 1
}

# check_contains <name> <haystack> <needle>
check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        pass "$name"
        return 0
    fi
    fail "$name"
    printf '      expected to contain: %s\n      actual: %s\n' "$needle" "${haystack:0:400}" >&2
    return 1
}

# check_nonempty <name> <value> — guards against jq returning "null" or "",
# which is the usual shape of a Loom API response that did not carry the field.
check_nonempty() {
    local name="$1" value="$2"
    if [[ -n "$value" && "$value" != "null" ]]; then
        pass "$name ($value)"
        return 0
    fi
    fail "$name"
    printf '      value was empty or null\n' >&2
    return 1
}

# xfail <name> <note> <command...>
#
# A check that asserts documented behaviour which the application does not
# currently deliver. It is reported, but it does not fail the run — otherwise a
# pre-existing application bug would mask the chart regressions this harness
# exists to find.
#
# If the command starts passing, THAT is a failure: the known issue was fixed and
# the xfail should become a normal check.
xfail() {
    local name="$1" note="$2"; shift 2
    if "$@" >/dev/null 2>&1; then
        TESTS_RUN=$((TESTS_RUN + 1))
        TESTS_FAILED=$((TESTS_FAILED + 1))
        FAILED_NAMES+=("$name — known issue now PASSES; promote it to a real check")
        printf '%s\n' "  ${C_RED}✗ $name — known issue now passes, promote it to a real check${C_RESET}" >&2
        return 1
    fi
    KNOWN_ISSUES=$((KNOWN_ISSUES + 1))
    KNOWN_NAMES+=("$name — $note")
    printf '%s\n' "  ${C_YELLOW}⊘${C_RESET} $name ${C_DIM}(known issue: $note)${C_RESET}"
    return 0
}

# ── Waiting ───────────────────────────────────────────────────────────

# wait_for <timeout-seconds> <description> <command...>
#
# Polls until the command succeeds. Prints a progress dot per attempt so a long
# image pull does not look like a hang.
wait_for() {
    local timeout="$1" desc="$2"; shift 2
    local deadline=$(( SECONDS + timeout ))
    printf '%s' "    ${C_DIM}waiting for ${desc}${C_RESET} "
    while (( SECONDS < deadline )); do
        if "$@" >/dev/null 2>&1; then
            printf '%s\n' "${C_GREEN}ok${C_RESET}"
            return 0
        fi
        printf '.'
        sleep 3
    done
    printf '%s\n' "${C_RED}timeout after ${timeout}s${C_RESET}"
    return 1
}

# ── Diagnostics ───────────────────────────────────────────────────────

# dump_diagnostics [namespace] — everything needed to explain a failure without
# a second run: what exists, why pods are unhappy, and the tail of each log.
#
# Called from the failure paths and from the EXIT trap when tests failed, since
# by then the cluster may be about to be deleted.
dump_diagnostics() {
    local ns="${1:-$NAMESPACE}"
    printf '\n%s\n' "${C_YELLOW}━━━ diagnostics (namespace: $ns) ━━━${C_RESET}" >&2

    printf '\n%s\n' "--- workloads ---" >&2
    kubectl -n "$ns" get pods,statefulsets,deployments,pvc,svc -o wide 2>&1 | sed 's/^/  /' >&2

    printf '\n%s\n' "--- recent events ---" >&2
    kubectl -n "$ns" get events --sort-by=.lastTimestamp 2>&1 | tail -30 | sed 's/^/  /' >&2

    local pod
    for pod in $(kubectl -n "$ns" get pods -o name 2>/dev/null); do
        printf '\n%s\n' "--- $pod: not-ready containers ---" >&2
        kubectl -n "$ns" get "$pod" \
            -o jsonpath='{range .status.containerStatuses[?(@.ready==false)]}{.name}{": "}{.state}{"\n"}{end}' 2>&1 \
            | sed 's/^/  /' >&2
        printf '%s\n' "--- $pod: last 40 log lines ---" >&2
        kubectl -n "$ns" logs "$pod" --all-containers --tail=40 2>&1 | sed 's/^/  /' >&2
    done
    printf '\n' >&2
}

# ── Misc ──────────────────────────────────────────────────────────────

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fatal "required command not found: $1${2:+ — $2}"
}

# json_field <json> <jq-filter> — jq with the empty/absent case flattened to ""
# so callers can test with [[ -z ]] instead of matching the string "null".
json_field() {
    printf '%s' "$1" | jq -r "$2 // empty" 2>/dev/null || true
}
