# MetaLoom // Loom — System Specification

**Version:** 1.0 (March 2026)
**Status:** In Development
**License:** Apache 2.0

---

## 1. Overview

**Loom** is the backend server and storage component of the MetaLoom Digital Asset Management (DAM) system. It acts as the central authority for asset metadata, user management, access control, and event distribution. Loom persists all structured metadata extracted by [Cortex](./cortex-spec.md) worker nodes and exposes it via REST, GraphQL, and (planned) gRPC APIs.

### Architecture Role

```
┌──────────────┐        REST / gRPC / GraphQL         ┌─────────────────┐
│   Clients    │ ◄──────────────────────────────────► │   Loom Server   │
│ (Browser /   │                                      │  (REST + Auth)  │
│  CLI / App)  │                                      └────────┬────────┘
└──────────────┘                                               │
                                                   ┌───────────┴────────────┐
                                           ┌───────┴───────┐  ┌─────────────┴─────┐
                                           │  PostgreSQL   │  │  Elasticsearch    │
                                           │  (Primary DB) │  │  / Lucene / Qdrant│
                                           └───────────────┘  └───────────────────┘
```

Cortex worker nodes operate independently and push extracted metadata back to Loom via the Loom REST client.

---

## 2. Technology Stack

| Component         | Technology                         |
|-------------------|------------------------------------|
| Runtime           | Java (JVM)                         |
| HTTP Framework    | Eclipse Vert.x                     |
| Database          | PostgreSQL (primary), MariaDB (alt)|
| Schema Migrations | Flyway                             |
| ORM / Query       | jOOQ (generated)                   |
| DI Framework      | Dagger 2                           |
| Search            | Elasticsearch, Apache Lucene       |
| Vector Search     | Qdrant                             |
| Doc Parsing       | Apache Tika                        |
| Messaging         | Internal Vert.x Eventbus           |
| API Protocols     | REST (JSON), GraphQL, gRPC (planned)|
| Metadata Protocol | MCP (Model Context Protocol)       |
| Monitoring        | Prometheus metrics endpoint        |
| Containerization  | Docker / Helm (Kubernetes)         |

---

## 3. Database Schema

The database lives in the `loom` PostgreSQL schema. All tables use UUID v4 primary keys (via `uuid-ossp` extension). Every mutable entity records `created`, `creator_uuid`, `edited`, and `editor_uuid` audit columns.

### 3.1 Core User & Access Control

#### `user`
Stores all system users.

| Column          | Type      | Notes                                         |
|-----------------|-----------|-----------------------------------------------|
| `uuid`          | uuid (PK) | Auto-generated v4 UUID                        |
| `username`      | varchar   | Unique, required                              |
| `firstname`     | varchar   |                                               |
| `lastname`      | varchar   |                                               |
| `email`         | varchar   |                                               |
| `enabled`       | boolean   | Enable/disable account; default `true`        |
| `deleted`       | boolean   | Soft-delete flag; default `false`             |
| `sso`           | boolean   | Created via SSO; default `false`              |
| `password_hash` | varchar   | Bcrypt or similar hash                        |
| `meta`          | jsonb     | Arbitrary custom properties                   |

#### `token`
API tokens scoped to a user.

| Column        | Type      | Notes                                   |
|---------------|-----------|-----------------------------------------|
| `uuid`        | uuid (PK) |                                         |
| `name`        | varchar   | Unique per creator                      |
| `description` | varchar   |                                         |
| `token`       | varchar   | Unique token value                      |
| `meta`        | jsonb     |                                         |

#### `role`
Named role for grouping permissions.

| Column | Type      | Notes       |
|--------|-----------|-------------|
| `uuid` | uuid (PK) |             |
| `name` | varchar   | Unique      |
| `meta` | jsonb     |             |

#### `group`
Named group for associating users with roles.

| Column | Type      | Notes  |
|--------|-----------|--------|
| `uuid` | uuid (PK) |        |
| `name` | varchar   | Unique |
| `meta` | jsonb     |        |

#### Junction tables

| Table            | Description                              |
|------------------|------------------------------------------|
| `user_group`     | M:N user ↔ group membership             |
| `role_group`     | M:N group ↔ role assignment             |
| `role_permission`| Role → resource + permission grants      |
| `user_permission`| Per-user permission overrides            |
| `token_permission`| Per-token permission grants             |

#### `loom_permission` enum
Fine-grained CRUD permissions per entity type:

`CREATE_ANNOTATION`, `READ_ANNOTATION`, `DELETE_ANNOTATION`, `UPDATE_ANNOTATION`,
`CREATE_ASSET`, `READ_ASSET`, `DELETE_ASSET`, `UPDATE_ASSET`,
`CREATE_ASSET_LOCATION`, ..., `CREATE_ATTACHMENT`, ..., `CREATE_USER`, ...,
`CREATE_ROLE`, ..., `CREATE_GROUP`, ..., `CREATE_PROJECT`, ..., `CREATE_CLUSTER`, ...,
`CREATE_COLLECTION`, ..., `CREATE_COMMENT`, ..., `CREATE_EMBEDDING`, ...,
`CREATE_REACTION`, ..., `CREATE_TASK`, ..., `CREATE_TAG`, ...,
`TAG_ASSET`, `UNTAG_ASSET`,
`CREATE_TOKEN`, ..., `CREATE_WEBHOOK`, ..., `CREATE_LIBRARY`, ...

