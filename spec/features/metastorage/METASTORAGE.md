# Cortex MetaStorage — Technical Specification

> ## ⚠️ Status: REMOVED (2026-07-24)
>
> **The MetaStorage subsystem described below no longer exists.** It was removed
> in favour of the simpler model recommended in
> [METASTORAGE_FEEDBACK.md](METASTORAGE_FEEDBACK.md) §4:
>
> - **Hash persistence stays in xattr** — SHA-512 is written/read directly by
>   `LoomMediaImpl` via `XAttrUtils` (xattr `loom_sha512`), exactly as before and
>   entirely independent of the deleted subsystem.
> - **`FingerprintNode`** — the only live consumer of MetaStorage — now uses a
>   **lightweight in-memory LRU cache** (`java.util` `LinkedHashMap`, cap 100 000)
>   as its skip cache. Non-durable by design; Loom is checked first.
> - **Everything else deleted:** `MetaStorage`, `AbstractMetaStorage`,
>   `MetaStorageImpl`, `LoomMetaKey(Impl)`, `LoomMetaType`/`LoomMetaCoreType`,
>   `LoomMetaTypeHandler` + all four handlers (XATTR/FS/HEAP/AVRO),
>   `AbstractCachingLoomTypeHandler`, `MetaDataStream(+FSImpl)`,
>   `MetaStorageException`, both `LoomStorageModule`s, the `CortexBindModule`
>   binding, and all eight per-node `*MetaStorage` decorators.
>
> Result delivery to Loom is unchanged (nodes emit via `ctx.output(...)` → bulk
> sync; typed components + `node_result` ledger). Pipeline skip-if-done remains
> the job of `NodeCacheProvider` ([../pipeline/PIPELINE.md](../pipeline/PIPELINE.md)
> §4.10).
>
> **The text below is retained as a historical description of the removed system**
> — useful for understanding older commits and the reasoning in the feedback doc.
> Do not treat it as current; the types it names are gone.

---

> **Audience: AI coding agents and contributors.** This document describes the
> **(now removed)** MetaStorage subsystem in Cortex: what it was, how it was
> wired, and how it related to the two other result-persistence mechanisms that
> existed alongside it (the pipeline `NodeCacheProvider` and the Loom back-end
> sync).
>
> **Source of truth is the code** under `cortex/`. Every claim here was checked
> against the tree at the time of writing; last verified 2026-07-24 (the day the
> subsystem was removed).
>
> Companions:
> [METASTORAGE_FEEDBACK.md](METASTORAGE_FEEDBACK.md) (review + should-it-stay
> analysis, now actioned) · [../pipeline-nodes/NODES.md](../pipeline-nodes/NODES.md) §2 (node
> persistence overview) · [../pipeline/PIPELINE.md](../pipeline/PIPELINE.md) §4.10
> (pipeline caching) · [../../cortex/METALOOM_ARCHITECTURE.md](../../cortex/METALOOM_ARCHITECTURE.md)
> (Loom ↔ Cortex split).

---

## 1. What MetaStorage is, and why it exists

MetaStorage is Cortex's **local, typed, per-media metadata store**. It answers
one question for a node: *"for this media file, is there already a value under
this key, and if so what is it?"* — and lets the node write one back.

It predates the current MetaLoom architecture. When Cortex was a standalone
batch tool with **no Loom back-end**, MetaStorage was the *only* place a node
result could live. It was designed to cache two very different kinds of output
against a media file:

- **Small scalar results** — a hash, a face count, a boolean "is complete" flag.
- **Larger structured results** — face-detection records, transcripts, thumbnail
  image bytes.

That dual requirement is why the abstraction is more elaborate than a plain
`Map<String,String>`: it has to store an `int` in a Linux xattr *and* a list of
Avro records in a binary sidecar file *and* a thumbnail byte stream, all behind
one interface.

