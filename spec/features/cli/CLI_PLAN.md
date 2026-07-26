# MetaLoom CLI — Creation Plan

> **Status**: implemented on 2026-07-26. See §14 for what landed and what did not.
> **Scope**: replace the dead `loom/cli` stub with a new top-level `cli/` module —
> a PicoCLI + Dagger 2 client for Loom, shipped as a GraalVM (Substrate VM) native image.
> **Related**: [../pipeline/PIPELINE.md](../pipeline/PIPELINE.md),
> [../../loom/RESTAPI.md](../../loom/RESTAPI.md),
> [../../loom/WEBSOCKET.md](../../loom/WEBSOCKET.md),
> [../../cortex/BUILD.md](../../cortex/BUILD.md).

---

## 1. Context

`loom/cli` is dead code: a single class printing `"TBD"`, zero dependencies, referenced by
nothing. Six documents still claim it is the Loom server entry point — it never was; that
is `LoomServerRunner` in `loom/containers/server`.

MetaLoom has no operator-facing command line. Everything an admin might want to do —
trigger a pipeline over a folder, watch it run, list users — requires the React UI or
hand-rolled `curl`. The Cortex CLI (`cortex/cli`) is a *worker launcher*, not a client.

This plan replaces `loom/cli` with a new top-level `cli/` module: a PicoCLI + Dagger 2
client that talks to a Loom server over REST/WebSocket and ships as a GraalVM native image
(fast startup, no JVM required on the target host).

### Three server-side gaps block the requested feature set

| Requested | Reality today | Fix |
| --- | --- | --- |
| Pause / start / stop a pipeline | Only `run` (start) and `runs/:runUuid/cancel` (stop). **No pause.** | Add a `PAUSED` status + an engine gate + two REST routes |
| Trigger for a specific folder | `PipelineRunRequest` carries `pathGlobs` (full re-walk) but not `path` (differential index-backed scan) | Add `path` to the request model |
| List projects | **There is no project entity.** The hierarchy is spaces → libraries → pools → collections → assets | Expose `space`/`library`/`pool`/`collection` commands; no `project` command |

`loom-client` additionally lacks `runPipeline`, `cancelRun`, the version methods, and a
`setPathPrefix` builder setter — the UI and the integration tests hand-roll `fetch` /
`java.net.http` calls against `/run` today.

### Decisions taken

1. Real pause/resume **server-side**, not a CLI-only approximation.
2. `path` added to `PipelineRunRequest` so `--dir` gets the fast differential scan.
3. No `project` command; the real hierarchy is exposed instead.
4. Scope is a **Loom-facing client CLI only**. `cortex/cli` stays untouched — absorbing it
   would drag OpenCV/whisper JNI into the native image.

---

## 2. Phase 0 — Remove `loom/cli` (0.5 d)

- Delete `loom/cli/` entirely (`pom.xml`, `README.md`, `LoomCLI.java`, Eclipse metadata,
  stale `target/`). Nothing depends on `loom-cli` — verified by a word-boundary grep across
  the repo.
- Remove `<module>cli</module>` from `loom/pom.xml` (line ~76).
- Fix the six stale `LoomCLI` references:
  - `loom/doc/src/main/docs/loom/configuration/index.adoc:85`
  - `website/content/english/docs/loom/configuration/index.adoc:192`
  - `spec/loom/SERVER.md:402`
  - `spec/CONTEXT.md:214`
  - `spec/METALOOM.md:88`
  - `spec/loom/BUILD.md:49`

  (`website/dist/` is generated output — do not hand-edit.)
- **Unify picocli.** `bom/pom.xml` declares `4.7.5`; `cortex/pom.xml` overrides to `4.7.7`.
  Because annotation-processor paths interpolate `${pico-cli.version}`, the *processor* and
  the *runtime* currently skew across modules. Bump the bom to `4.7.7` and delete the cortex
  override.

---

## 3. Phase 1 — Server: run-request `path` + client gaps (2–3 d)

### 3.1 `path` on the run request

`loom-shared/rest-model/.../model/pipeline/PipelineRunRequest.java` — add
`private String path;` plus fluent accessors.

`loom/services/rest/.../service/impl/PipelineEndpointService.sourceOptions()` (~line 440)
— insert a `path` branch. Precedence must match what `FilesystemSourceNodeOptions`
(`cortex/nodes/filesystem-source/`) already documents: globs beat path and force a full
re-walk; a bare `path` gets the differential scan against the persisted per-root index.

1. `pathGlobs` → `options["pathGlobs"]` *(existing)*
2. `path` → `options["path"]` — **only when `pathGlobs` was absent**, so a glob request is
   never silently downgraded *(new)*
3. `mediaUuids` → a single asset overwrites `path` + `assetUuid` *(existing; the most
   specific selection wins, and this is the asset auto-trigger path)*

Make `sourceOptions` package-private and unit-test the precedence matrix under
`loom/services/rest/src/test/`. Update `pipelineRunRequest()` in `PipelineExamples.java`
so the field surfaces in the generated OpenAPI.

### 3.2 `loom-client` methods

