# Loom DAO Specification

## Overview

Loom uses a DAO (Data Access Object) pattern backed by jOOQ against PostgreSQL. All DAOs extend `AbstractJooqDao<T>` providing common CRUD with pagination, sorting, and filtering. Every entity uses UUID primary keys, JSONB `meta` fields for extensible metadata, and audit columns (`created`, `creator_uuid`, `edited`, `editor_uuid`).

An in-memory DAO implementation (`AbstractMemDao`, HashMap-backed) exists for testing.

## Entity Hierarchy

```
Project
  └── Library
        └── AssetLocation → Asset
              ├── AssetComponent (Geo, Doc, Image, Video, Audio, Transcript)
              ├── Annotation ←→ Task ←→ Comment
              ├── Tag (M:N via tag_asset)
              ├── Collection (M:N via collection_asset)
              ├── Embedding ←→ Cluster
              ├── Reaction
              ├── Attachment
              └── Blacklist

User ←→ Group ←→ Role ←→ Permission
Token → Permission
Webhook (event-driven callbacks)
```

---

## DAOs

### 1. ProjectDao

**Purpose:** Top-level organizational container grouping Libraries and Collections into a work context.

**User-Facing Usage:** Users create Projects to represent distinct initiatives (e.g. "Q1 Marketing Campaign", "Product Photography", "Archive Digitization"). A Project scopes access control and groups related Libraries and Collections together. It serves as the primary navigation entry point for teams working on a shared effort.

**Key Operations:** `createProject()`, CRUD, pagination

---

### 2. LibraryDao

**Purpose:** Represents a logical storage domain or mount point containing asset locations.

**User-Facing Usage:** Users create a Library for each distinct storage source (e.g. "Network SAN", "Cloud Archive S3 Bucket", "Mobile Uploads"). Assets are ingested into a Library via AssetLocations. This enables cross-library deduplication — the same file found on two drives is recognized as one Asset with two locations. Libraries live within Projects.

**Key Operations:** `createLibrary()`, CRUD, pagination

---

### 3. AssetDao

**Purpose:** Core entity representing a unique binary media file, identified by content hash (SHA-512).

**User-Facing Usage:** An Asset is the central object in the DAM. When a user ingests a file, Loom computes its SHA-512 hash and either creates a new Asset or links to an existing one (deduplication). Users browse, search, tag, annotate, and organize Assets. Each Asset stores its MIME type, filename, file size, S3 references, and optional hashes (SHA-256, MD5, chunk hashes for similarity detection).

**Key Operations:** `createAsset()` (with SHA-512 dedup), `loadById()` (UUID or hash lookup), CRUD, pagination

---

### 4. AssetLocationDao

**Purpose:** Tracks where an Asset physically lives on disk or object storage.

**User-Facing Usage:** A single Asset can exist in multiple filesystem paths or S3 keys across different Libraries. When users ingest from a new drive or bucket, Loom registers an AssetLocation linking Asset → Library → Path. File-level locking prevents concurrent processing. Inode-based tracking (`filekey_inode`, `filekey_stdev`) detects file renames and moves automatically.

**Key Operations:** `createAssetLocation()`, CRUD, inode key queries, processing lock management

---

### 5. AssetComponentDao

**Purpose:** Manages six specialized metadata tables for media-type-specific attributes extracted from assets.

**User-Facing Usage:** Rather than dumping all metadata into a single blob, Loom extracts and normalizes it by type so users can filter and search precisely:

| Component | Stored Attributes | User Example |
|-----------|-------------------|--------------|
| **GeoComponent** | Latitude, longitude (from EXIF/GPS) | "Find all assets photographed in Berlin" |
| **ImageComponent** | Dominant color, pixel dimensions, encoding | "Find landscape-oriented 4K photos" |
| **VideoComponent** | Frame dimensions, duration, bitrate, codec | "List all videos longer than 5 minutes" |
| **AudioComponent** | BPM, sample rate, channels, bitrate, encoding | "Filter by 48kHz stereo audio" |
| **DocComponent** | Extracted plain text, word count | "Search documents containing 'contract'" |
| **TranscriptComponent** | Speech-to-text output, timestamps, language, confidence | "Find videos where someone says 'deadline'" |

Components support source-based multiples (e.g. face detection results from different ML models), temporal anchors (video segment ranges), and bounding boxes for spatial annotations.

**Key Operations:** Per-component CRUD linked to an Asset UUID

---

### 6. CollectionDao

**Purpose:** Named, curated virtual grouping of assets.

**User-Facing Usage:** Users create Collections within Libraries to organize assets without moving files (e.g. "Approved Photos", "Draft Edits", "Archived"). An Asset can belong to many Collections simultaneously (M:N relationship). Collections support hierarchical nesting (parent/child), can be tagged, and can be linked to Clusters for ML-driven visual similarity groupings. Permissions can be scoped per Collection.