Since then the architecture changed (see
[METALOOM_ARCHITECTURE.md](../../cortex/METALOOM_ARCHITECTURE.md)): **Loom is now
the system of record**, Cortex is a stateless worker, and results travel back to
Loom over REST (typed asset components + a `node_result` ledger). Two newer
persistence layers grew up around MetaStorage as a result — see §7. This document
describes MetaStorage as it stands; the question of whether it is still warranted
is [METASTORAGE_FEEDBACK.md](METASTORAGE_FEEDBACK.md).

---

## 2. The core abstractions

Four types make up the public surface. All live in `cortex/api`
(`io.metaloom.cortex.api.media` / `.media.type` / `.meta`).

### 2.1 `MetaStorage` — the front door

`cortex/api/.../meta/MetaStorage.java`

```java
<T> boolean has(LoomMedia media, LoomMetaKey<T> metaKey);
<T> T       get(LoomMedia media, LoomMetaKey<T> metaKey);
<T> List<T> getAll(LoomMedia media, LoomMetaKey<T> metaKey);
<T> void    put(LoomMedia media, LoomMetaKey<T> metaKey, T value);
<T> void    append(LoomMedia media, LoomMetaKey<T> metakey, T value);
void        setSHA512(LoomMedia media, SHA512 hash);   // convenience
SHA512      getSHA512(LoomMedia media);                // convenience
```

Every operation is keyed by the pair **(media, key)**. Values are strongly typed
through the generic `T` carried by the key. There is no `remove`/`delete` and no
listing of keys.

### 2.2 `LoomMetaKey<T>` — a typed, versioned key

`cortex/api/.../media/LoomMetaKey.java` (impl `LoomMetaKeyImpl`)

A key bundles everything the store needs to route and (de)serialise a value:

| Field | Meaning |
|---|---|
| `key()` | short logical name, e.g. `"sha512"`, `"facedetect_result"` |
| `version()` | an `int` schema version for that key |
| `type()` | the `LoomMetaCoreType` — **selects the storage backend** |
| `getValueClazz()` | the runtime `Class<T>` used for (de)serialisation |
| `newValue(ByteBuffer)` | optional factory (`Function<ByteBuffer,T>`) for binary reads |
| `fullKey()` | the physical key actually written to storage |

`fullKey()` derivation (`LoomMetaKeyImpl`):

```java
LoomWorker.PREFIX + "_" + LoomWorker.VERSION + "_" + key() + "_v" + version();
// e.g.  loom_<workerVersion>_sha512_v1
```

So the on-disk/xattr name folds in a global worker prefix/version **and** the
per-key version. Bumping either changes the physical key — old values simply stop
being found (there is no migration; see
[METASTORAGE_FEEDBACK.md](METASTORAGE_FEEDBACK.md) §3.9).

Keys are created via the static factory and, by convention, declared as
`public static final` constants on a per-node storage class (§4):

```java
public static final LoomMetaKey<SHA512> SHA_512_KEY =
    metaKey("sha512", 1, XATTR, SHA512.class, b -> SHA512.fromBuffer(b));
```

### 2.3 `LoomMetaCoreType` — the backend selector

`cortex/api/.../media/type/LoomMetaCoreType.java`

```java
public enum LoomMetaCoreType implements LoomMetaType { XATTR, FS, HEAP, AVRO; }
```

The key's `type()` is the *only* thing that decides which backend handles it.
`LoomMetaType` is an open interface, so in principle custom types could be added,
but only these four core values are ever used.

### 2.4 `LoomMetaTypeHandler` — a backend

`cortex/api/.../media/type/LoomMetaTypeHandler.java`

Each backend implements the same shape as `MetaStorage` minus the SHA-512
convenience methods, plus `LoomMetaType type()` announcing which core type it
serves. There are exactly four implementations (§3), all under
`cortex/common/.../media/type/handler/`.

---

## 3. `MetaStorageImpl` and the four backends

### 3.1 Dispatch

`cortex/common/.../meta/MetaStorageImpl.java` (`@Singleton`)

`MetaStorageImpl` holds `Set<LoomMetaTypeHandler>` (Dagger multibinding) and does
nothing but **route** each call to the handler whose `type()` equals the key's
`type()`:

```java
private LoomMetaTypeHandler getHandler(LoomMetaCoreType type) {
    return handlers.stream().filter(h -> h.type() == type).findFirst()
        .orElseThrow(() -> new MetaStorageException("Failed to locate handler for type " + type + " …"));
}
```

It also defines its own private `SHA_512_KEY = metaKey("sha512sum", 1, XATTR,
SHA512.class)` used by `setSHA512`/`getSHA512`. ⚠️ Note this key is **`sha512sum`**,
distinct from `HashMetaStorage.SHA_512_KEY` (`"sha512"`) — see
[METASTORAGE_FEEDBACK.md](METASTORAGE_FEEDBACK.md) §3.5.

### 3.2 Backend capability matrix

| Backend | Class | Location of data | `put`/`get`/`has` | `append`/`getAll` | Value types accepted |
|---|---|---|---|---|---|
| **XATTR** | `XAttrLoomMetaTypeHandlerImpl` | Linux extended attribute on the **media file itself** (`XAttrUtils`, key = `fullKey()`) | ✅ | ❌ throws `RuntimeException` | `AbstractStringHash` (binary buffer → `newValue`), `JsonObject`, or anything `XAttrUtils` can string-encode. Rejects `MetaDataStream`. |
| **FS** | `FSLoomMetaTypeHandlerImpl` | Sidecar file `metaPath/<key>/<segmented-sha512>/<sha512>.meta` | ✅ | ❌ throws | **`String` only** on write; reads return the file as a `String`, or a `MetaDataStreamFSImpl` when the value class is `MetaDataStream` |
| **AVRO** | `AvroLoomMetaTypeHandlerImpl` | Avro `DataFile` at `metaPath/<key>/<segmented-sha512>/<sha512>.meta` | ✅ | ✅ (the **only** backend that implements them) | Avro `GenericContainer` / `SpecificRecord` only |
| **HEAP** | `HeapLoomMetaTypeHandlerImpl` → `AbstractCachingLoomTypeHandler` | In-process Caffeine cache (max 10 000, expire-after-write **30 days**) | ✅ | ❌ throws | anything except `MetaDataStream` |

Notes that matter:

- **XATTR** is content-addressed by the *file path* (xattrs live on the inode);
  the other three file/heap backends are content-addressed by the media's
  **SHA-512** (`media.getSHA512()`), via `HashUtils.segmentPath`. A node therefore
  needs the hash computed before it can use FS/AVRO storage for that media.
- **HEAP is the odd one out and effectively dead.** No production key uses
  `HEAP` (only `MetaStorageTest` does), and its cache key is `metaKey.fullKey()`
  **without the media** — so all media collide on one entry. See
  [METASTORAGE_FEEDBACK.md](METASTORAGE_FEEDBACK.md) §3.3.
- `append`/`getAll` exist on the interface but only Avro honours them; the other
  three throw at runtime. This is a leaky abstraction — the caller must know the
  backend to know which methods are legal.

### 3.3 `MetaDataStream`

`cortex/api/.../meta/MetaDataStream.java` — a tiny handle exposing
`inputStream()` / `outputStream()`, implemented by `MetaDataStreamFSImpl`
(backed by the sidecar file path). It lets a node stream **large binary blobs**
(the thumbnail contact sheet) into FS storage without materialising them in
memory. XATTR and HEAP explicitly reject it.

---

## 4. Per-node storage decorators

Each domain declares a `*MetaStorage` class that **extends
`AbstractMetaStorage`** — a pass-through decorator wrapping the injected
`MetaStorage` — and adds (a) its typed `LoomMetaKey` constants and (b) typed
getters/setters. They carry no logic beyond key definitions; they exist for type
safety and discoverability.

