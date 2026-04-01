# Media Processing Architecture — Design Feedback

**Date:** March 2026  
**Scope:** MetaLoom Loom + Cortex DAM System

---

## 1. Core Problem Statement

The central design tension in the current system is:

> **How does Cortex process media reliably in offline mode, track what has been computed, and synchronously or asynchronously deliver results to Loom — without losing data, duplicating work, or requiring a permanently live connection?**

Currently, `LoomAction` (the sync step) simply bails out when in offline mode:

```java
if (isOfflineMode()) {
    log.info("Running in offline mode. Skipping action");
    return ctx.next();
}
```

This means all xattr-computed results are never sent to Loom unless Cortex is simultaneously online during the processing run. There is no deferred sync, no pending queue, and no way to retroactively sync results from a previous offline run. The `LinuxFilesystemScanner` tracks `FileState.NEW` to avoid reprocessing, but "has been processed" and "has been synced to Loom" are currently the same pass — they need to be decoupled.

---

## 2. Current Design Analysis

### Strengths
- **Idempotent actions:** Every action independently checks `isProcessed(ctx)` before computing. Re-scanning the same file is safe.
- **xattr as local truth:** Results survive restarts and are stored atomically alongside files. A file either has a result or it doesn't. No orphaned state.
- **Modular pipeline:** Each `CortexAction` is independently enabled/disabled. Easy to add new processing steps.
- **Content-addressed assets:** SHA-512 as the primary key in Loom means duplicate uploads are naturally deduped.

### Weaknesses
- **No pending-sync tracking:** There is no record of which files have been processed locally but not yet pushed to Loom. Once offline processing is done, that knowledge is lost.
- **No retry for failed pushes:** If `LoomAction` fails mid-run (network error), there is no mechanism to retry just the Loom sync step.
- **Scan scope is fixed:** The `FilesystemProcessorImpl` only queues `FileState.NEW` video files. There is no "processed but unsynced" state.
- **Linear scanner, no parallelism:** Actions run sequentially per file, files processed sequentially. Heavy actions (Whisper, face detection, fingerprint) block the pipeline.
- **Online mode fetches before processing:** Calling Loom before every action to check if the result is already there introduces unnecessary latency and couples processing to server availability.

---

## 3. Design Options

The following designs are presented from simplest to most sophisticated. Each is evaluated on: reliability, complexity, scalability, offline support, and resource isolation.

---

### Option A — Client Push (Current Architecture)

**Description:**  
Cortex runs on the media host, scans files, processes them through the action pipeline locally (GPU/CPU), and pushes results directly to Loom via REST in the same processing run.

```
┌────────────────────────────────┐
│        Cortex Node             │
│                                │
│  Scanner → Actions → Results   │
│                      │         │
│                      ▼         │
│               xattr (local)    │
│                      │ (online)│
│                      ▼         │
│              LoomAction (REST) │
└────────────────────────────────┘
                       │
                       ▼
               ┌──────────────┐
               │  Loom Server │
               └──────────────┘
```

**Improvements needed for this model to work well:**

The model is fundamentally sound but needs a **two-phase commit** concept:

1. **Phase 1 (local):** All actions run and write to xattr. A special xattr key `synced_to_loom_v1 = false` is written after processing completes.
2. **Phase 2 (sync):** A separate sync pass reads all xattr entries where `synced_to_loom_v1 = false`, pushes them to Loom, and sets the key to `true` on success.

This decouples processing from syncing and enables deferred delivery.

```
                     xattr state per file
┌─────────────────────────────────────────────────────────┐
│  sha512_v1      = "abc123..."   (COMPUTED)              │
│  fingerprint_v1 = "..."         (COMPUTED)              │
│  whisper_v1     = <avro binary> (COMPUTED)              │
│  loom_sync_v1   = false         ← sync gate            │
└─────────────────────────────────────────────────────────┘
```

**Pros:** Simple. No infrastructure. Works on a single machine. Easy to reason about.  
**Cons:** xattr is a weak queue — you can't query "all files not yet synced" across a directory tree without re-scanning. Sync state lives on the filesystem, not in a queryable structure.