`loom-client/common/.../method/PipelineMethods.java` — add, implementing in
`loom-client/rest/.../impl/LoomHttpClientImpl.java` next to the existing pipeline block
(lines 578–618):

```java
LoomClientRequest<PipelineRunResponse>         runPipeline(UUID uuid, PipelineRunRequest request);
LoomClientRequest<GenericMessageResponse>      cancelPipelineRun(UUID pipelineUuid, UUID runUuid);
LoomClientRequest<GenericMessageResponse>      pausePipelineRun(UUID pipelineUuid, UUID runUuid);
LoomClientRequest<GenericMessageResponse>      resumePipelineRun(UUID pipelineUuid, UUID runUuid);
LoomClientRequest<PipelineVersionListResponse> listPipelineVersions(UUID pipelineUuid);
LoomClientRequest<PipelineResponse>            loadPipelineVersion(UUID pipelineUuid, int version);
LoomClientRequest<PipelineResponse>            restorePipelineVersion(UUID pipelineUuid, int version, PipelineVersionRestoreRequest request);
```

New `InfoMethods` (`restInfo()` → `RESTInfoResponse`, for `metaloom version`), added to the
`ClientMethods` aggregate.

> ⚠️ Verify `LoomClientRequestImpl.createUrlBuilder` handles the empty path for
> `GET /api/v1` — OkHttp's `addPathSegments("")` appends an empty segment. Add a
> `getRootRequest(Class)` helper on `AbstractLoomOkHttpClient` if the route does not match.

Add the missing `setPathPrefix(String)` to `LoomHttpClientImpl.Builder`. The field and
`getPathPrefix()` already exist on `AbstractLoomClient`; only the builder setter is absent.
Needed for reverse-proxied deployments and for `--server https://host/loom`.

Migrate the hand-rolled `/run` calls in
`integration-test/.../PipelineDistributedExecutionIntegrationTest.java` onto the new client
method.

---

## 4. Phase 2 — Server: real pause/resume (3–4 d)

### 4.1 Status vocabulary

Add `PAUSED` to `PipelineRunStatusResolver`
(`loom/services/rest/.../service/impl/`). **Not** to `isTerminal()` — that is the point.

### 4.2 Flyway

The `status` column is free-text (`V2.29__add_pipeline_run.sql:41` is only a `COMMENT`,
there is no `CHECK` constraint), so the migration is comment-only:

`loom/db/flyway/src/main/resources/db/migration/V2.56__pipeline_run_paused_status.sql`

```sql
COMMENT ON COLUMN pipeline_run.status IS
  'Current status: PENDING, RUNNING, PAUSED, SUCCESS, FAILED, PARTIAL, CANCELLED';
```

(V2.55 is the current head.) **Re-run `./setup-pool.sh` afterwards** or every endpoint test
fails against a stale pooled database.

### 4.3 Engine — `loom/pipeline/.../engine/PipelineRunEngine.java`

Four edits. Note that the existing `resume(boolean)` at line 318 is *recovery* resume —
name the new pair `pause()` / `unpause()` to avoid the clash.

1. New `private boolean paused;` plus `synchronized pause() / unpause() / isPaused()`.
   `unpause()` calls `pumpDeferred()` then `checkComplete()`.
2. `advance(ItemState)` (line 536) — gate on `if (cancelled || paused) return;`.
   This is the single correct choke point: `dispatch(...)` and `dispatchSegment(...)` are
   only reachable through `advance`, so retries (`retryNow` → `advance`) and circuit
   un-parking (`unparkKind` → `pumpDeferred` → `advance`) are covered too. Late in-flight
   results still flow through `record()` and settle their own bookkeeping.
3. `releaseCapacityWaiters()` (line 1227) — `if (paused && !runComplete) return;`
4. `whenCapacityAvailable(Runnable)` (line 1212) — park the waiter while paused:
   `if ((!atCapacity() && !paused) || runComplete) { action.run(); return; }`

**Edits 3 + 4 are what make pause real rather than cosmetic.**
`ProcessorEndpoint.java:387` withholds `SOURCE_ITEMS_ACK` through `whenCapacityAvailable`,
and that ack is what lets the Cortex source send its next batch. Withholding it halts the
**filesystem scan itself**, not just node dispatch.

`checkComplete()` needs no change: while paused, an item with ready-but-undispatched nodes
is not `isComplete(graph.size())`, so the run cannot close out; a paused run whose work *is*
all settled should complete, and does.

### 4.4 Tracker

`PipelineRunTracker` — add `pause(UUID)` / `resume(UUID)` via a private
`transition(runUuid, expectedFrom, to)` that loads the row, rejects terminal states, rejects
a status mismatch, sets only `status`, and updates.

Do **not** route through the existing `apply(...)` — it stamps `finished` and zeroes all
four counters, which is correct for a terminal verdict and destructive for a pause.

### 4.5 Service

`PipelineEndpointService`, beside `cancelRun` (~line 545). Both under the existing
`UPDATE_PIPELINE_RUN` permission — no new `Permission` enum constants needed.

