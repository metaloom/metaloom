# Cloud Drive Source Nodes — `gdrive-source` and `onedrive-source`

🟢 **BUILT.** Two Cortex source kinds that ingest media from Google Drive and from OneDrive /
SharePoint, on a shared provider seam that a third cloud can plug into.

This file owns `cortex/cloud-common` the way
[NODE_S3SOURCE_PLAN.md](NODE_S3SOURCE_PLAN.md) owns `cortex/s3-common`. The node system itself is
[NODES.md](NODES.md); the rules for adding a node are
[../../guidelines/NEW_NODE.md](../../guidelines/NEW_NODE.md).

---

## 1. Why

Before this, media had to be on a mount (`filesystem-source`), in a bucket (`s3-source`), or already
an asset (`asset-source`). A large share of real libraries live in Google Drive and OneDrive, and the
only way to reach them was to sync them somewhere else first — which defeats differential scanning
and reintroduces the shared-mount requirement that `s3-source` had just removed.

Cloud drives are in one respect a *better* fit than object storage: they have **stable file ids** and
a **real change feed**, so a re-run is one API call and a rename is genuinely distinguishable from a
delete plus an add.

---

## 2. Shape

| Module | Contains |
|---|---|
| `cortex/cloud-common` | `CloudFileStore` (the provider seam), `GoogleDriveFileStore`, `GraphFileStore`, the four OAuth token sources, `CloudHttp`, `CloudUri`, `CloudFileRef`, `CloudMediaMaterializer`, `CloudLoomMedia`, `CloudMediaReferenceResolver`, `CloudSupport`/`CloudSupportRegistry` |
| `cortex/nodes/cloud-source` (flat) | `CloudSourceNode`, `CloudDifferentialScanner`, `CloudFolderWalker`, `CloudFileIndex(Store)`, `CloudSelection`, the two options classes, `CloudSourceNodeModule` |
| `cortex/core` | `CloudModule` (builds the per-provider support values), `MediaResolverModule` (the composite reference resolver) |

`cloud-common` sits beside `s3-common` rather than inside the node module for the same reason S3's
does: the materializer and resolver must be on the classpath of **every** worker that resolves a
`gdrive://` reference, including workers whose whitelist excludes the source kind.

### 2.1 Two kinds, one implementation

The scanner, index and node class are provider-agnostic; only the bound `CloudFileStore`, the options
`KEY` and the descriptor differ. So there is **one module and one node class**, and **two kinds**.

The kinds are separate because `RegistryNodeRegistrar` advertises a kind only when the worker holds
credentials for it. A single generic `cloud-source` kind could not express "this worker can serve
Google but not Microsoft", and Loom would dispatch a run that dies mid-flight instead of being
rejected up front. `NodeRegistrarTest` pins both directions per provider.

`NodePortConformanceTest`'s `NODE_KINDS` map therefore became `Map<String, List<String>>` — one class,
two kinds. The same change added the two pre-existing source kinds, which had never been listed.

---

## 3. The provider seam

`CloudFileStore` is deliberately **one level deep**: implementations list the direct children of a
folder and never recurse. Recursion lives in `CloudFolderWalker`, which keeps both stores small,
makes `FakeCloudFileStore` trivial, and means depth limits and cycle guards are tested once.

| Concern | Google Drive v3 | Microsoft Graph v1.0 | How the seam models it |
|---|---|---|---|
| Drive selection | absent drive id = My Drive | app-only has no `/me`; a drive id is required | `resolveDriveId(String)`; `CloudUri.MY_DRIVE = "my"` keeps every reference three-segment |
| Listing | `files.list?q='<id>' in parents` + `pageToken` | `/children` + `@odata.nextLink` | opaque `nextPageToken`; Graph stores the whole link in it |
| Delta | `changes.getStartPageToken` + `changes.list` | `/root/delta`, token inside `@odata.deltaLink` | `startDeltaToken` + `delta`; **both are drive-wide** |
| Expired cursor | 404 on the page token | `410 Gone` + `resyncRequired` | `CloudDelta.tokenExpired` — a value, not an exception |
| Change token | `md5Checksum` else `version` | `cTag` else `eTag` | `changeToken()`, prefixed `md5:`/`v:`/`ctag:`/`etag:`. **Opaque; never a hash, never a dedup key** |
| Native documents | Docs/Sheets/Slides have no bytes and no size | every item has bytes | `exportMimeType()`, null on Graph |

Both clients are hand-rolled `java.net.http` against the REST APIs. No SDKs: the surface used is five
endpoints, and `google-api-services-drive` / `microsoft-graph` would add Guava, the Google HTTP stack
and Azure Identity — plus `ServiceLoader` transports — to the shaded cortex jar. A useful side effect
is that the shading hazard recorded in [NODE_S3SOURCE_PLAN.md §7](NODE_S3SOURCE_PLAN.md) does not
apply here at all.

---

## 4. Change detection

Two modes, not S3's three. `startAfter` is **not** ported: it exists only because S3 keys are
lexicographically ordered and append-only, and neither Drive nor Graph has an ordered key space.

