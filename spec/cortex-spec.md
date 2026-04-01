# MetaLoom // Cortex — System Specification

**Version:** 1.0 (March 2026)
**Status:** In Development
**License:** Apache 2.0

---

## 1. Overview

**Cortex** is the worker node and media processing engine of the MetaLoom DAM system. It scans, analyzes, and indexes digital media assets, then (optionally) pushes structured metadata to a [Loom](./loom-spec.md) server. Cortex is deliberately **un-opinionated**: it does not store, import, or relocate files. Extracted metadata is written alongside media files as extended filesystem attributes (xattr), and optionally synchronized to Loom in **online mode**.

### Architecture Role

```
┌───────────────────────────────────────────────────────────┐
│                    Cortex Worker Node                     │
│                                                           │
│  ┌────────────┐   ┌────────────────────────────────────┐  │
│  │ Filesystem │──►│ Processor Pipeline (Action Chain)  │  │
│  │  Scanner   │   │                                    │  │
│  └────────────┘   │  hash → consistency → tika →       │  │
│                   │  fingerprint → thumbnail → face →  │  │
│                   │  whisper → ocr → captioning → llm  │  │
│                   └──────────────────┬─────────────────┘  │
│                                      │                    │
│                              [xattr / local cache]        │
│                                      │                    │
│                          (online mode only)               │
└──────────────────────────────────────┼────────────────────┘
                                       │ REST
                                       ▼
                              ┌─────────────────┐
                              │   Loom Server   │
                              └─────────────────┘
```

Cortex can be run:
- **Offline:** standalone, writing extracted data to xattr only
- **Online:** connected to Loom, pushing all extracted metadata via the Loom REST client

---

## 2. Technology Stack

| Component           | Technology                                            |
|---------------------|-------------------------------------------------------|
| Runtime             | Java (JVM)                                            |
| DI Framework        | Dagger 2                                              |
| Media Metadata      | Apache Tika                                           |
| Video Processing    | Video4J (OpenCV-based)                                |
| Audio Transcription | OpenAI Whisper (via external service)                 |
| Face Detection      | dlib face68 landmarks, InsightFace / InsperFace       |
| Object Detection    | YOLO (via yolo4j / ONNX Runtime)                      |
| Image Captioning    | SmolVLM (via HTTP service)                            |
| LLM Integration     | Ollama (local), OpenAI, Anthropic (via genai-utils)   |
| Fingerprinting      | MultiSectorVideoFingerprinter (Video4J)               |
| OCR                 | Tesseract (planned)                                   |
| Metadata storage    | Linux/macOS extended attributes (xattr)               |
| Serialization       | Apache Avro (action results)                          |
| Deployment          | CLI JAR, Kubernetes Job / CronJob                     |

---

## 3. Operational Modes

### 3.1 Offline Mode
Cortex processes media locally and writes results into extended filesystem attributes (`xattr`) attached to each file. No network connection to Loom is required. Results persist on the file system and survive restarts.

### 3.2 Online Mode
In addition to local xattr storage, Cortex connects to a Loom server via the Loom REST client (`LoomClient`) and:
- Fetches existing asset records before processing (to avoid redundant computation)
- Pushes computed metadata (hashes, embeddings, transcripts, etc.) back to Loom after each action

### 3.3 Processing State Tracking
Each action is idempotent. Before computing, an action checks:
1. whether media is of a processable type (`isProcessable`)
2. whether the result is already present locally in xattr (`isProcessed`)
3. optionally, whether Loom already has the result (`AssetResponse` from online mode)

If processing is complete, the action returns `NEXT` to pass control to the next action. If skipped or failed, the pipeline continues.

---

## 4. Media Types

Cortex classifies media into the following categories, checked by MIME type:

| Category    | Key MIME types                                            | Notes                                  |
|-------------|-----------------------------------------------------------|----------------------------------------|
| **Image**   | `image/jpeg`, `image/png`, `image/tiff`, `image/webp`    | Processed by thumbnail, face, caption  |
| **Video**   | `video/mp4`, `video/avi`, `video/quicktime`, `video/x-matroska` | Processed by fingerprint, whisper, face |
| **Audio**   | `audio/mpeg`, `audio/flac`, `audio/wav`, `audio/ogg`     | Processed by whisper, tika, audio comp |
| **Document**| `application/pdf`, `application/msword`, `text/plain`    | Processed by tika, OCR, LLM            |
| **Any**     | All media types                                           | Hashing and LLM (filename) apply to all|

MIME type detection is performed by the Tika service and stored during initial ingestion.

---

## 5. Action Pipeline

The **processor pipeline** executes a chain of `CortexAction` implementations on each media file. Actions are modular and independently enabled/disabled via configuration. Each action implements the `CortexAction` interface:

```
isProcessable(ctx)  → boolean  : Can this action handle this media type?
isProcessed(ctx)    → boolean  : Has this action already run (idempotency)?
compute(ctx, asset) → ActionResult : Execute the action
```

**`ActionResult`** carries:
- `origin`: `COMPUTED` (new), `REMOTE` (from Loom), `CACHED` (from xattr)
- status: `NEXT`, `SKIPPED`, `FAILED`, `DONE`

### Standard Pipeline Order

```
hash → consistency → tika → fingerprint → thumbnail → facedetect → whisper → ocr → captioning → llm → scene-detection → dedup → loom
```

---

## 6. Actions — Detailed Specification

### 6.1 Hash Actions

Four independent hash computations run on every asset:

#### `SHA512Action`
- **Name:** `sha512`
- **Input:** Any file
- **Output:** SHA-512 hex string
- **Storage:** xattr key, pushed to Loom as `asset.sha512sum` (primary key)
- **Purpose:** Content-addressable identity; deduplification

#### `SHA256Action`
- **Name:** `sha256`
- **Input:** Any file
- **Output:** SHA-256 hex string
- **Storage:** xattr, Loom `asset.sha256sum`

#### `MD5Action`
- **Name:** `md5`
- **Input:** Any file
- **Output:** MD5 hex string
- **Storage:** xattr, Loom `asset.md5sum`
- **Note:** Provided for legacy interoperability only; not collision-resistant

#### `ChunkHashAction`
- **Name:** `chunk_hash`
- **Input:** Any file
- **Output:** Partial/chunk-based hash over a representative data window
- **Storage:** xattr, Loom `asset.chunk_hash`
- **Purpose:** Fast near-duplicate detection without full file read; also tracks `zero_chunk_count` for sparse/blank file detection

---

### 6.2 ConsistencyAction

- **Name:** `consistency`
- **Input:** Video, Audio
- **Output:** Boolean `isComplete` flag
- **Storage:** xattr `ConsistencyMedia.CONSISTENCY`
- **Purpose:** Validates that a media file is complete and not truncated or corrupted. Downstream actions (fingerprint, thumbnail) use this flag to decide whether to process incomplete media (configurable via `processIncomplete` option).

---

### 6.3 TikaAction

- **Name:** `tika`
- **Input:** Image, Audio, Video, Document
- **Output:** Parsed metadata fields (title, author, date, embedded text, EXIF tags)
- **Storage:** xattr `TikaMedia.TIKA` (flag: `"DONE"` or `"NULL"`)
- **Loom target:** `asset_doc_comp` (text + word count), `asset_image_comp` (EXIF width/height, dominant color), `asset_audio_comp` (encoding, BPM, sampling rate), `asset_geo_comp` (GPS EXIF coordinates)
- **Library:** Apache Tika (`MediaTikaParser`)
- **Behavior:**
  - Parses file content and embedded metadata using Tika
  - On error: sets flag to `"DONE"` and reports failure (does not retry)
  - `"NULL"` flag indicates a previously failed extraction that should be skipped