---

### 3.2 Asset & Storage

#### `asset`
The central asset record, keyed by SHA-512 hash (content-addressable). Each unique binary has exactly one `asset` row.

| Column           | Type      | Notes                                                 |
|------------------|-----------|-------------------------------------------------------|
| `uuid`           | uuid      | Unique index (secondary identifier)                   |
| `sha512sum`      | varchar (PK)| Content-addressable primary key                    |
| `sha256sum`      | varchar   | Optional secondary hash                               |
| `md5sum`         | varchar   | Optional MD5                                          |
| `chunk_hash`     | varchar   | Partial/chunk-based hash for similarity               |
| `zero_chunk_count`| bigint   | Count of zero-filled chunks (sparse file detection)   |
| `size`           | bigint    | File size in bytes                                    |
| `mime_type`      | varchar   | MIME type e.g. `image/jpeg`, `video/mp4`              |
| `filename`       | varchar   | Original filename at first ingest                     |
| `initial_origin` | varchar   | First observed path, URL, or S3 key                   |
| `first_seen`     | timestamp | First ingestion timestamp                             |
| `s3_bucket_name` | varchar   | S3 storage bucket (optional)                          |
| `s3_object_path` | varchar   | S3 object key (optional)                              |
| `meta`           | jsonb     | Arbitrary custom metadata                             |

#### `asset_location`
Tracks filesystem locations where an asset has been found. Multiple locations may reference the same `asset` (deduplication).

| Column             | Type      | Notes                                          |
|--------------------|-----------|------------------------------------------------|
| `uuid`             | uuid (PK) |                                                |
| `asset_uuid`       | uuid (FK) | References `asset`; CASCADE delete             |
| `library_uuid`     | uuid (FK) | Owning library; CASCADE delete                 |
| `path`             | varchar   | Current known filesystem path                  |
| `filekey_inode`    | int       | inode for change detection                     |
| `filekey_stdev`    | int       | Device number                                  |
| `filekey_edate`    | int       | Entry date (epoch seconds)                     |
| `filekey_edate_nano`| int      | Nanosecond precision of entry date             |
| `mime_type`        | varchar   | MIME override for this location                |
| `license`          | varchar   | License string for this location               |
| `state`            | varchar   | Processing state of this location              |
| `locked_by_uuid`   | uuid (FK) | User who currently holds a processing lock     |
| `meta`             | jsonb     |                                                |

#### `asset_remix`
Records derivation/remix relationships between two assets.

| Column         | Type      | Notes              |
|----------------|-----------|--------------------|
| `asset_a_uuid` | uuid (FK) | Source asset       |
| `asset_b_uuid` | uuid (FK) | Derived/remix asset|
| `meta`         | jsonb     |                    |

---

### 3.3 Asset Components (Faceted Metadata)

Each component table allows multiple entries per asset (identified by `source`), enabling multi-track or multi-source metadata.

#### `asset_geo_comp` — Geolocation
| Column      | Type           | Notes                          |
|-------------|----------------|--------------------------------|
| `asset_uuid`| uuid (FK)      |                                |
| `source`    | varchar        | e.g. `"exif"`, `"gps-track"`  |
| `geo_lon`   | decimal(9,6)   | Longitude                      |
| `geo_lat`   | decimal(8,6)   | Latitude                       |
| `geo_alias` | varchar        | Human-readable place name      |

#### `asset_image_comp` — Image Properties
| Column                 | Type      | Notes                  |
|------------------------|-----------|------------------------|
| `asset_uuid`           | uuid (FK) |                        |
| `source`               | varchar   | e.g. `"exif"`          |
| `image_dominant_color` | varchar   | Hex color string       |
| `media_width`          | int       | Pixel width            |
| `media_height`         | int       | Pixel height           |

#### `asset_video_comp` — Video Properties
| Column           | Type      | Notes                    |
|------------------|-----------|--------------------------|
| `asset_uuid`     | uuid (FK) |                          |
| `source`         | varchar   | e.g. `"ffprobe"`         |
| `media_width`    | int       | Frame width in pixels    |
| `media_height`   | int       | Frame height in pixels   |
| `media_duration` | bigint    | Duration in milliseconds |
| `video_bitrate`  | int       | Bitrate in kbps          |
| `video_encoding` | varchar   | Codec e.g. `"h264"`      |

