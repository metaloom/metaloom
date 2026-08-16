# Static Code Analysis — Prompt for AI-Generated Java

This file is a **runnable prompt**, not background reading. It tells an AI coding agent how to audit
the Java in this repository for the defect classes that machine-written code produces: duplicated
methods, hallucinated APIs, duplicated enum values, and values that contradict themselves.

It is a **read-and-report** task. Do not change production code while running it unless the invoking
user explicitly asks for fixes — the deliverable is the HTML report described in §5.

Related rules: [CODING.md](CODING.md) (definition of done for a change),
[SPEC_RULES.md](SPEC_RULES.md) (definition of done for a spec),
[NEW_NODE.md](NEW_NODE.md) (definition of done for a Cortex node).
Router for everything else: [../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md).

---

## Progress Assessment

- [x] Detector catalogue written (§3, D1–D14)
- [x] Evidence and verification rules written (§4)
- [x] HTML report contract written (§5)
- [ ] First full-repo run recorded under `spec/reports/`
- [ ] Recurring run wired into the build or a scheduled agent (optional; not required today)

---

## 1. The prompt

Copy the block below verbatim when starting a run. Everything after it in this file is the
reference material the block points at.

```
Run a static code analysis pass over the Java sources in this repository, following
spec/guidelines/METALOOM_STATIC_CODE_ANALYSIS.md.

Scope: <all Java modules | loom/** | cortex/** | the diff against master | the listed files>.
Read §2 for scoping rules, §3 for the detector catalogue (D1-D14), §4 for the evidence bar.

You are auditing code that was largely written by AI coding agents. Assume plausible-looking
code that does not do what it claims. Every finding must cite file:line and must be verified
against the actual source before it is reported - see §4. A finding you cannot reproduce by
reading the code is not a finding.

Do not modify production code. Do not run git stash / git reset / git checkout on tracked files.

Write a self-contained HTML report to spec/reports/ exactly as specified in §5, including the
git HEAD revision and the date/time of the run in both the file name and the report header.
Finish by printing the report path and a one-paragraph summary with the finding counts by
severity.
```

---

## 2. Scope and ground rules

* **Language/build.** Java 25 (`<release>25</release>` in the root `pom.xml`), Maven multi-module.
  Sources live under `loom/`, `loom-shared/`, `loom-client/`, `loom-app/`, `cortex/`, `cli/`,
  `clients/`, `integration-test/`, `e2e-test/`.
* **Never audit generated code as if a human wrote it.** Exclude `target/`, `src/jooq/java`
  (jOOQ codegen — see [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md)),
  generated Avro/protobuf sources, and
  `website/dist/`. Duplication and dead accessors there are expected output, not findings.
* **Hand-written jOOQ table classes are an exception**: the registry files under `loom/db/jooq`
  that are maintained by hand *are* in scope for D3 and D4.
* **Diff mode.** When the scope is "the diff", derive it with `git diff --name-only master...HEAD`
  and audit changed files plus their direct callers. Still report a cross-module duplicate (D1)
  when only one side changed — the other half is the evidence.
* **The code is the source of truth.** Where code and a spec disagree, that is a D4 finding against
  the spec, not against the code.
* **Severity.** `CRITICAL` = wrong result, data loss, silently skipped work, or an auth hole.
  `HIGH` = a defect that will surface in production or in a test that does not exist yet.
  `MEDIUM` = maintenance hazard (duplication, contradiction, dead code).
  `LOW` = cosmetic, comment-only, or stylistic.

---

## 3. Detector catalogue

Each detector says what to look for, how to find it, and — where the repository has already produced
one — a real example. Use the examples to calibrate; do not re-report them if they have since been
fixed.

### D1 — Duplicate and near-duplicate methods

AI agents re-implement instead of reusing, because they do not see the whole tree.

* Identical or near-identical method bodies in two classes, especially across module boundaries
  (`loom/common` vs `cortex/common`, `loom-shared/rest-model` vs a node module).