---

### 6.4 FingerprintAction

- **Name:** `fingerprint`
- **Input:** Video files
- **Output:** Multi-sector perceptual video fingerprint
- **Storage:** xattr `FingerprintMedia.FINGERPRINT`
- **Loom target:** `asset.fingerprint` (via `AssetResponse.getFingerprint()`)
- **Library:** `MultiSectorVideoFingerprinterImpl` (Video4J)
- **Behavior:**
  - Extracts a perceptual fingerprint by sampling multiple time-sectors of the video
  - Enables robust near-duplicate video detection independent of re-encoding or bit-rate changes
  - Idempotency check: if fingerprint already in xattr or Loom response, returns `REMOTE`/`CACHED`
  - Respects `ConsistencyAction` result: skips incomplete videos unless `processIncomplete` is enabled

---

### 6.5 ThumbnailAction

- **Name:** `thumbnail`
- **Input:** Video files (image support planned)
- **Output:** Composite preview image (N×M tile grid of video frames)
- **Storage:** xattr flag `ThumbnailMedia.THUMBNAIL` → `DONE` / `FAILED`; binary written to Loom as `attachment` (type: `ASSET_THUMBNAIL`)
- **Library:** `PreviewGenerator` (Video4J)
- **Configuration:** `tileSize`, `cols`, `rows` (grid dimensions)
- **Behavior:**
  - Samples frames evenly distributed across video duration
  - Generates a composite tile image (contact sheet / story board)
  - Pushes binary thumbnail to Loom via attachment API
  - Skips incomplete videos unless `processIncomplete` is enabled

---

### 6.6 FacedetectAction

- **Name:** `facedetect`
- **Input:** Image, Video
- **Output:** Bounding boxes and 128-dimensional face embeddings per detected face
- **Storage:** xattr `FacedetectMedia` / `FaceStorage` (Avro serialized)
- **Loom target:** `embedding` table (type: `"dlib_facemark"`) + `attachment` (type: `EMBEDDING_ATTACHMENT`, face crop image)
- **Detection backends:**
  - **dlib face68:** Classic facial landmark detection (68-point model)
  - **InsightFace / InsperFace:** Deep-learning face recognition (configurable)
- **Detection type enum:** `DLIB`, `INSPIREFACE`
- **Video scanning:**
  - Uses `VideoFaceScanner` to process frames via a sliding `FrameWindow`
  - Deduplicates faces across frames via `VideoFaceScannerReport`
  - Output: `VideoFace` records with frame timestamps and face coordinates
- **Outputs per face:**
  - `areaStartX`, `areaStartY`, `areaWidth`, `areaHeight` — bounding box
  - `vector: real[]` — 128-d face embedding
  - `fromTime`, `toTime` — temporal range in video (ms)
  - `source` — face index (e.g. `"face_0"`, `"face_1"`)
- **FacedescriptionAction** (sub-action): generates a textual description of a detected face using a vision model

---

### 6.7 WhisperAction

- **Name:** `whisper`
- **Input:** Video, Audio
- **Output:** Speech-to-text transcript with segment-level timing
- **Storage:** xattr `WhisperMedia.WHISPER`
- **Loom target:** `asset_transcript_comp` (lang, transcript_text, duration, model, transcript_json)
- **Backend:** OpenAI Whisper (via `WhisperMediaProcessor`, called as external service)
- **Output fields:**
  - `transcript_text`: full concatenated plain text
  - `transcript_json`: full Whisper JSON output (segments, tokens, start/end times)
  - `lang`: detected or specified language (BCP-47)
  - `model`: model identifier (e.g. `"whisper-1"`)
  - `segments`: count used for progress/debug output
- **Behavior:**
  - Enabled/disabled via configuration (`options.isEnabled()`)
  - On success: writes result to xattr and Loom
  - On failure: logs error, returns `FAILED`

---

### 6.8 OCRAction