#### `asset_audio_comp` — Audio Properties
| Column              | Type      | Notes                      |
|---------------------|-----------|----------------------------|
| `asset_uuid`        | uuid (FK) |                            |
| `source`            | varchar   | e.g. `"ffprobe"`, `"id3"`  |
| `audio_bpm`         | int       | Beats per minute           |
| `audio_sampling_rate`| int      | Sample rate in Hz          |
| `audio_channels`    | int       | Channel count              |
| `audio_bitrate`     | int       | Bitrate in kbps            |
| `audio_encoding`    | varchar   | Codec e.g. `"mp3"`, `"flac"`|
| `media_duration`    | bigint    | Duration in milliseconds   |

#### `asset_doc_comp` — Document/Text
| Column           | Type      | Notes                         |
|------------------|-----------|-------------------------------|
| `asset_uuid`     | uuid (FK) |                               |
| `source`         | varchar   | e.g. `"tika"`                 |
| `doc_plain_text` | text      | Extracted full plain text     |
| `doc_word_count` | int       | Word count                    |

#### `asset_transcript_comp` — Speech Transcription
| Column            | Type      | Notes                                    |
|-------------------|-----------|------------------------------------------|
| `asset_uuid`      | uuid (FK) |                                          |
| `source`          | varchar   | e.g. `"whisper-pipeline"`                |
| `lang`            | varchar   | BCP-47 language code e.g. `"en"`, `"de"` |
| `transcript_text` | text      | Full concatenated transcript text        |
| `duration`        | int       | Duration in seconds                      |
| `model`           | varchar   | Model name e.g. `"whisper-1"`            |
| `transcript_json` | jsonb     | Full Whisper output (segments, tokens)   |

---

### 3.4 Embeddings & Clusters

#### `embedding`
Stores vector embeddings extracted from assets, e.g. face embeddings or perceptual hashes.

| Column       | Type      | Notes                                     |
|--------------|-----------|-------------------------------------------|
| `uuid`       | uuid (PK) |                                           |
| `asset_uuid` | uuid (FK) | CASCADE delete                            |
| `type`       | varchar   | e.g. `"dlib_facemark"`, `"clip"`, `"phash"` |
| `vector`     | real[]    | Raw embedding vector                       |
| `source`     | varchar   | e.g. face index from dlib                 |
| `fromTime`   | int       | Time offset start (ms) for video segment  |
| `toTime`     | int       | Time offset end (ms) for video segment    |
| `areaStartX` | int       | Bounding box X origin (pixels)            |
| `areaStartY` | int       | Bounding box Y origin (pixels)            |
| `areaWidth`  | int       | Bounding box width (pixels)               |
| `areaHeight` | int       | Bounding box height (pixels)              |
| `meta`       | jsonb     |                                           |

#### `cluster`
Named grouping of embeddings, e.g. a person whose face appears in multiple assets.

| Column | Type      | Notes                                              |
|--------|-----------|----------------------------------------------------|
| `uuid` | uuid (PK) |                                                    |
| `name` | varchar   | Unique; e.g. person's name                         |
| `type` | varchar   | e.g. `"person"`, `"visual_similarity"`             |
| `meta` | jsonb     |                                                    |

#### Junction tables

| Table               | Description                           |
|---------------------|---------------------------------------|
| `embedding_cluster` | M:N embedding ↔ cluster               |
| `collection_cluster`| M:N collection ↔ cluster              |
| `tag_cluster`       | M:N tag ↔ cluster                     |

---

### 3.5 Organization Hierarchy

#### `library`
Top-level organizational unit. Groups collections and assets from one logical domain (e.g. one storage mount point or S3 bucket).

| Column        | Type      | Notes  |
|---------------|-----------|--------|
| `uuid`        | uuid (PK) |        |
| `name`        | varchar   |        |
| `description` | varchar   |        |
| `meta`        | jsonb     |        |

#### `collection`
Named grouping of assets within a project. Collections are hierarchical (parent/child).

| Column                   | Type      | Notes                         |
|--------------------------|-----------|-------------------------------|
| `uuid`                   | uuid (PK) |                               |
| `name`                   | varchar   | Unique                        |
| `description`            | varchar   |                               |
| `parent_collection_uuid` | uuid (FK) | Self-referential hierarchy    |
| `meta`                   | jsonb     |                               |

#### `project`
High-level work context. Can contain multiple libraries and collections.

| Column        | Type      | Notes  |
|---------------|-----------|--------|
| `uuid`        | uuid (PK) |        |
| `name`        | varchar   |        |
| `description` | varchar   |        |
| `meta`        | jsonb     |        |

#### Junction tables

| Table                | Description                        |
|----------------------|------------------------------------|
| `library_asset`      | M:N library ↔ asset                |
| `library_collection` | M:N library ↔ collection           |
| `collection_asset`   | M:N collection ↔ asset             |
| `project_library`    | M:N project ↔ library              |
| `project_collection` | M:N project ↔ collection           |

---

### 3.6 Tagging

#### `tag`
System-wide named tag belonging to a named collection (namespace).