* A private helper that duplicates an existing utility (hash formatting, path normalisation,
  UUID parsing, `Optional`-vs-null wrapping, retry loops).
* The same method declared twice in a type hierarchy where the override adds nothing, or an
  override that silently reverses the parent's contract.
* Two DTO mappers/builders for the same entity (`*ModelBuilder` duplication is a known hazard).
* Copy-pasted test setup that should be a fixture (`loom/fixture`, `PipelineFixtures`).

How: normalise whitespace and identifiers, then hash method bodies over the scope and group
collisions; verify each group by reading both sites. Cheap first pass:
`rg -n --type java '^\s*(public|private|protected).*\(' -o` grouped by signature, then diff bodies.

### D2 — Hallucinations

The signature defect of generated code: confident references to things that do not exist.

* **Non-existent APIs** — a method, field, constant or overload called on a type that does not
  declare it. Compilation catches most; reflection, Dagger `@StringKey` maps, jOOQ field lookups,
  Vert.x `JsonObject` keys, and string-keyed service lookups do not compile-check.
* **Invented configuration** — an env var or option name in code, javadoc, spec or website docs
  that no `*EnvOptions`/`*Options` class reads. Cross-check against
  [../loom/CONFIGURATION.md](../loom/CONFIGURATION.md) and
  [../cortex/CONFIGURATION.md](../cortex/CONFIGURATION.md).
* **Invented REST paths / permissions** — a path in `loom-client`, `clients/python`, `loom-ui` or a
  doc that no endpoint registers; a permission constant that is not in the permission enum.
  See [../loom/RESTAPI.md](../loom/RESTAPI.md), [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md).
* **Invented files and spec sections** — links to spec files or `§`-sections that do not exist,
  `@see`/`{@link}` targets that resolve to nothing, referenced test-data paths that are absent
  (`/opt/metaloom/loom-testdata` is *not* in git — assume nothing about its contents).
* **Comments that describe absent behaviour** — javadoc promising retries, caching, validation or
  thread-safety the body does not implement.
* **Comments that rationalise a bug** — a plausible technical excuse for code that is simply wrong.
  Real example: a `LoomClientMock` comment blaming "Java 25 Mockito restrictions" for a null-client
  override; the restriction was invented and the override silently skipped Loom write-back coverage.
* **Fabricated metrics/log field names** — declared in a catalogue but never recorded (see
  [../features/ops/METRICS.md](../features/ops/METRICS.md) §5 for the pattern).

How: extract every string literal that names a config key, path, permission, port id, content type
or metric, and prove each one has a producer *and* a consumer. Unmatched in either direction is a
finding.

### D3 — Duplicate enum values, constants and map keys

* The same constant name or the same underlying value declared twice in one enum, or two enum
  constants that map to the same string/ordinal used as a key.
* Two enums modelling the same concept in different modules — a merge that was half-done.
  Real example: `NodeResult` / `ResultState` were unified into `cortex/api`; a second copy
  reappearing is a CRITICAL finding.
* Duplicate Dagger `@StringKey` map keys (last binding silently wins), duplicate `@Named`
  qualifiers, duplicate node ids across `cortex/nodes/**`.
* Duplicate keys in JSON/YAML resources — `loom-shared/node-model/src/main/resources/node-descriptors.json`,
  Helm values, demo data.
* Constants with the same value but different names used interchangeably (`"sha256"` vs
  `HASH_SHA256`), and content-type/port-id strings duplicated as literals instead of referenced.
* Duplicate Flyway migration versions under `loom/db/flyway/.../db/migration/` — and remember
  migration versions sort **numerically**, not lexically.

How: parse enum declarations and collect `(name, value)`; report any repeated value. For map keys,
`rg -n '@StringKey\("' --type java` and count duplicates.

### D4 — Values and fields that contradict themselves

The class the user specifically asked for. A single fact stated twice, differently.