- **Name:** `ocr`
- **Input:** Image
- **Output:** Extracted text from image via optical character recognition
- **Storage:** TBD (xattr + `asset_doc_comp`)
- **Library:** Tesseract (planned)
- **Status:** Placeholder; not yet implemented — returns `SKIPPED`

---

### 6.9 CaptioningAction

- **Name:** `captioning`
- **Input:** Image (Video planned)
- **Output:** Natural language caption describing image content
- **Storage:** xattr key `caption_result` (type: `XATTR`)
- **Backend:** **SmolVLM** vision-language model via HTTP API (`SmolVLMClient`)
- **Configuration:** `smolVLMHost`, `smolVLMPort`
- **Behavior:**
  - Loads image as `BufferedImage` and sends to SmolVLM with a target resolution of 512px
  - Stores the returned caption string as xattr metadata
  - Video captioning not yet implemented
  - On exception: prints stack trace and returns `FAILED`

---

### 6.10 LLMAction

- **Name:** `llm`
- **Input:** Any media (uses filename as input)
- **Output:** Structured JSON metadata extracted from the filename by an LLM
- **Storage:** Per-prompt xattr key (`result_<promptId>_v<version>`, type: `XATTR`)
- **Backend:** **Ollama** (default), with support for OpenAI and Anthropic via `genai-utils`
- **Configuration:** Named prompt map. Each prompt has:
  - `model`: LLM model name (e.g. `"gemma2:27b"`)
  - `prompt`: Prompt template with `${name}` placeholder for filename
- **Default prompt:** Instructs LLM to extract `format`, `genre`, `year`, `title` from filename as JSON
- **Behavior:**
  - Idempotency: checks whether all configured prompt result keys are present in xattr
  - Supports multiple named prompts per run
  - Sends each prompt to configured LLM provider, stores result JSON in xattr
  - Result is pushed to Loom `asset.meta` jsonb or a dedicated field

---

### 6.11 SceneDetectionAction

- **Name:** `scene-detection`
- **Input:** Video
- **Output:** List of detected scene change timestamps
- **Storage:** xattr `SceneDetectionMedia.SCENE_DETECTION`
- **Library:** `OpticalFlowSceneDetector` (custom OpenCV optical flow analysis via Video4J)
- **Enabled:** Configurable via `options.isEnabled()`
- **Output:** `SceneDetectionResult` containing scene boundary timestamps (ms)
- **Behavior:**
  - Opens video with `VideoFile.open(path)`
  - Detects scene changes by analyzing frame-to-frame optical flow magnitude
  - Returns `SKIPPED` for non-video media

---

### 6.12 Hash-based Deduplication (`HashDedupAction`)

- **Name:** `hash-dedup`
- **Input:** Any media with computed hashes
- **Output:** Deduplication report — identifies identical files by SHA-512 match
- **Behavior:** Queries Loom for assets with the same `sha512sum`. If a duplicate is found, records the relationship.

### 6.13 Fingerprint-based Deduplication (`FingerprintDedupAction`)

- **Name:** `fingerprint-dedup`
- **Input:** Video with computed fingerprint
- **Output:** Near-duplicate candidates from Loom vector search
- **Behavior:** Queries Loom/Qdrant using the computed fingerprint vector to find perceptually similar videos

---

### 6.14 LoomAction

- **Name:** `loom`
- **Input:** Any media that has been processed by preceding actions
- **Output:** Synchronized asset record in Loom
- **Behavior:**
  - Reads all xattr metadata collected by previous actions
  - Constructs a full asset create/update request
  - Pushes data to Loom via `LoomClient` (REST)
  - Registers asset, asset_location, all components, embeddings, attachments, transcripts
  - Acts as the final **flush** step in the online processing pipeline

---

## 7. Metadata Storage — Extended Attributes (xattr)

Cortex uses the Linux/macOS **extended attributes** (`xattr`) mechanism to store processing results inline with media files. This enables:
- Persistence without a database dependency
- Atomic per-file results (no external state)
- Portability: results survive file copies on the same filesystem