| Class | Keys → backend | Value type |
|---|---|---|
| `HashMetaStorage` | `sha512`,`sha256`,`md5`,`chunk_hash` → **XATTR** | hash types (binary via `newValue`) |
| `FingerprintMetaStorage` | `fingerprint` → **XATTR** | `String` |
| `FacedetectionMetaStorage` | `facedetect_count`,`facedetect_flag` → **XATTR**; `facedetect_result` → **AVRO** (`append`/`getAll`) | `Integer`,`FaceDetectionFlag`,`Facedetection` |
| `ThumbnailMetaStorage` | `thumbnail_flags` → **XATTR**; `thumbnail_bin` → **FS** (`MetaDataStream`) | `ThumbnailFlag`, stream |
| `ConsistencyMetaStorage` | `zero_chunk_count` → **XATTR** | `Long` |
| `TikaMetaStorage` | `tika_flags` → **XATTR** | `String` |
| `WhisperMetaStorage` | `whisper-result` → **FS** | `String` (JSON of `WhisperResult`) |
| `SceneDetectionMetaStorage` | `scene-detection-result` → **FS** | `String` |

---

## 5. Dependency wiring (Dagger)

Two modules make MetaStorage available to the object graph:

- **`CortexBindModule`** (`cortex/core/.../dagger/`) binds the front door:
  `@Binds abstract MetaStorage bindMetaStorage(MetaStorageImpl)`.
- **`LoomStorageModule`** (`cortex/cli/.../dagger/`, duplicated in
  `examples/cortex-custom/`) contributes the four handlers into the set:

  ```java
  @Binds @IntoSet abstract LoomMetaTypeHandler bindAvroHandler(AvroLoomMetaTypeHandlerImpl e);
  @Binds @IntoSet abstract LoomMetaTypeHandler bindXattrHandler(XAttrLoomMetaTypeHandlerImpl e);
  @Binds @IntoSet abstract LoomMetaTypeHandler bindFSHandler(FSLoomMetaTypeHandlerImpl e);
  @Binds @IntoSet abstract LoomMetaTypeHandler bindHeapHandler(HeapLoomMetaTypeHandlerImpl e);
  ```

`CortexComponent` (cli) pulls in both, so a fully-wired `MetaStorage` (all four
backends) is injectable everywhere a node is constructed. The `*MetaStorage`
decorators are `@Singleton` and take the `MetaStorage` binding via `@Inject`.

The FS/AVRO handlers need `CortexOptions.getMetaPath()` (default
`~/.cache/metaloom/cortex/meta`) — injected at construction; the AVRO handler
`requireNonNull`s it eagerly.

---

## 6. How a node actually uses it

A node that uses MetaStorage injects the domain decorator and calls typed
accessors. The **live** example is `FingerprintNode`:

```java
// isProcessable(): skip if we already have a local answer
if (metaStorage.hasFingerprint(media)) return false;
…
// compute(): remote hit → REMOTE; else compute, persist locally, COMPUTED
metaStorage.setFingerprint(media, value);   // writes the xattr
```

Two things are notable about even this one live caller:

1. It uses MetaStorage as a **skip cache** (`has…` in `isProcessable`), not as
   the delivery path to Loom — the value still goes to Loom via
   `ctx.output(...)` + the pipeline's sync collector.
2. It checks the **Loom asset first** (`asset.getFingerprint()`), and only falls
   back to local compute. Loom is authoritative; MetaStorage is a local
   short-circuit.

---

## 7. Where MetaStorage sits among three overlapping systems

This is the single most important thing to understand, and the reason the
feedback doc exists. **Cortex now has three independent ways to persist a node
result**, built at different times:

| System | Layer | Keyed by | Backends | Purpose today |
|---|---|---|---|---|
| **MetaStorage** (this doc) | node code (Cortex-level) | `(media, LoomMetaKey)` | XATTR / FS / AVRO / HEAP | original local cache; typed domain values |
| **`NodeCacheProvider`** ([PIPELINE.md](../pipeline/PIPELINE.md) §4.10) | pipeline executor | `(nodeId, media)` | NoOp / Heap(Caffeine) / XAttr / Sidecar / Layered | skip-if-already-computed for the DAG; caches whole `NodeResult`s |
| **Loom sync** ([NODES.md](../pipeline-nodes/NODES.md) §2) | REST to Loom | asset UUID | PostgreSQL | system of record; typed components + `node_result` ledger |