**Key Operations:** `createCollection()`, `linkAsset()`, `unlinkAsset()`, CRUD, pagination

---

### 7. TagDao

**Purpose:** Dynamic, user-defined labels organized by namespace ("collection").

**User-Facing Usage:** Users create Tags within namespaces (e.g. `Genre: Documentary`, `Format: 4K`) and apply them to Assets. Tags support color coding for visual organization and per-user ratings stored in `tag_user_meta`, allowing personal ranking (e.g. "my top picks"). Tags can be created on-the-fly during tagging workflows and optionally scoped to spatial regions (bounding box) or temporal ranges (video segment).

**Key Operations:** `createTag()`, `createAssetTag()` / `tagAsset()` (M:N linking), rating management, CRUD

---

### 8. AnnotationDao

**Purpose:** Time-coded and/or area-coded markup on assets for review and feedback.

**User-Facing Usage:** Reviewers and editors create Annotations on assets to mark specific moments or regions:

- **Types:** `FEEDBACK` (review note), `TAG` (labeling), `CHAPTER` (video chapter marker)
- **Temporal scope:** `time_from` / `time_to` in milliseconds (e.g. "blurry from 1:23–1:25")
- **Spatial scope:** bounding box coordinates (`areaStartX/Y`, `areaWidth/Height`)
- **Thumbnail:** reference image for visual context

Annotations can be escalated into Tasks and support comment threads and reactions.

**Key Operations:** `createAnnotation()`, `loadForAsset()`, CRUD, pagination

---

### 9. TaskDao

**Purpose:** Workflow work items for asset processing, review, or correction.

**User-Facing Usage:** Teams create Tasks to track actionable work (e.g. "Color correct this video", "Get legal approval"). Tasks follow a status workflow: `PENDING` → `REVIEW` → `ACCEPTED` or `REJECTED`. Each task has a title, description, priority level, and due date. Tasks are often created from Annotations — converting feedback into trackable work. Comment threads enable discussion and review on each task.

**Key Operations:** `createTask()`, `loadForAnnotation()`, status transitions, CRUD, pagination

---

### 10. CommentDao

**Purpose:** Threaded discussion on tasks, assets, and annotations.

**User-Facing Usage:** Users post Comments for real-time collaboration without creating formal tasks. Comments can target Tasks (status updates, revision requests), Assets (general feedback), or Annotations (expanding on specific feedback). Threading is supported via `parent_uuid` for reply chains. Comments can receive Reactions.

**Key Operations:** `createComment()`, `loadForTask()`, CRUD, pagination

---

### 11. EmbeddingDao

**Purpose:** Stores vector representations of assets or asset regions for ML-powered similarity search.

**User-Facing Usage:** Cortex or other ML pipelines extract dense numerical vectors (e.g. 512-dimensional CLIP embeddings, face embeddings, perceptual hashes) and store them per asset. Users leverage these for:

- **"Find similar"** queries via vector similarity search (backed by Qdrant)
- **Face recognition** — linking face embeddings to person Clusters
- **Automated clustering** of visually similar content

Each embedding records its model type/source, and can be scoped to a temporal range (video frame) or spatial region (bounding box / face crop).

**Key Operations:** `createEmbedding()`, CRUD, pagination

---

### 12. ClusterDao

**Purpose:** Named grouping of embeddings representing a conceptual entity (person, visual style, object class).

**User-Facing Usage:** ML pipelines cluster similar embeddings together. Instead of seeing 100 anonymous face detections, users see named Clusters like "John Smith" containing all face embeddings of that person across all videos. Users can then "Find all assets containing John." Clusters can be linked to Tags and Collections for cross-referencing.

**Key Operations:** `createCluster()`, `link()` (associate embeddings), CRUD, pagination

---

### 13. UserDao

**Purpose:** User accounts for authentication, authorization, and audit trails.

**User-Facing Usage:** Each person accessing Loom has a User account with a unique username, name, email, and password hash (bcrypt) or SSO flag. Admins can enable/disable and soft-delete users. All database mutations record the acting user's UUID for audit trails. Users belong to Groups which inherit Roles and Permissions.

**Key Operations:** `createUser()`, `loadByUsername()`, soft-delete handling, CRUD, pagination

---

### 14. GroupDao

**Purpose:** Named collections of users for simplified permission management.

**User-Facing Usage:** Instead of assigning permissions to each user individually, admins create Groups (e.g. "Video Team", "Archivists", "QA Reviewers") and assign Roles to those Groups. Users added to a Group inherit all associated permissions. This simplifies onboarding and permission changes at scale.