| Mode | Mechanism |
|---|---|
| `FULL_WALK` | Walk the subtree, diff against the index. The delta cursor is taken **before** the walk, so a change made during a long walk is caught next run rather than falling between the two |
| `DELTA` | Read the change feed since the stored cursor. One request for a drive of any size |

`chooseMode`: never walked → FULL_WALK; reconcile due → FULL_WALK; `useDelta` off → FULL_WALK; no
stored cursor → FULL_WALK; else DELTA. A `tokenExpired` response re-runs the scan as a full walk,
once.

### 4.1 `MOVED` is real here

`classify`: no index entry → NEW; change token or size differs → MODIFIED (content wins); parent or
name differs → MOVED; else PRESENT. Two selection-scoped refinements that are easy to get wrong:

- a file that **left** the subtree is `DELETED`, not `MOVED` — from the pipeline's point of view it
  is gone;
- a file that **entered** it is `NEW`, even though its id existed elsewhere in the drive all along.

Both kinds therefore advertise the full `[NEW, MODIFIED, MOVED, PRESENT, DELETED]` list and default
to `[NEW, MODIFIED, MOVED]` — matching `filesystem-source`, not `s3-source`.

⚠️ MOVED depends on a **content-stable** change token. Google's `md5Checksum` is one and is present
for every uploaded binary file; the `version` fallback is not — Drive bumps it on every change,
metadata included — so an item with no checksum (native documents, shortcuts) reports a rename as
`MODIFIED`. That errs toward over-reporting, which is the safe direction, and it does not affect
media. Microsoft's `cTag` is content-stable, which is why it is preferred over `eTag`.

### 4.2 The one genuine approximation

Both change feeds are **drive-wide** and a delta entry names only the item's immediate parent.
Deciding whether a changed file lies inside the selected folder means walking up the parent chain,
which `CloudDifferentialScanner` does for at most `PARENT_LOOKUP_DEPTH` (8) hops, answered from the
index where possible and memoised per scan. An unresolvable chain is treated as **outside** — the
conservative direction, which can delay a file but never invent one.

That, and only that, is why `reconcileIntervalMs` survives. It defaults to **24h** rather than S3's
6h, because a delta feed is a provider guarantee about content, unlike a bucket notification that can
simply be lost. Setting it to 0 disables the forced walk entirely.

### 4.3 The index

Avro, like `S3ObjectIndexStore`, keyed by **file id** — which is what makes MOVED possible at all.
Folders are indexed alongside files, because subtree membership is decided by asking whether a parent
is a known folder. Per-file Avro metadata carries `lastFullScanMillis`, `deltaToken` and `accountId`;
a credential change discards the index rather than reporting another account's files as deleted.

Index path: `<indexBaseDir>/sha256(scheme/account/drive/folder/recursive/maxDepth).avro`. The
credential is in the key for the same reason an S3 endpoint is; `recursive`/`maxDepth` are in it
because a shallow index is a strict subset, and reusing it for a deeper selection would report the
whole subtree as `PRESENT` and emit nothing.

---

## 5. Media addressing

References are `<scheme>://<driveId>/<fileId>/<fileName>`.

The file name is in the reference because two consumers need an extension and a cloud file id has
none: cortex detects media type from the path, and Loom's `DaoAssetSink` derives the asset filename
and MIME type from `Paths.get(reference).getFileName()`. The `fileId` segment is what identifies the
file, so the name segment is addressing decoration — it is sanitized (Drive names may contain `/`)
and the true name lives in the index. Percent-encoding was rejected: `%2F` would leak into the asset
filename.

### 5.1 🔴 Two deliberate deviations from `S3LoomMedia`

Both exist because a Google native document is a file with **no size**, a case object storage never
presents:

- **`size()` never materializes.** `S3LoomMedia.size()` falls back to downloading when the size is
  unknown, which is safe there because an S3 listing always reports one. `SourceTaskRunner` asks
  every enumerated item for its size, inside a `catch` that would hide the cost — so that fallback
  here would download every Google Doc during enumeration. `-1` is a legal answer the runner already
  tolerates.
- **`exists()` reads an explicit `present` flag**, not `size >= 0`. `AbstractMediaNode` asks every
  item whether it exists before doing any work, so the question must stay free.

`CloudLoomMediaTest` names both (`testSizeIsMinusOneForANativeDocRatherThanDownloading`,
`testExistsIsTrueForANativeDocWithNoSize`).

### 5.2 The reference resolver became a composite

`MediaReferenceResolver` was provided by a single `if/else` in `S3Module` — honest while `s3://` was
the only remote scheme, untenable with three. It moved to `MediaResolverModule` as a
`SchemeMediaReferenceResolver` holding one branch per active remote, falling back to a local path.

A worker with nothing remote configured still gets **literally** `new MediaReferenceResolver(loader)`,
and `S3MediaReferenceResolver` gained only an `implements` clause and a three-line `handles` —
its seven existing tests pass unedited.

---

## 6. Configuration

Worker-level only; see [../../cortex/CONFIGURATION.md](../../cortex/CONFIGURATION.md) for the full
flag tables (18 `CORTEX_GDRIVE_*`, 15 `CORTEX_ONEDRIVE_*`).