| Column       | Type      | Notes                               |
|--------------|-----------|-------------------------------------|
| `uuid`       | uuid (PK) |                                     |
| `name`       | varchar   | Unique within collection            |
| `collection` | varchar   | Namespace/category for the tag      |
| `color`      | char(6)   | Hex RGB color                       |
| `rating`     | int       | Precomputed aggregate rating        |
| `meta`       | jsonb     |                                     |

#### `tag_user_meta`
Per-user tag metadata (ratings).

#### Junction tables

| Table            | Description                                                      |
|------------------|------------------------------------------------------------------|
| `tag_asset`      | M:N tag ↔ asset; includes optional temporal/spatial region info   |
| `tag_collection` | M:N tag ↔ collection                                              |
| `tag_cluster`    | M:N tag ↔ cluster                                                 |

`tag_asset` supports region/time-coded tagging:

| Column       | Type | Notes                    |
|--------------|------|--------------------------|
| `time_from`  | int  | Start time in ms         |
| `time_to`    | int  | End time in ms           |
| `areaStartX` | int  | Bounding box X (pixels)  |
| `areaStartY` | int  | Bounding box Y (pixels)  |
| `areaWidth`  | int  | Bounding box width       |
| `areaHeight` | int  | Bounding box height      |

---

### 3.7 Workflow & Collaboration

#### `task`
Workflow task attached to assets or annotations.

| Column       | Type           | Notes                                        |
|--------------|----------------|----------------------------------------------|
| `uuid`       | uuid (PK)      |                                              |
| `title`      | varchar        | Required                                     |
| `content`    | varchar        | Description                                  |
| `due_date`   | timestamp      |                                              |
| `status`     | task_status    | `PENDING`, `REVIEW`, `ACCEPTED`, `REJECTED`  |
| `priority`   | int            |                                              |

#### `annotation`
Time- and/or area-coded annotation on an asset.

| Column        | Type            | Notes                                              |
|---------------|-----------------|----------------------------------------------------|
| `uuid`        | uuid (PK)       |                                                    |
| `type`        | annotation_type | `FEEDBACK`, `TAG`, `CHAPTER`                       |
| `asset_uuid`  | uuid (FK)       | CASCADE delete                                     |
| `title`       | varchar         |                                                    |
| `description` | varchar         |                                                    |
| `time_from`   | int             | Start time in ms (for video/audio)                 |
| `time_to`     | int             | End time in ms                                     |
| `areaStartX`  | int             | Bounding box X (pixels)                            |
| `areaStartY`  | int             | Bounding box Y                                     |
| `areaWidth`   | int             | Bounding box width                                 |
| `areaHeight`  | int             | Bounding box height                                |
| `thumbnail`   | varchar         | Reference to thumbnail depicting the annotated area|
| `meta`        | jsonb           |                                                    |

#### Junction tables

| Table              | Description                  |
|--------------------|------------------------------|
| `annotation_task`  | M:N annotation ↔ task        |
| `annotation_asset` | M:N annotation ↔ asset       |
| `annotation_tag`   | M:N annotation ↔ tag         |
| `asset_task`       | M:N asset ↔ task             |

---

### 3.8 Social & Reactions

#### `comment`
Threaded comment on an asset, task, or annotation.

| Column            | Type      | Notes                             |
|-------------------|-----------|-----------------------------------|
| `uuid`            | uuid (PK) |                                   |
| `title`           | varchar   | Optional title                    |
| `text`            | varchar   | Required body text                |
| `parent_uuid`     | uuid (FK) | Self-ref for threading            |
| `task_uuid`       | uuid (FK) | Optional: comment on a task       |
| `asset_uuid`      | uuid (FK) | Optional: comment on an asset     |
| `annotation_uuid` | uuid (FK) | Optional: comment on annotation   |

#### `reaction`
Emoji/rating reaction on an asset, task, comment, or annotation.

| Column            | Type      | Notes                                    |
|-------------------|-----------|------------------------------------------|
| `uuid`            | uuid (PK) |                                          |
| `type`            | varchar   | e.g. `"thumbsup"`, `"heart"`             |
| `rating`          | int       | Numeric rating (-1 to 5 etc.)            |
| `asset_uuid`      | uuid (FK) | Optional target                          |
| `task_uuid`       | uuid (FK) | Optional target                          |
| `comment_uuid`    | uuid (FK) | Optional target                          |
| `annotation_uuid` | uuid (FK) | Optional target                          |
| `meta`            | jsonb     |                                          |

Unique constraints prevent duplicate reactions of the same type per user per target.

---

### 3.9 Attachments

#### `attachment_binary`
Deduplicated storage for attachment binaries (e.g. thumbnails, face crops).

| Column      | Type         | Notes                       |
|-------------|--------------|-----------------------------|
| `sha512sum` | varchar (PK) | Content-addressed binary    |
| `size`      | bigint       |                             |

#### `attachment`
Links a binary to an asset or embedding with a semantic type.