**Key Operations:** `loadByName()`, user membership (M:N via `user_group`), role assignment (M:N via `role_group`), CRUD

---

### 15. RoleDao

**Purpose:** Named bundles of permissions for role-based access control.

**User-Facing Usage:** Admins define Roles like "Asset Viewer" (read-only), "Asset Editor" (create/update), or "Admin" (full access). Each Role contains a set of Permissions. Roles are assigned to Groups — users in those Groups inherit the role's permissions. Supports resource-scoped permissions (e.g. "Editor role on Project X only").

**Key Operations:** `createRole()`, `loadByName()`, CRUD, pagination

---

### 16. PermissionDao

**Purpose:** Fine-grained RBAC permission grants for 50+ resource operations.

**User-Facing Usage:** Loom implements fine-grained access control with separate CREATE, READ, UPDATE, DELETE permissions per entity type (e.g. `CREATE_ASSET`, `READ_ASSET`, `TAG_ASSET`, `UNTAG_ASSET`). Permissions can be:

- Granted to **Roles** (most common via `grantRolePermission()`)
- Granted directly to **Users** (`grantUserPermission()`)
- Granted to **Tokens** (for API access scoping)
- Scoped to a specific resource UUID or wildcard `*` (any resource)

**Key Operations:** `loadPermissionsForUser()`, `grantRolePermission()`, `grantUserPermission()`, resource-scoped grants

---

### 17. TokenDao

**Purpose:** API access tokens for programmatic authentication without sharing passwords.

**User-Facing Usage:** Users create named API Tokens for CI/CD pipelines, third-party integrations, or mobile apps. Each token carries its own set of Permissions (which can be more restrictive than the user's full permissions). Tokens are included in HTTP headers (`Authorization: Bearer <token>`) and can be revoked independently without affecting the user account.

**Key Operations:** `createToken()`, CRUD, permission assignment

---

### 18. ReactionDao

**Purpose:** Lightweight user feedback (emojis, ratings) on assets, tasks, comments, and annotations.

**User-Facing Usage:** Users react to content with emojis (e.g. thumbsup, heart) or numeric ratings (-1 to 5) as a quick way to express approval or flag issues without writing full comments. Reactions are aggregatable ("20 people liked this") and enforce per-user uniqueness per target to prevent duplicate voting.

**Key Operations:** `createReaction()`, `loadPageForAsset()`, CRUD, pagination

---

### 19. AttachmentDao

**Purpose:** Auxiliary binary files attached to assets or embeddings (thumbnails, face crops).

**User-Facing Usage:** Thumbnails, face crops, and other derived files are stored as Attachments. They are content-addressed (SHA-512 dedup via `attachment_binary` table) and typed (`ASSET_THUMBNAIL`, `EMBEDDING_ATTACHMENT`). Users see thumbnails in browse views; face crops appear in embedding/cluster UIs.

**Key Operations:** `load()` (with LEFT JOIN to `attachment_binary`), CRUD

---

### 20. BlacklistDao

**Purpose:** Asset exclusion for content moderation and legal compliance.

**User-Facing Usage:** Assets can be blacklisted due to copyright claims, virus detection, DMCA takedowns, or policy violations. Blacklisted assets are excluded from search results and cannot be modified or reprocessed. Each entry records the reason type, review count (for tracking review cycles), and metadata for audit trails. Entries are removed via a review/appeal process.

**Key Operations:** `createBlacklist()`, CRUD, pagination

---

### 21. WebhookDao

**Purpose:** Outbound HTTP callbacks notifying external systems of Loom events.

**User-Facing Usage:** Users and admins register Webhook endpoints that receive HTTP POST notifications when events occur in Loom (e.g. `ASSET_UPLOADED`, `ASSET_TAGGED`, `TASK_CREATED`, `USER_CREATED`). Each webhook specifies a target URL, triggering events, and an HMAC secret for signature verification. Webhooks enable integration with analytics platforms, CMS systems, encoding pipelines, and other external services without polling.

**Key Operations:** CRUD, event subscription management, delivery status tracking

---

## Cross-Cutting Concerns

| Concern | Implementation |
|---------|---------------|
| **Primary Keys** | UUID v4 (via PostgreSQL `uuid-ossp`) |
| **Audit Trail** | `created`, `creator_uuid`, `edited`, `editor_uuid` on all entities |
| **Extensible Metadata** | JSONB `meta` column on most entities |
| **Soft Deletes** | Supported on User (via `deleted` flag); other entities use hard delete |
| **Pagination** | All list DAOs support `loadPage()` with filtering and sorting |
| **Dependency Injection** | `@Inject` / `@Singleton` via Dagger |
| **Database Migrations** | Flyway (V1 through V2.18+) |
| **Permission Model** | 50+ fine-grained permissions, scoped per resource or wildcard |
