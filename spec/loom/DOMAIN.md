# Metaloom Domain Model

A compact overview of the domain entities of Metaloom (Loom), derived from the Flyway
migrations (`loom/db/flyway/.../db/migration`) and the REST endpoints (`loom/.../rest`).

Every core entity carries a `uuid` primary key and an audit trail (`created`,
`creator_uuid`, `edited`, `editor_uuid`); most carry a free-form `meta` JSONB blob.
These common columns are omitted from the table below.

## Domain Groups

| # | Domain group | Entities |
|---|--------------|----------|
| 1 | Identity & Access (RBAC) | User, Group, Role, Permission, Token |
| 2 | Assets & Media | Asset, Asset Location, Asset Pool, Asset Component, Asset Remix, Attachment, Blacklist, Annotation |
| 3 | Organization | Collection, Library, Space, Tag |
| 4 | AI / ML | Embedding, Cluster, Detection, Person, Vector Config, Chat |
| 5 | Pipeline / Processing (Cortex) | Pipeline, Pipeline Version, Pipeline Run, Run Item, Node Task, Cortex Instance |
| 6 | Collaboration / Social | Task, Comment, Reaction |
| 7 | System | Webhook, Loom |

## Entities

### 1. Identity & Access (RBAC)

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **User** | `user` | Account (login, SSO, password hash, enabled/deleted flags). Creator/editor of nearly everything. | ↔ `user_group`, `user_permission` |
| **Group** | `group` | Collection of users; carries roles. | `user_group`, `role_group` |
| **Role** | `role`, `role_permission` | Named bundle of permissions on resources. | `role_group` → Group |
| **Permission** | `loom_permission` (enum) | CRUD-style grants per resource type (`CREATE_ASSET`, `READ_ROLE`, `MANAGE_CORTEX_INSTANCE`, …). Bound to role/user/token. | `role_permission`, `user_permission`, `token_permission` |
| **Token** | `token`, `token_permission` | API key with its own permission set (scoped machine access). | → User (creator) |

### 2. Assets & Media

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **Asset** | `asset` | Content-addressed media (PK `sha512sum`): mime, size, filename, hashes, origin. The central media entity. | ← Location, Component, Embedding, Detection, Annotation |
| **Asset Location** | `asset_location` | Physical placement of an asset's binary (path, filekey, pool, lock, state, license). One binary per asset. | → Asset, Library, Asset Pool |
| **Asset Pool** | `asset_pool` | Storage backend for binaries — filesystem dir *or* S3 bucket (free/used space tracked). | ← Asset Location |
| **Asset Component** | `asset_geo_comp`, `asset_doc_comp`, `asset_image_comp`, `asset_video_comp`, `asset_audio_comp`, `asset_transcript_comp`, `asset_json_comp` | Per-modality extracted metadata, multiple per asset, tagged by `source`. Transcript + generic JSON produced by Cortex nodes. | → Asset |
| **Asset Remix** | `asset_remix` | Derivation/relation link between two assets. | Asset ↔ Asset |
| **Attachment** | `attachment`, `attachment_binary` | Auxiliary binaries — asset thumbnails, embedding attachments. | → Asset / Embedding |
| **Blacklist** | `blacklist` | Blocked assets (copyright, virus scan), with review count. | → Asset |
| **Annotation** | `annotation`, `annotation_asset`, `annotation_tag`, `annotation_task` | Time-/area-scoped markers on an asset: FEEDBACK, TAG, CHAPTER. | → Asset, Tag, Task |

### 3. Organization

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **Collection** | `collection`, `collection_asset`, `collection_cluster`, `tag_collection` | Hierarchical folder grouping assets & clusters. | self-parent; ↔ Asset, Cluster, Tag |
| **Library** | `library`, `library_asset`, `library_collection` | Top-level container of assets and collections (scanner root). | ↔ Asset, Collection |
| **Space** | `project`, `project_library`, `project_collection` | Workspace grouping libraries + collections (DB table `project`, exposed as *Space*). | ↔ Library, Collection |
| **Tag** | `tag`, `tag_user_meta`, `tag_asset`, `tag_cluster`, `tag_collection` | Named label (with collection namespace, color, rating). Not user-specific; per-user rating in `tag_user_meta`. | ↔ Asset, Cluster, Collection |

### 4. AI / ML

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **Embedding** | `embedding`, `embedding_cluster` | Vector extracted from an asset (with time/area scope + type, e.g. `dlib_facemark`). | → Asset; ↔ Cluster |
| **Cluster** | `cluster`, `embedding_cluster`, `tag_cluster` | Group of embeddings by similarity (e.g. a person, a visual-fingerprint group). | ← Embedding; ↔ Tag, Collection |
| **Detection** | `detection` | Object/face bounding box within an asset frame (type, bbox, confidence). | → Asset |
| **Person** | `person`, `person_image` | Named identity with a gallery of images and a primary image. | → Asset (images) |
| **Vector Config** | `vector_config` | Named weight definition for building custom vector indices. | — |
| **Chat** | `chat` | LLM chat session with JSON message history (may reference assets). | → User |

### 5. Pipeline / Processing (Cortex)

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **Pipeline** | `pipeline` | Processing graph definition (JSONB), enabled/priority/dry-run. | → latest Pipeline Version |
| **Pipeline Version** | `pipeline_version` | Immutable versioned snapshot of a pipeline definition. | → Pipeline |
| **Pipeline Run** | `pipeline_run` | One execution of a pipeline: status + success/failure/skipped counts. | → Pipeline |
| **Run Item** | `pipeline_run_item` | One media item discovered by a run's source node (path, hash, state). | → Run |
| **Node Task** | `pipeline_node_task` | One node executed against one item — leased, retried, dead-lettered. | → Run Item, Run |
| **Cortex Instance** | `cortex_instance`, `cortex_instance_node_kind` | Registered processor/worker (node_id, host, priority, state) with node-kind whitelist/blacklist. | — |

### 6. Collaboration / Social

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **Task** | `task`, `asset_task`, `annotation_task` | Workflow item (title, status PENDING/REJECTED/ACCEPTED/REVIEW, priority, due date). | ↔ Asset, Annotation |
| **Comment** | `comment` | Threaded comment on a task, asset, or annotation. | self-parent; → Task/Asset/Annotation |
| **Reaction** | `reaction` | Social reaction/rating (e.g. thumbsup) on asset, task, comment, annotation. | → Asset/Task/Comment/Annotation |

### 7. System

| Entity | Table(s) | Description | Key relations |
|--------|----------|-------------|---------------|
| **Webhook** | `webhook` | Outbound HTTP hook fired on `loom_events` (user/group/role/asset/tag/webhook lifecycle), with secret token. | — |
| **Loom** | `loom` | Singleton system row: DB revision + last-used timestamp. | — |