### Key Naming Convention

xattr keys follow the pattern:
```
user.<namespace>.<key>_v<version>
```

Examples:
- `user.hash.sha512_v1`
- `user.fingerprint.fingerprint_v1`
- `user.tika.flags_v1`
- `user.whisper.result_v1`
- `user.caption_result_v1`

### Data Formats

| Action       | Format         | Notes                                |
|--------------|----------------|--------------------------------------|
| Hash         | hex string     | SHA-512, SHA-256, MD5, chunk hash    |
| Fingerprint  | base64 string  | MultiSector fingerprint bytes        |
| Tika         | string flag    | `"DONE"` or `"NULL"`                 |
| Whisper      | Avro binary    | `WhisperResult` serialized via Avro  |
| Face         | Avro binary    | `Facedetection` Avro schema          |
| Scene        | JSON string    | Timestamp list                       |
| Caption      | string         | Plain text caption                   |
| LLM          | JSON string    | Prompt-specific result JSON          |
| Thumbnail    | string flag    | `"DONE"` or `"FAILED"`               |
| Consistency  | boolean string | `"true"` / `"false"`                 |

---

## 8. Processor Pipeline

The **FilesystemProcessor** recursively scans a configured directory tree:

1. Discovers files via `FilesystemProcessor.scan(path)`
2. For each file, constructs a `LoomMedia` context wrapper
3. Passes the media through the `DefaultMediaProcessor` pipeline
4. Each `CortexAction` in the chain is called sequentially
5. Results are written to xattr after each action
6. In online mode, a final `LoomAction` syncs all results to Loom

### Media Context (`LoomMedia`)

The `LoomMedia` object wraps a filesystem path and provides:
- Type detection: `isImage()`, `isVideo()`, `isAudio()`, `isDocument()`
- MIME type access
- xattr read/write via `has(key)`, `get(key)`, `put(key, value)`
- Typed component access: `ctx.media(TIKA)`, `ctx.media(FINGERPRINT)`, etc.

### Action Context (`ActionContext`)

Wraps `LoomMedia` for the current action invocation:
- `ctx.media()` — base media
- `ctx.media(ComponentKey)` — typed component view (e.g. `TikaMedia`, `WhisperMedia`)
- `ctx.next()` — advance to next action (success)
- `ctx.skipped(reason)` — skip this action, continue pipeline
- `ctx.failure(reason)` — record failure, continue pipeline
- `ctx.origin(COMPUTED|REMOTE|CACHED)` — tag result origin

---

## 9. Configuration

Cortex is configured via `CortexOptions` and per-action `*ActionOptions` objects. Key settings:

### Global (`CortexOptions`)
| Setting          | Description                                      |
|------------------|--------------------------------------------------|
| `loomUrl`        | Loom server base URL (online mode)               |
| `loomToken`      | API token for Loom authentication                |
| `scanPaths`      | List of root directories to scan                 |

### Per-Action Options

Each action has a corresponding `*ActionOptions` class:

| Action          | Key Options                                                  |
|-----------------|--------------------------------------------------------------|
| Thumbnail       | `tileSize`, `cols`, `rows`, `processIncomplete`              |
| Fingerprint     | `processIncomplete`                                          |
| Whisper         | `enabled`, model endpoint URL                                |
| Scene Detection | `enabled`                                                    |
| Captioning      | `smolVLMHost`, `smolVLMPort`                                 |
| LLM             | `prompts` (map of named prompt configs: `model`, `prompt`)   |
| Face Detection  | `enabled`, `detectionType` (`DLIB` / `INSPIREFACE`)          |

---

## 10. Actions per Asset Type — Matrix