* **Default disagrees with its documentation** — the code default, the javadoc, the spec env-var
  table and `website/content/english/docs/` must agree. Any two disagreeing is a finding; name the
  authority (the code) and the stale copies.
* **A field is set but never read**, or read but never set — dead configuration. Real example: a
  `hasEmbedding` filter that could never match and a blur threshold that was unreachable, together
  zeroing the video face path.
* **Mutually exclusive flags** both honoured, or a flag whose two call sites interpret it with
  opposite polarity.
* **Boundary contradiction** — a validator that rejects what a default supplies (`min > default`,
  `default` outside an allowed enum set, a timeout shorter than the retry budget it wraps).
* **Two sources of truth** — the same value derived independently in Loom and Cortex, or in Java and
  in `loom-ui`/`clients/python`, with no shared constant.
* **Contradicting annotations** — `@Nullable` on a field dereferenced unconditionally,
  `@NotNull` on something assigned `null`, `@Deprecated` on the only supported path.
* **Version-line contradictions** — a dependency version pinned in one module and overridden in
  another for the same artifact.

### D5 — Dead, unreachable and no-op code

* Conditions that are always true/false given the surrounding constraints; `catch` blocks for
  exceptions the body cannot throw; branches after an unconditional return.
* Private methods, fields and whole classes with no reader — including "wired but never called"
  Dagger bindings, which look alive to a grep of the module.
* Filters, thresholds and guards that can never trigger because of an earlier check.
* Half-wired write paths: a collector/writer that is bound and flushed but never fed. Real example:
  the `LoomBulkSyncCollector` path, whose only `collect()` call lived in a test.

### D6 — Ignored results from fluent / builder APIs

A generated-code specialty: the fluent call is written, its return value is dropped.

* Real example, still present at 19 call sites: `ctx.failure(...).next()` — the failure message is
  discarded and the node reports `SUCCESS`. Only `.abort()` reads `failureCause`.
* Any `@CheckReturnValue`-shaped API used as a statement: `String.trim()`, `Stream` ops,
  `Optional.map`, immutable-builder `withX(...)`, `Instant.plus(...)`.
* `Future`/`Completable`/`Flowable` created and never subscribed or composed — the work never runs.
* Return values of `add`/`remove`/`compareAndSet` ignored where the outcome matters.

### D7 — Tests that do not test

* Assertions only against mocks (`verify` with no state assertion), `assertTrue(true)`,
  `assertNotNull` on something just constructed.
* An overridden test method with an empty body, or a subclass disabling an inherited case.
* `@Disabled`/`@Ignore` without a linked reason, and `try { ... } catch (Exception e) { }` in tests.
* Tests asserting the buggy behaviour — pinning the defect. Compare the assertion against the spec.
* Endpoint without a `*EndpointTest`, DAO without a delete-cascade test, node without an IT
  (see [CODING.md](CODING.md) — these are *unfinished*, and belong in the report).
* Tests that pass because the fixture is empty: e.g. a transcript assertion over a silent video
  (every video in the test corpus is silent), or an EXIF assertion over images that carry no EXIF.

### D8 — Copy-paste mix-ups

* A block copied and one identifier left unchanged — wrong field assigned, wrong enum branch, wrong
  index, wrong constant, wrong log message naming a different class.
* A label that contradicts the computation (`"sha256"` on an MD5 digest).
* `equals`/`hashCode`/`toString` covering a different field set than the class actually has.
* Loops whose body ignores the loop variable.

### D9 — Resources, lifecycle and concurrency

* `AutoCloseable` not closed on every path — connections, pools, streams, `Session`s, native
  handles. Real example: a leaked c3p0 pool surfaced late in a large test class as
  "too many clients", misdiagnosed as provider capacity.
* Mutable state on a singleton/`@Singleton` Dagger binding, or a shared non-thread-safe collection.
* Blocking calls on a Vert.x event loop.
* Native library loading assumptions (`System.load` registers a SONAME — do not also require
  `LD_LIBRARY_PATH` and claim both).