| Criterion         | Rating |
|-------------------|--------|
| Reliability       | ⚠ Medium (no retry, no queue) |
| Complexity        | ✅ Low  |
| Scalability       | ⚠ Low (single node) |
| Offline support   | ✅ Full processing, ⚠ deferred sync |
| Resource isolation| ✅ All on client |

---

### Option B — Server-Only Processing

**Description:**  
Loom itself ingests binary files (via upload endpoint) and runs the full processing pipeline internally. Cortex is eliminated or used only as a file watcher that uploads raw bytes.

```
┌──────────────┐                 ┌─────────────────────────────────────┐
│  File Watcher│──── upload ────►│           Loom Server               │
│  (minimal    │                 │                                      │
│   agent)     │                 │  Ingest → Action Pipeline (internal) │
└──────────────┘                 │  Tika / Fingerprint / Whisper / Face │
                                 │            │                         │
                                 │            ▼                         │
                                 │       PostgreSQL / Qdrant            │
                                 └─────────────────────────────────────┘
```

**Analysis:**

This model collapses Cortex into Loom. It is the **simplest operational model** — one server, one database, one thing to maintain — but it has severe practical problems:

- **Resource conflict:** GPU-heavy operations (face detection, Whisper transcription, video fingerprinting) compete with API serving on the same machine. A stuck Whisper job can starve API requests.
- **Error propagation:** A crash in a processing action can bring down the entire Loom server.
- **No horizontal scale:** Cannot distribute processing across multiple machines. One bottleneck server.
- **Requires file access:** The server needs access to the actual media binary, which may be huge (4K video, raw audio). Uploading is impractical at scale.
- **Hardware lock-in:** If face detection requires a GPU, the Loom server must have one.

**When it makes sense:** Small deployments (hobby/personal), very low media volume, or when all media already lives on the Loom server's filesystem.

| Criterion         | Rating |
|-------------------|--------|
| Reliability       | ❌ Low (processing failures affect server) |
| Complexity        | ✅ Low (single system) |
| Scalability       | ❌ None |
| Offline support   | ❌ None (server must be online) |
| Resource isolation| ❌ None |

---

### Option C — Client Scans, Server Processes (Hybrid Upload)

**Description:**  
Cortex only computes cheap operations locally (hashing, consistency, Tika). The binary is uploaded to Loom or S3. Loom then schedules heavy processing (fingerprint, face, Whisper) via an internal task queue with worker threads or co-located processing services.

```
┌──────────────────────┐          ┌─────────────────────────────────────┐
│   Cortex (light)     │          │           Loom Server               │
│                      │          │                                      │
│  hash → tika         │──REST───►│  store binary → enqueue tasks       │
│                      │          │         │                            │
│  (no GPU required)   │          │         ▼                            │
└──────────────────────┘          │  Internal Worker Pool                │
                                  │  ┌──────────────────────────────┐   │
                                  │  │ face / fingerprint / whisper │   │
                                  │  └──────────────────────────────┘   │
                                  │         │                            │
                                  │         ▼                            │
                                  │     PostgreSQL / Qdrant             │
                                  └─────────────────────────────────────┘
```

**Analysis:**

This is the architecture used by systems like Pixelfed, Mastodon's media pipeline, or Plex media scanning. It provides a reasonable middle ground.

- **Pros:** Cortex stays lightweight. Heavy jobs don't block the client. The server can batch and prioritize processing.
- **Cons:** Server must still handle heavy GPU workloads. Better than Option B but still requires powerful server infrastructure. Binary upload may be impractical for large libraries (many TB of video).

| Criterion         | Rating |
|-------------------|--------|
| Reliability       | ⚠ Medium (server queue, but server still processes) |
| Complexity        | ⚠ Medium |
| Scalability       | ⚠ Medium (vertical on server) |
| Offline support   | ❌ Upload requires connectivity |
| Resource isolation| ⚠ Partial |