Credentials must never be node options: a pipeline definition is stored in Postgres and rendered
verbatim in the editor, and `ParameterType` has no `SECRET`.

**Auth modes, and they are not equal:**

| Provider | Production | Development only |
|---|---|---|
| Google | service-account key, optionally with domain-wide impersonation | OAuth refresh token — expires after 7 days for an app in "Testing" status |
| Microsoft | app-only client credentials | delegated refresh token — Microsoft rotates it on every use and a stateless worker cannot persist the replacement |

Both refresh-token paths log a WARN on first use saying so. A **half-filled** credential set is a
hard failure at boot naming the missing flag, not a silent `inactive()`: turning a typo into a missing
capability produces the dead-run failure mode `NODE_S3SOURCE_PLAN.md §7` warns about.

Throttling note: Google reports it as **`403` with a `rateLimitExceeded` reason**, not `429`.
`CloudHttp` parses the error body for exactly those two reasons; missing this makes Drive throttling
read as a permission denial and kills the run.

---

## 7. Testing

No credentials and no network anywhere in the suite.

- `FakeCloudFileStore` (cloud-common **test-jar**) is an in-memory store whose change feed is *real*:
  every mutation appends to a monotonic log and `delta()` replays it. A canned list would pass
  whether or not the scanner handled moves and removals correctly.
- `StubHttpServer` (test-jar) is a scriptable `com.sun.net.httpserver` server. Because every token URL
  and API base URL is a configurable option, the OAuth grants and both provider clients are driven
  against it end to end. **Do not hard-code the endpoints in the stores** — that is what makes this
  possible.

| Suite | Count | Covers |
|---|---|---|
| `cortex/cloud-common` | 129 | URI, ref, materializer, lazy media (incl. the two §5.1 guards), resolver, the four grants, token caching under 32 concurrent callers, retry/backoff incl. Google's 403, both provider clients |
| `cortex/nodes/cloud-source` | 89 | node, scanner (both modes, cursor expiry, subtree filtering, depth), index store, walker, selection, options |
| `cortex/core` | 17 | `CloudModule` activation and paths, the composite resolver in all four configurations |
| `cortex/cli` | +7 | the capability gate, both directions, per provider |
| `integration-test` | 10 | the **real** Drive client against `StubDriveApiServer` — real URLs, real JSON mapping, real signed assertion, real materializer — ending in a SHA-512 that reaches an `asset` row over REST |

`StubDriveApiServer` models Drive faithfully enough to matter: it returns `md5Checksum`, and the
first version of it did not — which made a rename read as `MODIFIED` and caught the token nuance
above.

---

## 8. Deliberately not done

- **No demo pipeline.** `DemoDatabaseInitializer` is not touched. A Google or Microsoft tenant
  credential cannot exist in the demo image, so a seeded `gdrive-source` pipeline could only fail —
  the same precedent `NEW_NODE.md §4` records for the GPU sidecar nodes. Note also that no asset
  appears in Loom unless the pipeline contains a hash node (`DaoAssetSink` keys on `HASH_SHA512`), so
  any example must read `gdrive-source → sha512 → …`.
- **No resumable download.** An interrupted large transfer restarts. Same property as the S3
  materializer; both providers support `Range`, so this is a real follow-up for large-video archives.
- **Google Docs export is off by default.** It is lossy and capped at 10 MB of output. The seam
  (`exportMimeType`) exists so the decision is reversible without an index migration.
- **`lastStates` is still per-JVM**, inherited from both other sources: when the source's own
  NODE_TASK lands on another worker the diff state reads `UNKNOWN`. It matters slightly more here
  because `MOVED` is a state this source can actually produce.

---

## 9. Key Classes

| Class | Module | Purpose |
|---|---|---|
| `CloudFileStore` | `cortex/cloud-common` | The provider seam; one level deep by design |
| `GoogleDriveFileStore` / `GraphFileStore` | same | Drive v3 / Graph v1.0 over `java.net.http` |
| `CloudHttp` | same | Bearer injection, retry incl. Google's 403 throttling, one-shot 401 refresh, streaming download |
| `AbstractCachingTokenSource` | same | `volatile` hot path + locked double-check, so N concurrent tasks make one token request |
| `CloudMediaMaterializer` / `CloudLoomMedia` | same | Lazy download, cache keyed on the change token; `size()`/`exists()` never fetch |
| `CloudSupport` / `CloudSupportRegistry` | same | "Is this cloud configured" as a value, never a nullable binding |
| `CloudSourceNode` | `cortex/nodes/cloud-source` | One class, both kinds; cold `stream()`, emits `reference()` |
| `CloudDifferentialScanner` | same | Full walk vs delta, MOVED classification, subtree filtering |
| `CloudFolderWalker` | same | The only recursion in the design |
| `CloudModule` / `MediaResolverModule` | `cortex/core` | Per-provider support values; the composite resolver |

---

_Git HEAD revision: `aab85cb3`_
_Last updated: 2026-08-02 (initial version — both kinds built end to end)_