| Column            | Type            | Notes                                       |
|-------------------|-----------------|---------------------------------------------|
| `uuid`            | uuid (PK)       |                                             |
| `binary_sha512sum`| varchar (FK)    | References `attachment_binary`              |
| `asset_uuid`      | uuid (FK)       | Optional: attached to asset                 |
| `embedding_uuid`  | uuid (FK)       | Optional: attached to embedding (face crop) |
| `type`            | attachment_type | `ASSET_THUMBNAIL`, `EMBEDDING_ATTACHMENT`   |
| `mime_type`       | varchar         | e.g. `"image/webp"`                         |
| `filename`        | varchar         |                                             |
| `meta`            | jsonb           |                                             |

---

### 3.10 Auxiliary Tables

#### `blacklist`
Block an asset from processing or display (e.g. copyright claim, virus scan hit).

| Column          | Type      | Notes                                      |
|-----------------|-----------|--------------------------------------------|
| `uuid`          | uuid (PK) |                                            |
| `asset_uuid`    | uuid (FK) | CASCADE delete                             |
| `type`          | varchar   | e.g. `"copyright"`, `"virus"`             |
| `review_count`  | int       | Number of review cycles                    |
| `meta`          | jsonb     |                                            |

#### `webhook`
Registered HTTP callbacks for system events.

| Column        | Type        | Notes                                         |
|---------------|-------------|-----------------------------------------------|
| `uuid`        | uuid (PK)   |                                               |
| `url`         | varchar     | Target endpoint URL                           |
| `status`      | varchar     | Last delivery status                          |
| `active`      | boolean     | Enable/disable; default `true`                |
| `triggers`    | loom_events | Array of triggering events                    |
| `secretToken` | varchar     | HMAC secret for request authentication        |
| `meta`        | jsonb       |                                               |

#### `loom_events` enum

System events that can trigger webhooks:
`USER_LOGGED_IN`, `USER_CREATED`, `USER_UPDATED`, `USER_DELETED`, `USER_MAPPED`,
`GROUP_CREATED`, `GROUP_UPDATED`, `GROUP_DELETED`,
`ROLE_CREATED`, `ROLE_UPDATED`, `ROLE_DELETED`,
`ASSET_UPLOADED`, `ASSET_CREATED`, `ASSET_UPDATED`, `ASSET_DELETED`, `ASSET_TAGGED`,
`TAG_CREATED`, `TAG_DELETED`, `TAG_UPDATED`,
`WEBHOOK_CREATED`, `WEBHOOK_DELETED`, `WEBHOOK_UPDATED`

#### `vector_config`
Named index definitions for Qdrant vector collections. Defines which asset attributes and weights form a custom vector for similarity search.

| Column    | Type      | Notes                             |
|-----------|-----------|-----------------------------------|
| `uuid`    | uuid (PK) |                                   |
| `name`    | varchar   | Unique                            |
| `weights` | jsonb     | Attribute weight configuration    |

#### `loom`
Single-row system state table.

| Column                | Type      | Notes              |
|-----------------------|-----------|--------------------|
| `db_rev`              | varchar   | Running DB revision|
| `last_used_timestamp` | timestamp |                    |

---

## 4. Asset Types

Loom represents the following media categories, identified primarily by MIME type:

| Category    | Representative MIME Types                                    | Notes                          |
|-------------|--------------------------------------------------------------|--------------------------------|
| **Image**   | `image/jpeg`, `image/png`, `image/tiff`, `image/webp`, `image/gif`, `image/svg+xml` | Raster and vector  |
| **Video**   | `video/mp4`, `video/avi`, `video/quicktime`, `video/x-matroska`, `video/webm` | Multi-track support |
| **Audio**   | `audio/mpeg`, `audio/flac`, `audio/wav`, `audio/ogg`, `audio/aac` | BPM, sampling rate stored |
| **Document**| `application/pdf`, `application/msword`, `application/vnd.openxmlformats-officedocument.*`, `text/plain`, `text/html` | Text extracted via Tika |
| **Archive** | `application/zip`, `application/x-tar`, `application/x-7z-compressed` | Hash/size only |

---

## 5. REST API

**Base path:** `/api/v1`  
**Authentication:** JWT bearer token (acquired via `POST /api/v1/login`)  
**Format:** JSON request/response bodies

All collection endpoints return paged results. Pagination parameters: `page`, `perPage`.

### 5.1 Authentication

| Method | Path              | Description                   |
|--------|-------------------|-------------------------------|
| POST   | `/api/v1/login`   | Authenticate; returns JWT     |

### 5.2 Users `/api/v1/users`

| Method | Path                     | Permission       | Description       |
|--------|--------------------------|------------------|-------------------|
| POST   | `/users`                 | `CREATE_USER`    | Create user        |
| GET    | `/users`                 | `READ_USER`      | List users (paged) |
| GET    | `/users/:uuid`           | `READ_USER`      | Get user by UUID   |
| POST   | `/users/:uuid`           | `UPDATE_USER`    | Update user        |
| DELETE | `/users/:uuid`           | `DELETE_USER`    | Delete user        |

