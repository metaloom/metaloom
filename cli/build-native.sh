#!/bin/bash
#
# Build the metaloom CLI as a GraalVM native image.
#
# Mirrors loom/containers/build-containers.sh: same GRAALVM_HOME convention, same guard, so
# there is one way to do a native build in this repo.
#
# Usage:
#   ./build-native.sh [build]    Regenerate metadata and build the native binary (default)
#   ./build-native.sh metadata   Regenerate reflect-config.json from the jar only
#   ./build-native.sh agent      Run the tracing agent to top up the metadata
#   ./build-native.sh smoke      Smoke-test an already-built binary
#   ./build-native.sh install    Copy the binary to ~/.local/bin/metaloom
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GRAALVM_HOME="${GRAALVM_HOME:-/opt/jvm/graalvm-25}"
BINARY="$SCRIPT_DIR/target/metaloom"
JAR="$SCRIPT_DIR/target/metaloom-cli.jar"
METADATA_DIR="$SCRIPT_DIR/src/main/resources/META-INF/native-image/io.metaloom.cli/metaloom-cli"

ensure_graalvm() {
	if [ ! -x "$GRAALVM_HOME/bin/native-image" ]; then
		echo "ERROR: native-image not found at $GRAALVM_HOME/bin/native-image" >&2
		echo "Set GRAALVM_HOME to a GraalVM 25 installation, e.g.:" >&2
		echo "  GRAALVM_HOME=/opt/jvm/graalvm-25 $0" >&2
		exit 1
	fi
}

build_jar() {
	echo "==> Building the shaded jar"
	mvn -f "$REPO_ROOT/pom.xml" -q -DskipTests -pl cli -am package
}

# Jackson reflects over every model class it (de)serializes. The tracing agent only records
# what a scripted run happens to touch, which would silently miss a response type nobody
# exercised - and the failure mode is a runtime error in the shipped binary, not a build
# error. Enumerating the model packages from the jar is exhaustive by construction.
cmd_metadata() {
	if [ ! -f "$JAR" ]; then
		build_jar
	fi
	echo "==> Generating reflect-config.json from $JAR"
	mkdir -p "$METADATA_DIR"

	# Model classes only: DTOs are what Jackson instantiates. Nested classes are included;
	# package-info and Jackson's own generated classes are not.
	unzip -l "$JAR" \
		| grep -oE '(io/metaloom/loom/rest/model|io/metaloom/cli/config)/[A-Za-z0-9/$]+\.class' \
		| sed 's#/#.#g; s#\.class$##' \
		| grep -v 'package-info' \
		| sort -u \
		| awk 'BEGIN { print "[" }
		       { printf "%s  {\n    \"name\": \"%s\",\n    \"allDeclaredFields\": true,\n    \"allDeclaredMethods\": true,\n    \"allDeclaredConstructors\": true\n  }", (NR>1 ? ",\n" : ""), $0 }
		       END { print "\n]" }' \
		> "$METADATA_DIR/reflect-config.json"

	local count
	count=$(grep -c '"name"' "$METADATA_DIR/reflect-config.json")
	echo "    registered $count classes for reflection"
}

cmd_build() {
	ensure_graalvm
	cmd_metadata
	echo "==> Building the native image with $GRAALVM_HOME"
	JAVA_HOME="$GRAALVM_HOME" mvn -f "$REPO_ROOT/pom.xml" -Pnative -DskipTests -pl cli -am package
	echo
	echo "Built $BINARY"
	ls -lh "$BINARY"
}