### D10 — Cross-surface drift

One change landed in Java and nowhere else.

* `loom-client/rest` ↔ `clients/python` ↔ `loom-ui/src/api/` ↔ OpenAPI (regenerate from inside
  `loom/doc`) ↔ `cli/`.
* Node code ↔ `node-descriptors.json` (install the node module *before* regenerating, or the
  harvest reads a stale jar) ↔ website node pages.
* Flyway migration ↔ jOOQ classes ↔ DAO ↔ REST model.
* Spec file that has not been updated with the change (a [CODING.md](CODING.md) violation).

### D11 — Error handling anti-patterns

* `catch (Exception e) { log.warn(...); }` around something whose failure must fail the task.
* Swallowed `InterruptedException` without restoring the interrupt flag.
* Returning `null`/empty on error where the caller cannot distinguish it from a legitimate result —
  the shape that turns a failure into a silent zero-result run.
* Error messages that lose the cause (`throw new X(e.getMessage())`).

### D12 — Comment and documentation hygiene

* Commented-out code blocks; `TODO`/`FIXME`/"for now"/"temporary" with no owner or issue.
* Javadoc parameters that no longer exist; `@return` describing a different type.
* Comments asserting a limitation that is not real (see D2's example) — treat these as HIGH, because
  they cause the next agent to code around a phantom.

### D13 — Speculative generality

* Interfaces with exactly one implementation and no test double, added "for extensibility".
* Configuration flags no operator can reach; strategy/factory layers over a single concrete path.
* Abstract base classes whose only subclass overrides everything.

### D14 — Security and permissions

* Endpoints registered outside the secured router, or missing a permission check that its siblings
  have; permission constants checked with a string literal rather than the enum.
* Lenient-by-default auth switches (a missing token accepted with a warning).
* Hardcoded tokens, passwords or keys in Java, YAML, Helm values or test fixtures that are also used
  at runtime.
* Log statements printing tokens, full JWTs or credentials.

---

## 4. Evidence bar and verification

1. **No finding without `file:line`.** Cite the path relative to the repository root and the line.
2. **Read before reporting.** Open every cited location. A grep hit is a lead, not a finding.
3. **Adversarially verify.** For each candidate, try to refute it: is the "dead" method reached by
   reflection, Dagger, SPI (`META-INF/services`), a JSON descriptor, or a test-only path? Is the
   "duplicate" enum value actually distinguished elsewhere? Drop anything you cannot defend.
4. **Prove the negative for D2/D5.** Search the *whole* repository (including `loom-ui/`,
   `clients/python/`, `helm/`, `website/`, `*.json`, `*.yaml`, `*.sql`) before claiming something has
   no producer or no consumer.
5. **Compile-adjacent claims must be checked.** If a claim implies the code would not build, build
   it: `./mvnw -q -o -pl <module> -am compile`. If it implies a test would fail, say which test —
   and note that running the suite needs `./setup-pool.sh` first.
6. **Deduplicate.** One finding per root cause, listing all affected sites, rather than N copies.
7. **State confidence.** `CONFIRMED` (read and reproduced) or `PLAUSIBLE` (consistent with the code
   but not fully traced). Never present `PLAUSIBLE` as `CONFIRMED`.
8. **No silent caps.** If you sampled, limited to top-N, or skipped a module, say so in the report's
   Coverage section. A truncated sweep presented as complete is itself a defect.

Useful commands:

```bash
git rev-parse --short HEAD                    # report revision
git diff --name-only master...HEAD            # diff-mode scope
rg -n --type java 'ctx\.failure\([^)]*\)\.next\(\)'   # D6 reference pattern
rg -n --type java '@StringKey\("'             # D3 map keys
rg -n --type java 'TODO|FIXME|for now|temporar' # D12
./mvnw -q -o -pl <module> -am compile         # verify a compile-adjacent claim
```

---

## 5. The report — required output

Write **one self-contained HTML file** to `spec/reports/`:

```
spec/reports/static-analysis-<SHORT_HEAD>-<YYYY-MM-DD_HHMM>.html
```

for example `spec/reports/static-analysis-1e12f39e-2026-08-06_1441.html`. Create `spec/reports/`
if it does not exist. Never overwrite an earlier report — the file name carries the revision and the
timestamp precisely so that runs accumulate and can be diffed.

**Header block (mandatory, visible at the top of the rendered page):**

| Field | Value |
|---|---|
| Git HEAD revision | short and full SHA, plus the branch name |
| Working tree | `clean` or the output of `git status --porcelain` summarised |
| Date / time of run | local time **and** UTC, ISO-8601 |
| Scope | what was audited, and what was excluded |
| Analyser | the model/agent that produced the report |
| Totals | findings by severity and by confidence |

**Body:**

* A summary table of all findings — id, severity, confidence, detector (D1–D14), file:line, one-line
  claim — sorted most severe first, with in-page anchors to the detail sections.
* One detail section per finding: what the code does, why it is wrong, the concrete failure scenario
  (inputs/state → wrong output), the evidence (a short quoted snippet with line numbers), and a
  suggested fix. Do **not** paste large file dumps.
* A **Coverage** section: modules and file counts audited, what was skipped and why, which detectors
  were run, and any limits applied.
* A **Clean** section naming detectors that produced nothing — a report that only lists hits reads as
  if the rest was never checked.

**Formatting constraints:**

* Fully self-contained: inline CSS, no CDN links, no external fonts, no remote images.
* Readable in both light and dark (`@media (prefers-color-scheme: dark)`).
* Wide tables and code blocks scroll inside their own `overflow-x: auto` container.
* Severity colour-coded, and also labelled in text — colour alone is not an indicator.

Finally, print to the console: the report path, the totals by severity, and the top three findings.

---

## 6. What not to do

* Do **not** `git stash`, `git reset` or `git checkout` tracked files — a standing rule of this repo.
* Do not fix code as a side effect of the audit; findings first, fixes only on request.
* Do not report style opinions (formatting, naming taste) — this pass is about defects and
  contradictions.
* Do not report a "bug" you have not read the code for, and do not pad the report to look thorough.
  Zero findings in a detector is a legitimate, reportable result.
* Do not regenerate descriptors, OpenAPI or jOOQ classes while auditing; note the drift instead.

---

## 7. Where do I find…?

| Need | Look here |
|---|---|
| Definition of done for a change | [CODING.md](CODING.md) |
| Definition of done for a new node | [NEW_NODE.md](NEW_NODE.md) |
| Spec-file format rules | [../SPEC_RULES.md](SPEC_RULES.md) |
| Env vars / defaults (Loom, Cortex) | [../loom/CONFIGURATION.md](../loom/CONFIGURATION.md), [../cortex/CONFIGURATION.md](../cortex/CONFIGURATION.md) |
| REST routes and DTOs | [../loom/RESTAPI.md](../loom/RESTAPI.md), `loom/services/rest/.../endpoint/impl/`, `loom-shared/rest-model/` |
| Permissions model | [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) |
| Node ports, content types, cardinality | [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) |
| Node descriptors (harvested) | `loom-shared/node-model/src/main/resources/node-descriptors.json` |
| Node result / state enums | `cortex/api/.../node/NodeResult.java`, `.../node/ResultState.java` |
| Metrics catalogue (incl. declared-but-unrecorded) | [../features/ops/METRICS.md](../features/ops/METRICS.md) |
| Migrations and jOOQ codegen | `loom/db/flyway/.../db/migration/`, `loom/db/jooq/generate.sh` |
| Test pool setup (required before any test run) | `./setup-pool.sh` |
| Generated reports | `spec/reports/` |

---
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_