---

### Option D — Pull-based Managed Worker Nodes

**Description:**  
Loom acts as a **work coordinator**. It maintains a job queue of assets pending processing. Cortex nodes register with Loom, pull job assignments, process them, and push results back. This is the Celery/Sidekiq/Temporal worker pattern.

```
              ┌─────────────────────────────────┐
              │         Loom Server             │
              │                                 │
              │  Asset DB ←── Results           │
              │  Job Queue ──► assignments      │
              └──────────────┬──────────────────┘
                             │  (poll / websocket)
              ┌──────────────┼──────────────────┐
              ▼              ▼                  ▼
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │  Cortex #1   │ │  Cortex #2   │ │  Cortex #3   │
     │  (GPU node)  │ │  (CPU node)  │ │  (GPU node)  │
     │  face/embed  │ │  hash/tika   │ │  whisper     │
     └──────────────┘ └──────────────┘ └──────────────┘
```

**Loom job queue additions needed:**

```sql
CREATE TABLE "processing_job" (
  "uuid"         uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  "asset_uuid"   uuid REFERENCES asset(uuid),
  "action"       varchar NOT NULL,      -- e.g. "fingerprint", "facedetect"
  "status"       varchar NOT NULL,      -- PENDING, RUNNING, DONE, FAILED
  "worker_id"    varchar,              -- which Cortex node claimed it
  "claimed_at"   timestamp,
  "completed_at" timestamp,
  "retry_count"  int DEFAULT 0,
  "error"        text
);
```

**Flow:**
1. File arrives → Loom creates `PENDING` jobs for required actions
2. Cortex nodes poll `GET /api/v1/jobs?action=fingerprint&claim=true`
3. Cortex claims job, processes media (accessing via shared NFS or S3 presigned URL)
4. Cortex posts results back: `POST /api/v1/jobs/:uuid/results`
5. Loom marks job `DONE`, stores results in DB

**Pros:** 
- True horizontal scale — add more Cortex nodes for more throughput
- Specialized nodes: GPU nodes handle face/whisper, CPU nodes handle hash/tika
- Built-in retry: job stays `PENDING` if a worker crashes
- Loom has full visibility into processing state
- Offline processing: Cortex can process locally first (xattr), then sync results by claiming "already computed" jobs

**Cons:** 
- Requires shared storage (NFS mount or S3) so Cortex can access media bytes for claimed jobs
- More complex Loom API (job endpoints)
- Cortex nodes need to be network-reachable or able to reach Loom

| Criterion         | Rating |
|-------------------|--------|
| Reliability       | ✅ High (retry, heartbeat, claim timeout) |
| Complexity        | ❌ High |
| Scalability       | ✅ High (horizontal) |
| Offline support   | ⚠ Partial (local xattr, deferred claim) |
| Resource isolation| ✅ Full (dedicated nodes) |

---

### Option E — Event/Message-driven (Pub/Sub)

**Description:**  
Loom publishes asset events to a message broker (Kafka, NATS, RabbitMQ). Cortex nodes subscribe to relevant action topics, process, and publish results back. Loom consumes result events and persists them.

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Message Broker (NATS / Kafka)                 │
│                                                                     │
│  topic: asset.created  ──────────────────────────────────────────►  │
│  topic: job.fingerprint ──────────────────────────────────────────► │
│  topic: result.fingerprint ◄────────────────────────────────────── │
└──────────────┬──────────────────────────────────┬───────────────────┘
               │                                  │
               ▼                                  ▼
      ┌─────────────────┐                ┌──────────────────┐
      │   Loom Server   │                │   Cortex Nodes   │
      │                 │                │                  │
      │  publishes jobs │                │  consume jobs    │
      │  consumes results│               │  publish results │
      └─────────────────┘                └──────────────────┘