### 5.3 Roles `/api/v1/roles`

| Method | Path                 | Permission    | Description        |
|--------|----------------------|---------------|--------------------|
| POST   | `/roles`             | `CREATE_ROLE` | Create role         |
| GET    | `/roles`             | `READ_ROLE`   | List roles          |
| GET    | `/roles/:uuid`       | `READ_ROLE`   | Get role by UUID    |
| POST   | `/roles/:uuid`       | `UPDATE_ROLE` | Update role         |
| DELETE | `/roles/:uuid`       | `DELETE_ROLE` | Delete role         |

### 5.4 Groups `/api/v1/groups`

| Method | Path                  | Permission     | Description        |
|--------|-----------------------|----------------|--------------------|
| POST   | `/groups`             | `CREATE_GROUP` | Create group        |
| GET    | `/groups`             | `READ_GROUP`   | List groups         |
| GET    | `/groups/:uuid`       | `READ_GROUP`   | Get group           |
| POST   | `/groups/:uuid`       | `UPDATE_GROUP` | Update group        |
| DELETE | `/groups/:uuid`       | `DELETE_GROUP` | Delete group        |

### 5.5 Assets `/api/v1/assets`

| Method | Path                                                   | Permission       | Description                          |
|--------|--------------------------------------------------------|------------------|--------------------------------------|
| POST   | `/assets`                                              | `CREATE_ASSET`   | Ingest/create asset                  |
| GET    | `/assets`                                              | `READ_ASSET`     | List assets (paged)                  |
| GET    | `/assets/:sha512orUUID`                                | `READ_ASSET`     | Get asset by SHA-512 or UUID         |
| POST   | `/assets/:sha512orUUID`                                | `UPDATE_ASSET`   | Update asset metadata                |
| DELETE | `/assets/:sha512orUUID`                                | `DELETE_ASSET`   | Delete asset                         |
| POST   | `/assets/:sha512orUUID/tags`                           | `TAG_ASSET`      | Add tag to asset                     |
| DELETE | `/assets/:sha512orUUID/tags/:tagUuid`                  | `UNTAG_ASSET`    | Remove tag from asset                |
| POST   | `/assets/:sha512orUUID/reactions`                      | `CREATE_REACTION`| Add reaction to asset                |
| GET    | `/assets/:sha512orUUID/reactions`                      | `READ_REACTION`  | List reactions on asset              |
| GET    | `/assets/:sha512orUUID/reactions/:reactionUuid`        | `READ_REACTION`  | Get specific reaction                |
| POST   | `/assets/:sha512orUUID/reactions/:reactionUuid`        | `UPDATE_REACTION`| Update reaction on asset             |
| DELETE | `/assets/:sha512orUUID/reactions/:reactionUuid`        | `DELETE_REACTION`| Delete reaction from asset           |

### 5.6 Asset Locations `/api/v1/locations`

| Method | Path                    | Permission              | Description            |
|--------|-------------------------|-------------------------|------------------------|
| POST   | `/locations`            | `CREATE_ASSET_LOCATION` | Register location       |
| GET    | `/locations`            | `READ_ASSET_LOCATION`   | List locations          |
| GET    | `/locations/:uuid`      | `READ_ASSET_LOCATION`   | Get location            |
| POST   | `/locations/:uuid`      | `UPDATE_ASSET_LOCATION` | Update location         |
| DELETE | `/locations/:uuid`      | `DELETE_ASSET_LOCATION` | Delete location         |

### 5.7 Libraries `/api/v1/libraries`

| Method | Path                    | Permission        | Description       |
|--------|-------------------------|-------------------|-------------------|
| POST   | `/libraries`            | `CREATE_LIBRARY`  | Create library     |
| GET    | `/libraries`            | `READ_LIBRARY`    | List libraries     |
| GET    | `/libraries/:uuid`      | `READ_LIBRARY`    | Get library        |
| POST   | `/libraries/:uuid`      | `UPDATE_LIBRARY`  | Update library     |
| DELETE | `/libraries/:uuid`      | `DELETE_LIBRARY`  | Delete library     |

### 5.8 Collections `/api/v1/collections`

| Method | Path                       | Permission           | Description             |
|--------|----------------------------|----------------------|-------------------------|
| POST   | `/collections`             | `CREATE_COLLECTION`  | Create collection        |
| GET    | `/collections`             | `READ_COLLECTION`    | List collections         |
| GET    | `/collections/:uuid`       | `READ_COLLECTION`    | Get collection           |
| POST   | `/collections/:uuid`       | `UPDATE_COLLECTION`  | Update collection        |
| DELETE | `/collections/:uuid`       | `DELETE_COLLECTION`  | Delete collection        |

### 5.9 Projects `/api/v1/projects`