- **`pauseRun`** — 404 unknown / pipeline mismatch; 409 terminal; 409 already `PAUSED`.
  **DB first, then engine** (same rationale as `cancelRun`: a natural completion racing the
  request must not win a half-applied pause).
- **`resumeRun`** — 404; 409 if the status is not `PAUSED`; **409 when
  `pipelineRunRegistry.get(runUuid)` is null** ("run is not live and cannot be resumed") —
  flipping a dead row back to `RUNNING` would create a run that nothing will ever advance.
  **Engine first (`unpause()`), then DB**, so we never advertise `RUNNING` while dispatch is
  still gated.

### 4.6 Recovery

`PipelineRunRecovery.recoverAll()` currently loads only `RUNNING`. Extend it to also load
`PAUSED`, rebuild those engines exactly as before, then immediately call `engine.pause()`.
Without this every paused run is silently orphaned by a Loom restart.

### 4.7 Routes

`PipelineEndpoint` — two `secure(...)` entries and two `addRoute(..., POST, ...)` returning
`GenericMessageResponse` (same shape as cancel). Both plural-compliant per
[../../guidelines/CODING.md](../../guidelines/CODING.md):

```
POST /api/v1/pipelines/:uuid/runs/:runUuid/pause
POST /api/v1/pipelines/:uuid/runs/:runUuid/resume
```

---

## 5. Phase 3 — CLI skeleton (3–4 d)

Register `<module>cli</module>` in the root `pom.xml` **after `loom`** (it needs
`loom-client-rest`) and **before `integration-test`** (which will invoke the CLI in-process).

Coordinates `io.metaloom.cli:metaloom-cli`, Java package root `io.metaloom.cli`, binary name
`metaloom`.

### 5.1 Layout

```
cli/
  pom.xml  README.md  build-native.sh
  src/main/java/io/metaloom/cli/
    MetaLoomCLIMain.java  MetaLoomCLI.java  ExitCode.java  CliContext.java
    cmd/{auth,config,pipeline,run,org,iam,infra}/…
    client/{LoomApi,LoomApiRestImpl,LoomClientFactory,PipelineEventStream,ClientErrors}.java
    config/{CliConfigFile,Profile,CliConfigLoader,CredentialStore}.java
    output/{OutputFormat,OutputRenderer,JsonRenderer,YamlRenderer,TableRenderer,Table,Ansi,Printer}.java
    dagger/{CliComponent,CliModule,ClientModule,DaggerCliFactory,CliExecutionStrategy}.java
  src/main/resources/
    logback.xml
    cli-version.properties            # maven-filtered
    META-INF/native-image/io.metaloom.cli/metaloom-cli/
      native-image.properties  reflect-config.json  resource-config.json
      proxy-config.json  reachability-metadata.json
  src/test/java/io/metaloom/cli/…
```

### 5.2 `cli/pom.xml`

Parent `metaloom-parent`; import `io.metaloom:bom`. Dependencies: `loom-client-rest`,
`picocli`, `dagger`, `jackson-dataformat-yaml`, `logback-classic`. Annotation-processor
paths `picocli-codegen` + `dagger-compiler` (mirroring `cortex/cli/pom.xml`), plus
`-Aproject=${project.groupId}/${project.artifactId}` so picocli's generated native-image
config lands in a predictable directory.

> ⚠️ **Shade must include `ServicesResourceTransformer`** alongside the
> `ManifestResourceTransformer`. Without it the shaded jar loses
> `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` and SLF4J 2.x silently degrades to
> NOP. `loom/containers/server/pom.xml` gets this right; `cortex/cli/pom.xml` does not.
> Shade **in place** (no `shadedArtifactAttached`) so the native profile has one jar to
> point at.

### 5.3 Dagger + picocli — no double parse

`CortexCLIMain` does a throwaway "pass 1" parse only because `CortexOptions` must be
`@BindsInstance`-bound before Dagger constructs anything. The CLI needs none of that:

1. **`CliContext`** — a `@Singleton` mutable holder (server URI, token, profile, output
   format, quiet, colour mode, timeout, verbosity), injected everywhere.
2. **`MetaLoomCLI`** — global `@Option` **setters** with `scope = ScopeType.INHERIT`
   (repo convention, see `CortexCLI`) writing straight into `CliContext`.
3. **`CliExecutionStrategy implements CommandLine.IExecutionStrategy`** — runs after parsing
   and before the leaf command: applies the config precedence chain using
   `parseResult.hasMatchedOption(...)` (which answers "was this flag actually given", so no
   null-vs-default guessing), sets the logback root level from `-v`, then delegates to
   `new CommandLine.RunLast()`.
4. **`DaggerCliFactory implements CommandLine.IFactory`** — backed by a Dagger
   `Map<Class<?>, Provider<Object>>` (`@IntoMap @ClassKey(...)`), falling back to
   `CommandLine.defaultFactory()` for picocli-owned types (`AutoComplete.GenerateCompletion`,
   type converters). This lets the tree be declared with `@Command(subcommands = {...})`
   annotations while every node stays a Dagger-managed singleton — far better than the ~15
   explicit `addSubcommand` calls the cortex `PicoCLIModule` pattern would require here.