```

**Pros:**
- Fully decoupled: Loom and Cortex communicate only via messages — no direct HTTP calls during processing
- Outstanding resilience: messages are durable; Cortex can be offline for days and catch up
- Natural fan-out: multiple Cortex nodes consume from the same topic, load-balanced by the broker

**Cons:**
- Requires a message broker: new infrastructure dependency (Kafka is heavyweight; NATS is lighter)
- Ordering guarantees are harder (but generally not needed for independent actions)
- Debugging is more complex (distributed tracing needed)
- Overkill for small deployments

| Criterion         | Rating |
|-------------------|--------|
| Reliability       | ✅ Very High |
| Complexity        | ❌ Very High |
| Scalability       | ✅ Very High |
| Offline support   | ✅ Full (message durability) |
| Resource isolation| ✅ Full |

---

### Option F — Local Cache DB + Background Sync Agent (Recommended Near-term)

**Description:**  
This is an evolution of Option A that directly solves the offline caching problem without adding infrastructure. Cortex maintains a **local SQLite database** as a pending-sync queue. All action results are written to both xattr (idempotency) and the local DB (sync tracking). A background `SyncAgent` thread drains the DB queue to Loom whenever a connection is available.

```
┌─────────────────────────────────────────────────────────────┐
│                     Cortex Node                             │
│                                                             │
│  Scanner ──► Action Pipeline                                │
│                    │                                        │
│                    ├──► xattr (per-file idempotency)        │
│                    │                                        │
│                    └──► Local SQLite DB (sync queue)        │
│                              │                              │
│                    ┌─────────▼──────────┐                   │
│                    │   SyncAgent        │                   │
│                    │  (background thread│                   │
│                    │   with retry/backoff)                  │
│                    └─────────┬──────────┘                   │
└──────────────────────────────┼─────────────────────────────┘
                               │ REST (when available)
                               ▼
                      ┌─────────────────┐
                      │   Loom Server   │
                      └─────────────────┘