| Method | Path                   | Permission        | Description    |
|--------|------------------------|-------------------|----------------|
| POST   | `/projects`            | `CREATE_PROJECT`  | Create project  |
| GET    | `/projects`            | `READ_PROJECT`    | List projects   |
| GET    | `/projects/:uuid`      | `READ_PROJECT`    | Get project     |
| POST   | `/projects/:uuid`      | `UPDATE_PROJECT`  | Update project  |
| DELETE | `/projects/:uuid`      | `DELETE_PROJECT`  | Delete project  |

### 5.10 Tags `/api/v1/tags`

| Method | Path               | Permission    | Description |
|--------|--------------------|---------------|-------------|
| POST   | `/tags`            | `CREATE_TAG`  | Create tag   |
| GET    | `/tags`            | `READ_TAG`    | List tags    |
| GET    | `/tags/:uuid`      | `READ_TAG`    | Get tag      |
| POST   | `/tags/:uuid`      | `UPDATE_TAG`  | Update tag   |
| DELETE | `/tags/:uuid`      | `DELETE_TAG`  | Delete tag   |

### 5.11 Embeddings `/api/v1/embeddings`

| Method | Path                                      | Permission          | Description                        |
|--------|-------------------------------------------|---------------------|------------------------------------|
| POST   | `/embeddings`                             | `CREATE_EMBEDDING`  | Create embedding                    |
| GET    | `/embeddings`                             | `READ_EMBEDDING`    | List embeddings                     |
| GET    | `/embeddings/:uuid`                       | `READ_EMBEDDING`    | Get embedding                       |
| POST   | `/embeddings/:uuid`                       | `UPDATE_EMBEDDING`  | Update embedding                    |
| DELETE | `/embeddings/:uuid`                       | `DELETE_EMBEDDING`  | Delete embedding                    |
| POST   | `/embeddings/:embeddingUuid/attachments`   | `CREATE_ATTACHMENT` | Add attachment to embedding         |
| GET    | `/embeddings/:embeddingUuid/attachments`   | `READ_ATTACHMENT`   | List attachments for embedding      |

### 5.12 Attachments `/api/v1/attachments`

| Method | Path                     | Permission           | Description        |
|--------|--------------------------|----------------------|--------------------|
| POST   | `/attachments`           | `CREATE_ATTACHMENT`  | Upload attachment   |
| GET    | `/attachments`           | `READ_ATTACHMENT`    | List attachments    |
| GET    | `/attachments/:uuid`     | `READ_ATTACHMENT`    | Get attachment      |
| POST   | `/attachments/:uuid`     | `UPDATE_ATTACHMENT`  | Update attachment   |
| DELETE | `/attachments/:uuid`     | `DELETE_ATTACHMENT`  | Delete attachment   |

### 5.13 Annotations `/api/v1/annotations`

| Method | Path                                                     | Permission           | Description                     |
|--------|----------------------------------------------------------|----------------------|---------------------------------|
| POST   | `/annotations`                                           | `CREATE_ANNOTATION`  | Create annotation                |
| GET    | `/annotations`                                           | `READ_ANNOTATION`    | List annotations                 |
| GET    | `/annotations/:uuid`                                     | `READ_ANNOTATION`    | Get annotation                   |
| POST   | `/annotations/:uuid`                                     | `UPDATE_ANNOTATION`  | Update annotation                |
| DELETE | `/annotations/:uuid`                                     | `DELETE_ANNOTATION`  | Delete annotation                |
| POST   | `/annotations/:annotationUuid/reactions`                 | `CREATE_REACTION`    | Add reaction to annotation       |
| GET    | `/annotations/:annotationUuid/reactions`                 | `READ_REACTION`      | List reactions on annotation     |
| GET    | `/annotations/:annotationUuid/reactions/:reactionUuid`   | `READ_REACTION`      | Get specific reaction            |
| POST   | `/annotations/:annotationUuid/reactions/:reactionUuid`   | `UPDATE_REACTION`    | Update reaction on annotation    |
| DELETE | `/annotations/:annotationUuid/reactions/:reactionUuid`   | `DELETE_REACTION`    | Delete reaction from annotation  |

### 5.14 Comments `/api/v1/comments`

| Method | Path                                              | Permission        | Description                    |
|--------|---------------------------------------------------|-------------------|--------------------------------|
| POST   | `/comments`                                       | `CREATE_COMMENT`  | Create comment                  |
| GET    | `/comments`                                       | `READ_COMMENT`    | List comments                   |
| GET    | `/comments/:uuid`                                 | `READ_COMMENT`    | Get comment                     |
| POST   | `/comments/:uuid`                                 | `UPDATE_COMMENT`  | Update comment                  |
| DELETE | `/comments/:uuid`                                 | `DELETE_COMMENT`  | Delete comment                  |
| POST   | `/comments/:commentUuid/reactions`                | `CREATE_REACTION` | Add reaction to comment         |
| GET    | `/comments/:commentUuid/reactions`                | `READ_REACTION`   | List reactions on comment       |
| GET    | `/comments/:commentUuid/reactions/:reactionUuid`  | `READ_REACTION`   | Get specific reaction           |
| POST   | `/comments/:commentUuid/reactions/:reactionUuid`  | `UPDATE_REACTION` | Update reaction on comment      |
| DELETE | `/comments/:commentUuid/reactions/:reactionUuid`  | `DELETE_REACTION` | Delete reaction on comment      |