The current node base class chose the **second** one, not MetaStorage. Read
`AbstractMediaNode`'s own Javadoc:

> *"The 'already processed?' check is handled by the pipeline's persistent cache
> (XAttr or sidecar file cache backend), not by the node itself."*

Consequently, in the actual pipeline path most nodes deliver results via
`ctx.output(...)` → sync collector → Loom, and rely on `NodeCacheProvider` (not
MetaStorage) for skip-if-done.

---

## 8. Actual usage today (what is live vs. orphaned)

Verified by searching every `*/core/src/main` node source:

- **Live production consumer of MetaStorage: exactly one — `FingerprintNode`**
  (injects `FingerprintMetaStorage`, uses it as a skip cache).
- **Orphaned in production node code:** `HashMetaStorage`, `FacedetectionMetaStorage`,
  `ThumbnailMetaStorage`, `ConsistencyMetaStorage`, `TikaMetaStorage`,
  `WhisperMetaStorage`, `SceneDetectionMetaStorage`. These classes compile and
  are Dagger-injectable, but no current node's `compute()` calls them. For
  example `WhisperNode` now writes straight to Loom
  (`createAssetTranscript` + `createAssetNodeResult`) and never touches
  `WhisperMetaStorage`. Of these, most are still touched by unit tests, but
  **`ThumbnailMetaStorage` and `TikaMetaStorage` have no callers whatsoever** —
  not in production, not in tests — beyond their own definitions. This means the
  only production consumer of the `MetaDataStream` streaming path
  (`thumbnail_bin`, FS) is dead code.
- **`HEAP` backend:** referenced only by `MetaStorageTest`. No production key.
- **`MetaStorage.setSHA512/getSHA512` convenience** (with the `sha512sum` key):
  no production caller found either.

Most references to the `*MetaStorage` classes are now in **test code** (10 test
files under `cortex/nodes/*/core/src/test`), used to assert or seed values, plus
the `*Media` decorator test helpers.

---

## 9. Tests

- `MetaStorageTest` (`cortex/common/.../meta/`) — constructs `MetaStorageImpl`
  with all four handlers and exercises put/has/get for HEAP/XATTR/FS string
  keys, FS binary streams, and asserts that HEAP/XATTR reject `MetaDataStream`.
- `AvroLoomMetaTypeHandlerTest` — Avro round-trip incl. `append`/`getAll`.
- `FaceMetaStorageTest` — face records via the AVRO path.
- Per-node `*MediaTest` / `*NodeTest` helpers seed and read values through the
  decorators.

There is **no test coverage** of `MetaStorageImpl` handler-not-found dispatch,
of the `sha512sum` convenience methods, or of the HEAP media-collision behaviour.

---

## 10. Quick reference — file map

| Need | Path |
|---|---|
| Front-door interface | `cortex/api/.../meta/MetaStorage.java` |
| Decorator base | `cortex/api/.../meta/AbstractMetaStorage.java` |
| Key + impl | `cortex/api/.../media/LoomMetaKey.java`, `.../media/impl/LoomMetaKeyImpl.java` |
| Backend selector enum | `cortex/api/.../media/type/LoomMetaCoreType.java` |
| Backend interface | `cortex/api/.../media/type/LoomMetaTypeHandler.java` |
| Dispatcher | `cortex/common/.../meta/MetaStorageImpl.java` |
| Handlers | `cortex/common/.../media/type/handler/impl/{XAttr,FS,Avro,Heap}LoomMetaTypeHandlerImpl.java` |
| Caching base (HEAP) | `cortex/common/.../media/type/handler/AbstractCachingLoomTypeHandler.java` |
| Stream handle | `cortex/api/.../meta/MetaDataStream.java`, `cortex/common/.../meta/MetaDataStreamFSImpl.java` |
| Handler wiring | `cortex/cli/.../dagger/LoomStorageModule.java`; front door in `cortex/core/.../dagger/CortexBindModule.java` |
| Per-node decorators | `cortex/nodes/<domain>/core/.../<Domain>MetaStorage.java` |

_Last updated: 2026-07-24_
</content>