5. Commands inject `Provider<LoomApi>` so nothing touches the network before config
   resolution has run.
6. `MetaLoomCLIMain.main` reduces to
   `System.exit(DaggerCliComponent.create().cli().execute(args));`, plus an
   `execute(String...)` overload returning the code (for tests and the integration test).

Also register an `EnvDefaultProvider` (same shape as cortex's), `setExecutionExceptionHandler`
→ `ClientErrors`, and `setCaseInsensitiveEnumValuesAllowed(true)`.

### 5.4 Global options

| Option | Env | Default |
| --- | --- | --- |
| `-s, --server URL` | `METALOOM_SERVER`, else `LOOM_HOST` + `LOOM_PORT` | `http://localhost:6333` |
| `-P, --profile NAME` | `METALOOM_PROFILE` | `default` |
| `--token` / `--token-file` | `METALOOM_TOKEN` | credentials file |
| `-o, --output json\|yaml\|table` | `METALOOM_OUTPUT` | `table` |
| `-q, --quiet` · `-v` (repeatable) | — | — |
| `--color auto\|always\|never` | `NO_COLOR` | `auto` |
| `--timeout DURATION` | `METALOOM_TIMEOUT` | `30s` |
| `-k, --insecure` | — | false |
| `--config FILE` | `METALOOM_CONFIG` | `$XDG_CONFIG_HOME/metaloom/cli.yml` |

`--server` is parsed with `java.net.URI` into scheme/host/port/**pathPrefix** — hence the
builder gap closed in §3.2.

### 5.5 Config and credentials

**Precedence**: flag > env > `~/.config/metaloom/cli.yml` (active profile) > built-in default.

```yaml
# ~/.config/metaloom/cli.yml
currentProfile: default
profiles:
  default:
    server: http://localhost:6333
    output: table
    timeout: 30s
  prod:
    server: https://loom.example.com/loom    # pathPrefix comes from the URL
```

Credentials live in a **separate** `~/.config/metaloom/credentials.yml`, created `0600` via
`PosixFilePermissions`, re-asserted on every write, and **refused on read if
group/other-readable** (the ssh rule). The whole permission block is skipped with a warning
on filesystems without a POSIX view.

### 5.6 Output rendering

- **JSON** — a CLI-local `ObjectMapper` cloned from `LoomJson.mapper` with `INDENT_OUTPUT`.
  **Never call `LoomJson.parse(Buffer, …)` / `encodeToBuffer(…)`** — they route through
  `io.vertx.core.internal.buffer.BufferInternal` and drag Netty into a code path that should
  stay cold under Substrate.
- **YAML** — `new ObjectMapper(new YAMLFactory())` with the same `JavaTimeModule` and the
  `JsonObject`/`Hash` (de)serializers.
- **TABLE** — a small hand-rolled builder with per-command `RowMapper<T>` column definitions.
  picocli's `Help.TextTable` is tuned for usage-help layout, not data.
- **Streams** — machine output → **stdout**; progress, warnings, prompts → **stderr**, so
  `metaloom -o json pipeline list | jq` is always clean.
- **`--quiet`** — TABLE degrades to bare identifiers, one per line, no header
  (`metaloom -q pipeline list | xargs -n1 metaloom pipeline get`); logback drops to `ERROR`.
- **Colour off** when `NO_COLOR` is non-empty, `--color=never`, `TERM=dumb`, or
  `System.console() == null`.

### 5.7 Exit codes

| Code | Meaning | Code | Meaning |
| --- | --- | --- | --- |
| 0 | OK | 8 | Validation failed (400) |
| 2 | Usage / invalid parameter | 10 | Generic error |
| 3 | File error | 15 | Connect error |
| 4 | Not found (404) | 20 | Server failure (5xx, incl. 503 "no processor") |
| 5 | Auth required (401 / WS 4401) | 21 | `--wait` saw a non-SUCCESS terminal status |
| 6 | Forbidden (403) | 124 | Timed out (matches `timeout(1)`) |
| 7 | Conflict (409) | 130 | Interrupted (SIGINT) |

`ClientErrors` maps `LoomHttpClientException` (which carries the code, message and raw error
body) to an exit code plus a one-line stderr message, printing the parsed server body under
`-v`.

Ship in this phase: `login` / `logout` / `whoami`, `config`, `health`, `version`.

---

## 6. Phase 4 — Full command surface (3–4 d)

```
metaloom pipeline list|get|create -f|update -f|delete|export|import|validate|versions|restore
metaloom pipeline run <name|uuid> [--dir PATH] [--glob G]... [--asset UUID]...
                                  [--dry-run] [--follow|-f] [--wait] [--wait-timeout DUR]

metaloom run list [-p <name|uuid>] [--status S] | get <runUuid> | items <runUuid> [--state FAILED]
metaloom run follow <runUuid> | pause <runUuid> | resume <runUuid> (alias start)
                              | cancel <runUuid> (alias stop) | stats [--days N]

metaloom space|library|pool|collection   list|get|create|delete
metaloom user|group                      list|get|create|delete
metaloom role                            list|get|delete
metaloom token                           list|create --name N [--expires D]|delete

metaloom processor list|get <uuid>|restrictions <uuid>
metaloom node descriptors [--kind K] | content-types
metaloom stats runs [--days N] | server
metaloom health | version | completion bash|zsh
```

- `--dir` → the new `PipelineRunRequest.path`; `--glob` → `pathGlobs`; `--asset` → `mediaUuids`.
- "Pause / start / stop" maps to `run pause` / `run resume` (alias `start`) / `run cancel`
  (alias `stop`).
- REST nests runs under a pipeline (`/pipelines/:uuid/runs/:runUuid`) but users hold only a
  run UUID. `-p/--pipeline` accepts a name or a UUID; when omitted a `PipelineResolver` does
  one `listPipelines()` + `listPipelineRuns()` sweep to locate the run. Names resolve to
  UUIDs wherever `<name|uuid>` is accepted.

---

## 7. Phase 5 — `--follow` / event streaming (2 d)

`PipelineEventStream` built on `okhttp3.WebSocket` — already on the classpath via
`loom-client-rest`. Do **not** add `vertx-web-client`.

- URL: `ws(s)://host:port{prefix}/api/v1/pipelines/events/ws?token=<jwt>[&pipeline=<name>][&run=<uuid>]`.
  The token goes in the query string because that is what the server's
  `WebSocketAuthenticator` reads; close code `4401` means a bad token → exit 5.
- Frames deserialize to `PipelineEventMessage`. TABLE renders one colour-coded line per
  event (type badge, node id, media path, counters); JSON renders **NDJSON** (one compact
  object per line, so `| jq` streams); YAML renders `---`-separated documents.
- Terminates on `PIPELINE_COMPLETED` for the followed run, on `--wait-timeout`, or on SIGINT
  (a shutdown hook closes the socket, exit 130). Reconnects with capped exponential backoff
  on abnormal close; never on 4401.

**Race, and how to avoid it.** The WebSocket carries no history, so `pipeline run --follow`
must **connect and wait for the socket to open, then POST `/run`**, buffering events until
the returned `runUuid` is known and replaying them through the filter. Doing it the other
way round loses `PIPELINE_STARTED` and the early node events.

**Optional server tweak (recommended).** `PipelineEventEndpoint` today filters only by
pipeline *name*. Generalise `extractPipelineFilter` into `extractQueryParam(ws, key)`, read
both `pipeline` and `run`, and extend `Subscriber.matches` in `PipelineEventBroadcaster` to
compare `event.getPipelineRunUuid()`. Keep the two-arg overload. The CLI sends `run=` **and**
filters client-side, so `--follow` still works against a server that predates this.

`--wait` without `--follow` polls `loadPipelineRun` at 1 s until terminal; exit 0 on SUCCESS,
21 otherwise.

---

## 8. Phase 6 — GraalVM native image (2–4 d, high variance)

A `native` profile in `cli/pom.xml`, modelled on `loom/containers/server/pom.xml`:
`org.graalvm.buildtools:native-maven-plugin:0.10.6`, goal `compile-no-fork` bound to
`package`, `<imageName>metaloom</imageName>`, built **from the shaded jar** via `<classpath>`.

buildArgs:

```
--no-fallback
-H:+UnlockExperimentalVMOptions
-H:+ReportExceptionStackTraces
--enable-url-protocols=http,https
--initialize-at-build-time=org.slf4j,ch.qos.logback,com.fasterxml.jackson,org.yaml.snakeyaml,picocli
--initialize-at-run-time=io.netty
--install-exit-handlers
-H:ConfigurationFileDirectories=${project.basedir}/src/main/resources/META-INF/native-image/io.metaloom.cli/metaloom-cli
-J-Xmx4g
-Os
```

`org.jooq` is dropped from the build-time list — it is not on the CLI classpath.
`--install-exit-handlers` is **required**: without it Ctrl-C during `--follow` skips
shutdown hooks in a native binary. `-Os` because a CLI wants a small binary and fast startup,
not peak throughput.

### 8.1 Expected pitfalls

1. **Jackson over rest-model.** `LoomJson.mapper` reflects over every `*Response`/`*Record`/
   `*Request`. `reflect-config.json` must cover `io.metaloom.loom.rest.model.**` with all
   declared fields/methods/constructors. Generate it with the tracing agent — hand-listing
   200+ classes is not maintainable.
2. **OkHttp 4.12 is Kotlin.** `kotlin.Metadata` must be reachable, and
   `okhttp3.internal.platform.Platform.findPlatform()` reflectively probes
   Android10/Conscrypt/BouncyCastle/OpenJSSE and must be allowed to fail cleanly. If the
   build reports an `SSLContext` captured in the image heap, add
   `--initialize-at-run-time=okhttp3.internal.platform`.
   **`okhttp3/internal/publicsuffix/publicsuffixes.gz` must be in `resource-config.json`**
   or cookie handling throws at runtime.
3. **Netty via vertx-core.** `rest-model` depends on Vert.x because
   `PipelineModel.getDefinition()` and `PipelineRunRecord.getMeta()` are `JsonObject`, so
   vertx-core + Netty land on the CLI classpath regardless.
   `--initialize-at-run-time=io.netty` handles it; keeping the CLI off `LoomJson`'s `Buffer`
   methods means Netty is never actually reached at runtime.
   *Follow-up worth filing (out of scope here): migrate those two fields to Jackson
   `JsonNode` so `rest-model` stops depending on Vert.x entirely — that would cut roughly
   8 MB of dead weight off every client.*
4. **logback / SLF4J** — `logback.xml` in `resource-config.json`, `ch.qos.logback` at build
   time, plus the shade `ServicesResourceTransformer` from §5.2.
5. **picocli-codegen** emits the command reflection config for free — no hand maintenance
   for the command classes themselves.
6. **`--version` must not read a jar manifest** (there is none). Use the maven-filtered
   `cli-version.properties` with a picocli `versionProvider`, resource registered.
7. **TTY detection** — `System.console()` works in GraalVM 22+ native images, but keep the
   `TERM=dumb` fallback.

### 8.2 Metadata generation

```bash
java -agentlib:native-image-agent=config-merge-dir=cli/src/main/resources/META-INF/native-image/io.metaloom.cli/metaloom-cli \
     -jar cli/target/metaloom-cli.jar <command>
```

Run over a representative sweep: `login`, every `list` in all three output formats,
`pipeline run --follow`, an auth failure, a connection failure, `config set`. Wired as
`./build-native.sh agent`.

### 8.3 `cli/build-native.sh`

Mirrors `loom/containers/build-containers.sh`: `set -euo pipefail`, `GRAALVM_HOME` defaulting
to `/opt/jvm/graalvm-25`, an `ensure_graalvm` guard, then
`JAVA_HOME="$GRAALVM_HOME" mvn -f "$REPO_ROOT/pom.xml" -Pnative -DskipTests -pl cli -am package`.
Subcommands: `build` (default), `agent`, `smoke`, `install` (copy to `~/.local/bin/metaloom`).
The JVM jar keeps working because shade runs in the default profile and `native` only *adds*
the native plugin.

---

## 9. Phase 7 — Docs, demo data, spec (1–2 d)

Per [../../guidelines/CODING.md](../../guidelines/CODING.md):

**Website** (customer-facing; no spec references, no internal coding notes):

- New `website/content/english/docs/loom/cli/index.adoc` — install (jar + native), config
  file and profiles, `login`, every command group with worked examples, output formats,
  exit-code table, shell completion.
- `website/content/english/docs/pipeline/` — pause/resume and the `path` run option.
- `website/content/english/docs/rest/` — the two new routes.
- `website/content/english/docs/getting-started/` — a "drive it from the CLI" section.
- `website/content/english/docs/loom/configuration/index.adoc:192` — fix the stale `LoomCLI`
  claim.

**Internal docs**: `loom/doc/src/main/docs/loom/configuration/index.adoc:85`.

**Demo data** — `loom/core/.../boot/DemoDatabaseInitializer.java` creates three demo
pipelines but **no run history**, so a fresh demo container shows an empty
`metaloom run list` and a flat `metaloom stats runs`. Inject `PipelineRunDao` and seed four
runs across the demo pipelines — one SUCCESS, one PARTIAL, one PAUSED, one RUNNING — with
plausible counters and timestamps spread over the last 14 days (matching the stats
endpoint's default window).

**Spec**:

- This file, plus a new `spec/cli/CLI.md` (command tree, config precedence, exit codes,
  output contract, native build) once the CLI lands.
- `spec/loom/RESTAPI.md` — pause/resume in the endpoint table and the run section; `path` on
  `PipelineRunRequest`; the `PAUSED` status.
- `spec/loom/WEBSOCKET.md` §4.3 — the `?run=<uuid>` filter.
- `spec/loom/PERSISTENCE.md` — `PAUSED` in the `pipeline_run.status` vocabulary.
- The four module-layout references from §2.

---

## 10. Suggested additional CLI features

**Tier 1 — ship in v1** (cheap, and the CLI feels broken without them)

1. `login` / `logout` / `whoami` — without persisted credentials every command needs `--token`.
2. `config` subtree + profiles — the difference between a demo and a tool people use against
   three environments.
3. `health` / `version` — the first thing anyone runs when something is wrong; `version` also
   surfaces the server's DB revision from `GET /api/v1`.
4. Shell completion via `picocli.AutoComplete.GenerateCompletion` — roughly five lines, and
   it transforms the discoverability of a 15-group tree.
5. `--wait` and `-q` id-only output — what makes the CLI scriptable in CI.

**Tier 2 — high value, moderate cost**

6. `token` management — long-lived API keys (`/api/v1/tokens`) are the right credential for
   CI; JWTs expire.
7. `processor list` — "no processor accepts source kind X" is the single most common `/run`
   failure (503), and today there is no way to see the fleet from a terminal.
8. `node descriptors` / `content-types` — answers "what node kinds exist and what options do
   they take" when authoring a definition.
9. `pipeline export` / `import` as YAML — pipeline definitions belong in git; this is the
   only path to that today.
10. Asset search via the existing `GraphQLMethods` — one command, large payoff.

**Tier 3 — later**

11. `metaloom watch <pipeline> --dir` — poll or inotify a folder and trigger runs; a natural
    pairing with the differential index-backed scan.
12. Asset upload / download — needs multipart plumbing the CLI does not otherwise have.
13. `metaloom logs <runUuid> --since` — failed run items with context (partially covered by
    `run items --state FAILED`).
14. `metaloom chat` / MCP access — the agent and MCP modules exist, but it is a large surface
    and a different UX problem.

---

## 11. Verification

```bash
mvn -T 8 test-compile -q -DskipTests                 # fast compile check
./setup-pool.sh                                       # REQUIRED after the V2.56 migration

mvn test -pl loom/pipeline      -Dtest='PipelineRunEngine*Test'
mvn test -pl loom/core          -Dtest='PipelineRun*EndpointTest'
mvn test -pl loom/services/rest -Dtest='*SourceOptions*'
mvn test -pl cli                                      # CLI unit + renderer + config tests
./it.sh                                               # incl. CliPipelineRunIntegrationTest

cli/build-native.sh && cli/build-native.sh smoke
```

### Tests to add

| Where | What |
| --- | --- |
| `loom/pipeline/src/test/.../engine/PipelineRunEnginePauseTest.java` | Reuse `FakeNodeDispatcher` + `linearGraph()` from `PipelineRunEngineCancelTest`. Pause stops new dispatch; a late in-flight result settles its own node but does not unblock downstream; pause neither completes the run nor fires completion listeners; **pause holds capacity waiters even when capacity is free** (the source-ack throttle); unpause dispatches everything that became ready during the pause; pause is idempotent; pause after completion is a no-op; cancel-while-paused still works and releases waiters; a retry timer firing while paused does not dispatch; unpause on an all-settled run completes it |
| `loom/core/src/test/.../endpoint/test/PipelineRunPauseEndpointTest.java` | Copy the shape of `PipelineRunCancelEndpointTest` (same `LoomCoreTestExtension`, `createRunningRun()` DAO fixture, raw-Vert.x `httpSend` helper). 200 RUNNING→PAUSED; 200 PAUSED→RUNNING; 409 pausing an already-PAUSED run; 409 pausing a terminal run; 409 resuming with no live engine; 404 unknown run; 403 as `joedoe` (no `UPDATE_PIPELINE_RUN`); cancel-while-paused → CANCELLED |
| `loom/services/rest/src/test/` | `sourceOptions()` precedence: `pathGlobs` beats `path`, `mediaUuids` wins |
| `cli/src/test/` | Declare a `TestCliComponent` swapping `ClientModule` for a fake — Dagger has no override mechanism; this is the pattern `CortexComponentTest` uses. Assert on `cmd.setOut/setErr` writers plus the exit code. **This is why the narrow `LoomApi` port exists**: faking `LoomHttpClient` means implementing 33 aggregated interfaces, whereas faking `LoomApi` is about 20 lines. Plus renderer golden tests (TABLE column widths, `--quiet`, `NO_COLOR`, stable-key JSON, NDJSON follow mode, YAML round-trip) and the full config/credential precedence matrix including the `0600`-required check |
| `integration-test/.../CliPipelineRunIntegrationTest.java` | On the real-Loom + real-Cortex topology of `PipelineDistributedExecutionIntegrationTest`: a temp folder with two files, then in-process `MetaLoomCLIMain.execute("--server", url, "--token", jwt, "pipeline", "run", name, "--dir", dir, "--wait", "-o", "json")` → exit 0, a SUCCESS run with `mediaCount == 2` on stdout. Requires an `integration-test` → `metaloom-cli` dependency, hence the module ordering in §5 |
| `cli/build-native.sh smoke` | Not JUnit — native builds take minutes. `--version`, `--help`, `pipeline --help`, `-o json config list`, and a deliberate unreachable-server call asserting exit 15 |

---

## 12. Phase ordering and effort

| Phase | Scope | Effort | Depends on |
| --- | --- | --- | --- |
| P0 | Delete `loom/cli`, module removal, six doc fixes, unify picocli version | 0.5 d | — |
| P1 | `path` on the run request; loom-client method gaps; `setPathPrefix`; `InfoMethods` | 2–3 d | P0 |
| P2 | Pause/resume: engine gate, tracker, service, routes, recovery, Flyway, tests | 3–4 d | P0 |
| P3 | CLI skeleton: module, Dagger + `IFactory`, config/credentials, renderers, exit codes, `login`/`config`/`health`/`version` | 3–4 d | P0 |
| P4 | Full command surface + tests | 3–4 d | P1, P3 |
| P5 | `--follow`, the `?run=` filter, `--wait` | 2 d | P4 |
| P6 | Native image, metadata, `build-native.sh`, smoke test | 2–4 d | P3 |
| P7 | Docs, demo run history, spec | 1–2 d | all |
| | **Total** | **≈ 17–24 d** | |

P1, P2 and P3 are mutually independent and can run in parallel.

---

## 13. Risks and open questions

- **Native image + OkHttp/Kotlin is the schedule risk.** Mitigate by landing a fully working,
  fully tested JVM jar first; P6 is separable and can slip without blocking anything else.
- **`rest-model` → vertx-core → Netty** is dead weight on a client classpath and a Substrate
  hazard. Contained by `--initialize-at-run-time=io.netty` and by never touching `LoomJson`'s
  `Buffer` methods; the real fix (`JsonObject` → `JsonNode` in `PipelineModel` /
  `PipelineRunRecord`) is separate work.
- **Pause bites one batch late.** Withholding the source ack halts the scan only after the
  current batch drains. With a large `batchSize`, pause takes a batch to take effect.
  Document it; a `PAUSE_SOURCE` processor message would make it immediate, later.
- **A paused run pins a live engine and a WebSocket-connected worker indefinitely.** Should
  it auto-cancel (or auto-resume) after N hours? Without a reaper an operator can wedge the
  fleet by pausing and walking away. At minimum add a `loom_pipeline_runs_paused` gauge.
- **Resume on a run lost to a restart** — 409 (the recommendation here) or re-enter
  `PipelineRunRecovery`? The latter is more magical and interacts badly with the "source
  enumeration is not recoverable" rule already documented in `PipelineRunRecovery`.
- **`GET /api/v1` URL construction** through `LoomClientRequestImpl` — verify the
  empty-path-segment behaviour before committing to `InfoMethods` (§3.2).
- **The `run` subtree resolves a pipeline UUID from a run UUID** with an extra
  `listPipelines()` round trip. Acceptable; caching it in the profile is probably not worth
  the staleness.

---

## 14. Progress Assessment

- [x] P0 — `loom/cli` removed; six stale doc references fixed; picocli unified at 4.7.7
- [x] P1 — `path` on `PipelineRunRequest` + `SourceOptionsResolver`; client gaps filled
      (`runPipeline`, pause/resume/cancel, versions, `InfoMethods`, `setPathPrefix`)
- [x] P2 — server-side pause/resume: engine gates, tracker `transition`, two REST routes,
      recovery, `V2.56` migration
- [x] P3/P4 — `cli/` module, Dagger `IFactory` (single parse), config + credentials,
      three output formats, exit codes, full command surface
- [x] P5 — `--follow` over OkHttp WebSocket, `?run=` server-side filter, `--wait`
- [x] P6 — native image builds and passes its smoke test (30 MB, ~9 ms start-up)
- [x] P7 — website CLI/pipeline docs, demo run history, spec updates

### Test coverage added

| Suite | Count |
| --- | --- |
| `PipelineRunEnginePauseTest` | 10 |
| `PipelineRunPauseEndpointTest` | 12 |
| `SourceOptionsResolverTest` | 12 |
| CLI unit tests (table, ansi, config, credentials, errors, command tree) | 70 |
| `CliIntegrationTest` (real Loom, real HTTP) | 18 |
| `build-native.sh smoke` (native binary) | 10 |

### Defects found by the work

1. **`sourceOptions` ignored a single-asset selection** when the definition or request also
   carried `pathGlobs`, because the source node prefers globs. Running a pipeline for one
   asset therefore re-scanned the whole library. Fixed in `SourceOptionsResolver`.
2. **`--help` failed on every subcommand** (`Unknown option: '--help'`) — only the root
   declared `mixinStandardHelpOptions`. Now attached programmatically to the whole tree.
3. **An unreachable server exited 20 instead of 15**: the client reports transport failures
   as a synthetic HTTP 500, which `ClientErrors` now distinguishes by the absent body.
4. **A malformed config file escaped as a raw stack trace** with exit 1, because the config is
   read before picocli's exception handler is reachable.
5. **A loosened credentials file could never be repaired** — the read guard blocked the very
   `login` that would rewrite it. Writes are now lenient and tighten the file.
6. **`GET /api/v1` was unreachable from the client**: an empty path produced a trailing slash
   that the router does not match.

### Deferred, with reasons

- **Tier 2/3 commands** (`token`, `processor`, `node descriptors`, `pipeline export/import`,
  asset search, `watch`) are specified in §10 but not implemented. The requested feature set
  is complete without them.
- **Pipeline create/update from a file** — `pipeline get --definition` exports a definition,
  but there is no `create -f`. Needs a decision on the authoring format (raw definition JSON
  versus a wrapper carrying name/priority/enabled).
- **Versioning endpoint tests** — the client methods exist and are wired, but the surface is
  still untested; tracked in [../pipeline/PIPELINE_TASKS.md](../pipeline/PIPELINE_TASKS.md)
  Task 7.
- **A reaper for abandoned paused runs.** A paused run pins an engine and a worker
  indefinitely. Worth a `loom_pipeline_runs_paused` gauge at minimum.
- **`rest-model` → Vert.x → Netty** still lands ~8 MB of unused dependency on every client.
  Contained for the native image, but the real fix is `JsonObject` → Jackson `JsonNode`.

---

_Last updated: 2026-07-26_