### 5.15 Tasks `/api/v1/tasks`

| Method | Path                | Permission      | Description   |
|--------|---------------------|-----------------|---------------|
| POST   | `/tasks`            | `CREATE_TASK`   | Create task    |
| GET    | `/tasks`            | `READ_TASK`     | List tasks     |
| GET    | `/tasks/:uuid`      | `READ_TASK`     | Get task       |
| POST   | `/tasks/:uuid`      | `UPDATE_TASK`   | Update task    |
| DELETE | `/tasks/:uuid`      | `DELETE_TASK`   | Delete task    |

### 5.16 Webhooks `/api/v1/webhooks`

| Method | Path                   | Permission          | Description      |
|--------|------------------------|---------------------|------------------|
| POST   | `/webhooks`            | `CREATE_WEBHOOK`    | Register webhook  |
| GET    | `/webhooks`            | `READ_WEBHOOK`      | List webhooks     |
| GET    | `/webhooks/:uuid`      | `READ_WEBHOOK`      | Get webhook       |
| POST   | `/webhooks/:uuid`      | `UPDATE_WEBHOOK`    | Update webhook    |
| DELETE | `/webhooks/:uuid`      | `DELETE_WEBHOOK`    | Delete webhook    |

### 5.17 GraphQL `/api/v1/graphql`

GraphQL endpoint for flexible querying across all entities.

### 5.18 System Info `/api/v1`

| Method | Path       | Description                             |
|--------|------------|-----------------------------------------|
| GET    | `/api/v1`  | System and version information          |

---

## 6. Access Control

Loom uses a **Role-Based Access Control (RBAC)** model with optional **per-user overrides**:

1. Users are assigned to **Groups**
2. Groups are assigned **Roles**
3. Roles define **permissions** per **resource** (specific UUID or wildcard `*`)
4. API tokens can have independent permission grants

Every endpoint checks the required permission before processing.

Permission scope example:
```
role "editor" → permission CREATE_ASSET on resource "*"
role "viewer" → permission READ_ASSET on resource "*"
```

SSO users (SAML/OIDC) are automatically mapped to groups based on identity provider claims.

---

## 7. Services & Integrations

| Service         | Purpose                                                  |
|-----------------|----------------------------------------------------------|
| **Elasticsearch**| Full-text search across assets and metadata             |
| **Lucene**       | Local/embedded full-text search alternative             |
| **Qdrant**       | Vector similarity search using asset embeddings         |
| **Apache Tika**  | Document metadata extraction                            |
| **GraphQL**      | Flexible query API layer                                |
| **gRPC**         | High-performance binary RPC (planned)                   |
| **Webhook**      | Outbound HTTP callbacks for system events               |
| **Eventbus**     | Internal Vert.x event distribution between services     |
| **MCP**          | Model Context Protocol integration for AI agents        |
| **Monitoring**   | Prometheus metrics export                               |
| **Image service**| Server-side image transformation/resizing               |
| **Video service**| Video processing utilities                              |
| **Auth service** | JWT issuance and SSO integration                        |
| **Filesystem service** | Storage abstraction (local, S3)                  |
| **Plugin service**| Extension point for custom processors                  |

---

## 8. Storage Model

Loom separates **content** from **location**:

- An `asset` is uniquely identified by its SHA-512 hash — there is only one row per unique binary.
- `asset_location` rows point to where the same binary exists on disk or in S3.
- This enables deduplication: two files with the same content share a single `asset` row but have separate `asset_location` rows.
- Optional S3 fields (`s3_bucket_name`, `s3_object_path`) allow cloud object storage backends.
- inode-based file keys (`filekey_inode`, `filekey_stdev`, `filekey_edate`) provide stable filesystem identity tracking across renames.

---

## 9. Event System

Loom fires structured events internally via the Vert.x eventbus and outbound via webhooks for:

- User lifecycle (login, creation, update, delete, SSO mapping)
- Asset lifecycle (upload, creation, update, deletion, tagging)
- Tag management
- Webhook management changes

External consumers register webhooks via the REST API. Each webhook specifies a list of triggering `loom_events` values and receives a signed POST request to its configured URL.

---

## 10. Deployment

Loom is packaged as a self-contained Java JAR. It includes:
- CLI module for administrative operations
- Docker image for containerized deployment
- Helm chart for Kubernetes deployment

Database schema is managed by Flyway migrations (`V1` through `V2.18+`). The system supports both PostgreSQL and MariaDB.