| Action              | Image | Video | Audio | Document | Any |
|---------------------|:-----:|:-----:|:-----:|:--------:|:---:|
| SHA-512             | ✓     | ✓     | ✓     | ✓        | ✓   |
| SHA-256             | ✓     | ✓     | ✓     | ✓        | ✓   |
| MD5                 | ✓     | ✓     | ✓     | ✓        | ✓   |
| Chunk Hash          | ✓     | ✓     | ✓     | ✓        | ✓   |
| Consistency Check   |       | ✓     | ✓     |          |     |
| Tika (metadata)     | ✓     | ✓     | ✓     | ✓        |     |
| Fingerprint         |       | ✓     |       |          |     |
| Thumbnail           |       | ✓     |       |          |     |
| Face Detection      | ✓     | ✓     |       |          |     |
| Whisper (ASR)       |       | ✓     | ✓     |          |     |
| OCR                 | ✓     |       |       |          |     |
| Captioning (VLM)    | ✓     | ✓*   |       |          |     |
| LLM (filename)      | ✓     | ✓     | ✓     | ✓        | ✓   |
| Scene Detection     |       | ✓     |       |          |     |
| Hash Dedup          | ✓     | ✓     | ✓     | ✓        | ✓   |
| Fingerprint Dedup   |       | ✓     |       |          |     |
| Loom Sync           | ✓     | ✓     | ✓     | ✓        | ✓   |

*Video captioning is planned but not yet implemented.

---

## 11. Loom Integration (Online Mode)

When `LoomClient` is configured, Cortex:

1. **Before processing:** calls `GET /api/v1/assets/:sha512` to retrieve existing data
2. **After each action:** may push intermediate results (e.g. hash, embedding)
3. **Final LoomAction:** pushes complete asset record including:
   - Core asset fields (hashes, MIME type, filename, size)
   - `asset_location` (filesystem path, library, inode keys)
   - All component tables (geo, image, video, audio, doc, transcript)
   - `embedding` rows (face vectors with bounding box data)
   - `attachment` rows (thumbnail binary, face crop images)
   - `asset_transcript_comp` (Whisper output)

Authentication uses a token from `CortexOptions.loomToken`, sent as a `Bearer` header.

---

## 12. CLI Interface

Cortex exposes a command-line interface (CLI) for scheduling and integration:

```bash
cortex scan --path /media/library --mode online --loom-url http://loom:8080
cortex scan --path /media/library --mode offline
cortex process --file /path/to/video.mp4 --actions hash,tika,fingerprint
```

Suitable for:
- Cron-based periodic scanning
- Kubernetes `Job` or `CronJob` workloads
- CI/CD pipeline integration

---

## 13. Deployment

### Modes
| Mode        | Description                               |
|-------------|-------------------------------------------|
| Standalone  | Single JAR, manual invocation or cron     |
| Kubernetes  | K8s Job or CronJob per library scan       |
| Docker      | Container image for portable deployment   |

### Dependencies (Runtime)
- Java 17+ JVM
- Linux filesystem with xattr support (or macOS)
- (Optional) GPU for accelerated face detection (CUDA via dlib/InsightFace)
- (Optional) ONNX Runtime for YOLO inference (`yolo4j`)
- External services: Whisper API, SmolVLM HTTP, Ollama endpoint

---

## 14. Extension Points

### Adding a Custom Action

Implement `CortexAction` (or extend `AbstractMediaAction`):

```java
@Singleton
public class MyAction extends AbstractMediaAction<MyActionOptions> {
    @Override public String name() { return "my-action"; }
    
    @Override
    protected boolean isProcessable(ActionContext ctx) {
        return ctx.media().isImage();
    }
    
    @Override
    protected boolean isProcessed(ActionContext ctx) {
        return ctx.media().has(MY_META_KEY);
    }
    
    @Override
    protected ActionResult compute(ActionContext ctx, AssetResponse asset) {
        // ... process ...
        return ctx.origin(COMPUTED).next();
    }
}
```

Register the action in the Dagger module and add it to the pipeline configuration.