```

**Local DB schema (SQLite):**

```sql
CREATE TABLE pending_sync (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  file_path    TEXT NOT NULL,
  sha512sum    TEXT NOT NULL,
  action       TEXT NOT NULL,       -- "hash", "fingerprint", "whisper", ...
  payload      BLOB NOT NULL,       -- serialized result (Avro or JSON)
  status       TEXT DEFAULT 'PENDING', -- PENDING, SYNCING, DONE, FAILED
  attempts     INTEGER DEFAULT 0,
  last_attempt TIMESTAMP,
  error        TEXT,
  created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pending ON pending_sync(status, action);
```

**SyncAgent behavior:**
1. Polls `pending_sync WHERE status = 'PENDING' ORDER BY created_at LIMIT 100`
2. Marks records as `SYNCING`
3. Sends batch to Loom via REST
4. On success: marks `DONE`, optionally deletes row
5. On failure: marks `FAILED`, increments `attempts`, backs off exponentially
6. After N failures: leaves for manual inspection or purges if too old

**SyncAgent activation conditions:**
- Continuous background thread (if Cortex runs as daemon)
- Triggered after each processing run (if Cortex runs as CLI/job)
- Can also be invoked as a standalone command: `cortex sync --loom-url http://...`

**Pros:**
- Solves the offline problem completely: process now, sync later
- Queryable queue: `SELECT COUNT(*) FROM pending_sync WHERE status = 'PENDING'` gives pending work count
- Built-in retry with exponential backoff
- Minimal infrastructure: SQLite is a file, no extra services
- Works as CLI job or daemon

**Cons:**
- Local DB must be on a persistent volume (not lost between container restarts)
- If the same Cortex runs on a different machine next time, the DB doesn't follow
- Two stores to keep consistent (xattr + SQLite), though xattr remains authoritative

| Criterion         | Rating |
|-------------------|--------|
| Reliability       | ✅ High |
| Complexity        | ✅ Low–Medium |
| Scalability       | ⚠ Single node |
| Offline support   | ✅ Full |
| Resource isolation| ✅ All on client |

---

## 4. Comparison Matrix

| Option | Description               | Offline | Scale | Complexity | Reliability | GPU Isolation |
|--------|---------------------------|:-------:|:-----:|:----------:|:-----------:|:-------------:|
| A      | Client push (current)     | ⚠       | ⚠     | ✅          | ⚠           | ✅             |
| B      | Server-only               | ❌       | ❌     | ✅          | ❌           | ❌             |
| C      | Upload + server pipeline  | ❌       | ⚠     | ⚠           | ⚠           | ❌             |
| D      | Pull-based worker nodes   | ⚠       | ✅     | ❌          | ✅           | ✅             |
| E      | Message-driven pub/sub    | ✅       | ✅     | ❌          | ✅           | ✅             |
| **F**  | **Local DB + sync agent** | **✅**  | **⚠** | **✅**      | **✅**       | **✅**         |

---

## 5. Recommended Architecture Path

### Phase 1 (Near-term): Improve Option A with Deferred Sync (Option F)

The current architecture is conceptually correct. The core fix is decoupling the processing pass from the sync pass:

1. **Add a `loom_synced` xattr flag** per action: `user.hash.loom_synced_v1 = false`
2. **Add a local SQLite sync queue** populated after each action computes
3. **Implement a `SyncAgent`** that runs after scans and retries until all rows are `DONE`
4. **Separate the `sync` command** in the CLI: `cortex sync --path /media --loom-url http://...`
5. **The LoomAction** becomes a drain trigger rather than an inline push: it no longer calls Loom directly — it marks the file as "ready to sync" in the DB

### Phase 2 (Medium-term): Add Controlled Worker Mode (Option D, simplified)

Once the system needs to scale beyond one Cortex node:

1. Add a `processing_job` table to Loom
2. Expose a `/api/v1/jobs` endpoint for claim/complete
3. Cortex polls for jobs and processes claimed jobs
4. Keeps xattr + local DB for resilience (same as Phase 1)
5. No message broker required yet

### Phase 3 (Long-term): Event-driven with NATS (Option E)

If processing volume grows to millions of assets with multiple specialized nodes:

1. Switch job dispatch to NATS JetStream (lightweight, persistent, no Kafka overhead)
2. Loom publishes `asset.created` → Cortex subscribes, processes, publishes `result.*`
3. Loom consumes results and stores to DB
4. Cortex retains local xattr cache for idempotency even with durable messaging

---

## 6. Specific Design Issues in Current Spec

### 6.1 xattr as Sole Sync Tracking Mechanism

**Problem:** xattr cannot be queried across a directory tree efficiently. To find all "processed but unsynced" files, Cortex must re-walk the entire tree.

**Recommendation:** The local SQLite DB (Option F) resolves this.

### 6.2 `FileState.NEW` Assumption in Scanner

**Problem:** `FilesystemProcessorImpl` currently only queues files with `FileState.NEW`. After a file is processed, it's no longer `NEW`. If the Loom sync failed, the file will never be re-queued for syncing.

**Recommendation:** Add a separate `FileState.UNSYNCED` or use the local DB to track sync state independently of processing state.

### 6.3 LoomAction is Too Tightly Coupled

**Problem:** `LoomAction` runs inline within the processing pipeline. A network error mid-pipeline fails the sync for that file, but prior actions already wrote to xattr — the file won't be reprocessed, but the Loom record is incomplete.

**Recommendation:** `LoomAction` should only enqueue to the local sync DB. A separate `SyncAgent` thread handles the actual HTTP push with retry. This eliminates the tight coupling between media processing and network availability.

### 6.4 No Batch/Bulk API on Loom Side

**Problem:** Syncing 100,000 files one REST call at a time is slow and creates high Loom load.

**Recommendation:** Add a bulk ingest endpoint: `POST /api/v1/assets/bulk` that accepts an array of asset create/update requests and processes them in a single transaction.

**Status: IMPLEMENTED** ✅

The following bulk API has been added:

#### Bulk Create — `POST /api/v1/assets/bulk/create`

- **Request body:** `AssetBulkCreateRequest` containing a `List<AssetCreateRequest>` in the `assets` field.
- **Response body:** `AssetBulkResponse` with per-item results (`AssetBulkItemResponse`), each reporting `index`, `uuid`, `sha512`, `status` (CREATED/FAILED), and optional `error` message. Top-level `total`, `created`, `failed` counters.
- **Backpressure:** The server processes items in batches of 50 (configurable `BULK_BATCH_SIZE`). Each batch is prepared, stored via `storeBatch()` (jOOQ `batchInsert`), and then component records are created. This bounds peak memory usage and reduces DB round-trips while allowing GC to clean up between batches.
- **Error handling:** Validation failures are reported per-item without aborting the batch. If `storeBatch()` fails, the server falls back to individual `store()` calls.

#### Bulk Update — `POST /api/v1/assets/bulk/update`

- **Request body:** `AssetBulkUpdateRequest` containing a `List<AssetBulkUpdateEntry>`, each with `hashes` (SHA-512 required as lookup key) and an `update` payload (`AssetUpdateRequest`).
- **Response body:** Same `AssetBulkResponse` format. Status is `UPDATED` or `FAILED` per item.
- **Backpressure:** Same batched processing at batch size 50.

#### DAO Layer

- `CRUDDao.storeBatch(List<T>)` — default implementation iterates `store()`.
- `AbstractJooqDao.storeBatch(List<T>)` — overrides with jOOQ `batchInsert()` for efficient multi-row insert.

#### Client Layer

- `AssetMethods.bulkCreateAssets(AssetBulkCreateRequest)` → `LoomClientRequest<AssetBulkResponse>`
- `AssetMethods.bulkUpdateAssets(AssetBulkUpdateRequest)` → `LoomClientRequest<AssetBulkResponse>`
- `LoomHttpClientImpl` routes these to `POST assets/bulk/create` and `POST assets/bulk/update`.

#### Cortex Integration

- `LoomAction` now accumulates `AssetBulkUpdateEntry` items in a list. When the list reaches 50 entries (or `flush()` is called by the processor every 100 files), a single `bulkUpdateAssets()` call is made.
- If the bulk call fails, it falls back to individual `updateAsset()` calls per entry.
- Offline mode still skips the action entirely.

### 6.5 Action Result Visibility

**Problem:** When a Cortex run finishes, there is no summary of how many files were processed, how many actions succeeded/failed, or which files have pending sync.

**Recommendation:** Emit a processing report at the end of each run:
```
Processed:  12,482 files
  hash:     12,482 ✓
  tika:     11,930 ✓  | 552 skipped
  whisper:   4,201 ✓  | 8,281 skipped
  face:      6,332 ✓  | 6,150 skipped | 12 failed
Pending sync: 12,482 (will sync on next online run)
```

### 6.6 No Prioritization in Pipeline

**Problem:** All actions have equal priority. A 4-hour Whisper transcription blocks the fingerprinting of 1,000 other videos.

**Recommendation:** Run fast actions (hash, tika, consistency) in a first pass for all files, then run slow GPU actions (face, whisper, fingerprint) in a second pass. This ensures Loom gets basic metadata quickly even for large libraries.

```
Pass 1 (fast, all files):   hash → consistency → tika → loom-sync
Pass 2 (slow, per-type):    fingerprint → thumbnail → facedetect → whisper → loom-sync
```

### 6.7 Cortex Needs a Daemon Mode

**Problem:** Currently Cortex is a one-shot CLI tool. It scans, processes, and exits. New files added to the library after the scan are not processed until the next manual invocation.

**Options:**
- **Polling daemon:** Re-scan every N minutes/hours (simple, works on any OS)
- **inotify watcher:** Watch directory for `IN_CREATE` / `IN_MOVED_TO` events (Linux, low-latency, efficient)
- **S3 event trigger:** If storage is S3, subscribe to `s3:ObjectCreated` events via SNS/SQS

The `LinuxFilesystemScanner` already wraps Linux inotify infrastructure (`LinuxFilesystemScanner`) — this should be exposed as a `--watch` mode.