# Exercises the paths that use reflection - Jackson over the REST models, the YAML config,
# OkHttp's platform probing - so the agent records what the image needs. Failures are
# expected and ignored: an auth error and a connection error are two of the cases we want
# recorded.
cmd_agent() {
	build_jar
	echo "==> Recording reflection metadata into $METADATA_DIR"
	mkdir -p "$METADATA_DIR"

	local tmp_config
	tmp_config="$(mktemp -d)/cli.yml"
	local agent="-agentlib:native-image-agent=config-merge-dir=$METADATA_DIR"
	local run="java $agent -jar $JAR --config $tmp_config"

	set +e
	$run --help                                          > /dev/null
	$run version --client                                > /dev/null
	$run config set server http://localhost:6333         > /dev/null
	$run config list                                     > /dev/null
	$run -o json config list                             > /dev/null
	$run -o yaml config list                             > /dev/null
	# Unreachable server: exercises the transport-failure path.
	$run -s http://127.0.0.1:1 pipeline list             > /dev/null 2>&1
	$run -s http://127.0.0.1:1 -o json user list         > /dev/null 2>&1
	# If a server happens to be up, record the success paths too.
	if [ -n "${METALOOM_AGENT_SERVER:-}" ]; then
		$run -s "$METALOOM_AGENT_SERVER" login --username admin --password finger > /dev/null 2>&1
		for format in table json yaml; do
			$run -s "$METALOOM_AGENT_SERVER" -o "$format" pipeline list  > /dev/null 2>&1
			$run -s "$METALOOM_AGENT_SERVER" -o "$format" user list      > /dev/null 2>&1
			$run -s "$METALOOM_AGENT_SERVER" -o "$format" run stats      > /dev/null 2>&1
		done
		$run -s "$METALOOM_AGENT_SERVER" whoami                          > /dev/null 2>&1
	else
		echo "    (set METALOOM_AGENT_SERVER=http://host:port to also record the success paths)"
	fi
	set -e

	echo "==> Metadata written. Review and commit:"
	ls -la "$METADATA_DIR"
}

# Deliberately not a JUnit test: a native build takes minutes, so this runs on demand rather
# than in the surefire cycle.
cmd_smoke() {
	if [ ! -x "$BINARY" ]; then
		echo "ERROR: $BINARY not found. Run '$0 build' first." >&2
		exit 1
	fi
	local failures=0
	local tmp_config
	tmp_config="$(mktemp -d)/cli.yml"

	check() {
		local description="$1"
		local expected="$2"
		shift 2
		set +e
		"$@" > /dev/null 2>&1
		local actual=$?
		set -e
		if [ "$actual" -eq "$expected" ]; then
			echo "  ok    $description (exit $actual)"
		else
			echo "  FAIL  $description (expected $expected, got $actual)"
			failures=$((failures + 1))
		fi
	}

	echo "==> Smoke-testing $BINARY"
	check "--version"            0  "$BINARY" --version
	check "--help"               0  "$BINARY" --help
	check "pipeline --help"      0  "$BINARY" pipeline --help
	check "run --help"           0  "$BINARY" run --help
	check "version --client"     0  "$BINARY" version --client
	check "config set"           0  "$BINARY" --config "$tmp_config" config set server http://localhost:6333
	check "config list (json)"   0  "$BINARY" --config "$tmp_config" -o json config list
	check "config list (yaml)"   0  "$BINARY" --config "$tmp_config" -o yaml config list
	# 15 = CONNECT_ERROR. This is the case that most often breaks in a native image, because
	# it exercises OkHttp's platform detection and the whole error-mapping path.
	check "unreachable server"  15  "$BINARY" --config "$tmp_config" -s http://127.0.0.1:1 pipeline list
	check "bad usage"            2  "$BINARY" --config "$tmp_config" pipeline frobnicate

	echo
	if [ "$failures" -gt 0 ]; then
		echo "$failures smoke check(s) failed." >&2
		exit 1
	fi
	echo "All smoke checks passed."
}

cmd_install() {
	if [ ! -x "$BINARY" ]; then
		echo "ERROR: $BINARY not found. Run '$0 build' first." >&2
		exit 1
	fi
	mkdir -p "$HOME/.local/bin"
	cp "$BINARY" "$HOME/.local/bin/metaloom"
	echo "Installed to $HOME/.local/bin/metaloom"
	command -v metaloom > /dev/null || echo "NOTE: $HOME/.local/bin is not on your PATH."
}

case "${1:-build}" in
	build)    cmd_build ;;
	metadata) cmd_metadata ;;
	agent)    cmd_agent ;;
	smoke)    cmd_smoke ;;
	install)  cmd_install ;;
	*)
		echo "Usage: $0 [build|metadata|agent|smoke|install]" >&2
		exit 2
		;;
esac
