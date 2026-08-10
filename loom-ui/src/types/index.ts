// ── Domain Types ─────────────────────────────────────────────────────────────

export type AssetType = "video" | "image" | "audio" | "document" | "unknown";
export type AssetStatus = "processing" | "ready" | "failed" | "archived";
export type PipelineStatus = "idle" | "running" | "success" | "failed" | "paused" | "cancelled";

// Spaces
export interface Space {
  id: string;
  name: string;
  slug: string;
  description: string;
  color: string;
  assetCount: number;
  memberCount: number;
  createdAt: string;
  libraryIds: string[];
}

// Libraries
export interface Library {
  id: string;
  spaceId: string;
  name: string;
  description: string;
  assetCount: number;
  createdAt: string;
}

// Assets
export interface Asset {
  id: string;
  spaceId: string;
  libraryId: string;
  name: string;
  type: AssetType;
  status: AssetStatus;
  tags: string[];
  description: string;
  duration?: number; // seconds, for video/audio
  width?: number;
  height?: number;
  fileSize: number; // bytes
  mimeType: string;
  sha512?: string; // content hash, used as the key for bulk update
  thumbnailUrl: string;
  url: string;
  ownerId: string;
  collectionIds: string[];
  createdAt: string;
  updatedAt: string;
  metadata: Record<string, string | number | boolean>;
}

// Collections
export interface Collection {
  id: string;
  spaceId: string;
  name: string;
  description: string;
  assetIds: string[];
  ownerId: string;
  color: string;
  createdAt: string;
  updatedAt: string;
}

// Comments
export interface Comment {
  id: string;
  assetId: string;
  authorId: string;
  title?: string;
  text: string;
  timestampStart?: number; // seconds
  timestampEnd?: number;
  parentId?: string;
  createdAt: string;
  updatedAt: string;
}

// Annotations
export interface Annotation {
  id: string;
  assetId: string;
  authorId: string;
  type?: string;
  title: string;
  description: string;
  timestampStart?: number;
  timestampEnd?: number;
  region?: { x: number; y: number; width: number; height: number }; // normalized 0-1
  color: string;
  createdAt: string;
}

// Pipelines
export interface PipelineNode {
  id: string;
  type: string;
  label: string;
  description: string;
  position: { x: number; y: number };
  /**
   * Affinity group name. Nodes sharing an affinity are dispatched together as a
   * single pipeline segment by the engine. Optional; a missing/blank value means
   * the implicit "default" group. Persisted as a top-level field on each node.
   */
  affinity?: string;
  /**
   * Per-instance node options. This is the key Loom's `PipelineGraphParser` reads and hands to
   * the worker. Prefer `pipelineNodeOptions(node)` over reading this directly — older
   * definitions carry the same bag under `config` or `data`.
   */
  options?: Record<string, unknown>;
  /** @deprecated legacy alias for `options`, written by the editor before the options fix. */
  config?: Record<string, unknown>;
  /** @deprecated legacy in-editor bag; never persisted under this name by Loom. */
  data?: Record<string, unknown>;
}

/**
 * Resolve a pipeline node's per-instance options across the three shapes definitions have used.
 *
 * The editor once serialised parameters as `config` while the backend only ever read `options`,
 * so stored definitions carry either. `data` is the in-editor bag used before both.
 */
export function pipelineNodeOptions(node: PipelineNode): Record<string, unknown> {
  return node.options ?? node.config ?? node.data ?? {};
}

export type EdgeKind = "PASS" | "REJECT" | "ANY";

export interface PipelineEdge {
  id: string;
  source: string;
  target: string;
  /**
   * Id of the output port on the source node. Required by Loom — an edge without it is rejected,
   * and the editor uses it verbatim as the React Flow source handle id.
   */
  sourcePort?: string;
  /** Id of the input port on the target node. Required by Loom; the React Flow target handle id. */
  targetPort?: string;
  /**
   * Filter routing for this edge. The editor used to write this as `edgeType`, which neither
   * `PipelineGraphParser` nor `PipelineValidationService` ever read — so every UI-authored
   * PASS/REJECT edge reached the engine as `ANY`. `branch` is the field they read.
   */
  branch?: EdgeKind;
  label?: string;
  animated?: boolean;
}

export interface PipelineRun {
  id: string;
  pipelineId: string;
  startedAt: string;
  finishedAt?: string;
  status: PipelineStatus;
  processedAssets: number;
  errors: number;
  log: string[];
}

export interface Pipeline {
  id: string;
  /** UUID of the pipeline_version row this pipeline was rendered from. */
  versionUuid?: string;
  /** Sequential version number (1, 2, 3 …) of the currently loaded version. */
  versionNumber?: number;
  spaceId: string;
  name: string;
  description: string;
  enabled: boolean;
  priority: number;
  dryRun: boolean;
  definition: {
    nodes: PipelineNode[];
    edges: PipelineEdge[];
  };
  runs: PipelineRun[];
  createdAt: string;
  updatedAt: string;
}

// Users
export type UserRole = "admin" | "editor" | "viewer" | "operator";

export interface User {
  id: string;
  name: string;
  email: string;
  username: string;
  role: UserRole;
  groupIds: string[];
  avatarUrl?: string;
  active: boolean;
  createdAt: string;
  lastSeenAt: string;
}

// Groups
export interface Group {
  id: string;
  name: string;
  description: string;
  memberIds: string[];
  roleIds: string[];
  createdAt: string;
}

// Roles / RBAC
export interface Permission {
  id: string;
  resource: string;
  action: string;
  description: string;
}

export interface Role {
  id: string;
  name: string;
  description: string;
  permissionIds: string[];
  isSystem: boolean;
  createdAt: string;
}

// API Keys
export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  ownerId: string;
  scopes: string[];
  lastUsedAt?: string;
  expiresAt?: string;
  createdAt: string;
  active: boolean;
}

// Blacklist
export interface BlacklistEntry {
  id: string;
  type: "ip" | "user" | "domain" | "fingerprint";
  value: string;
  reason: string;
  addedBy: string;
  createdAt: string;
  expiresAt?: string;
}

// Chat
export type ChatMessageRole = "user" | "assistant" | "system";

export interface ChatReference {
  type: "asset" | "collection" | "task" | "pipeline" | "annotation";
  id: string;
  label: string;
}

export interface AgentAction {
  id: string;
  label: string;
  description: string;
  status: "pending" | "running" | "done" | "error";
  result?: string;
}

/** A tool invocation recorded on a persisted assistant message (CHAT.md §4.3). */
export interface ChatToolCall {
  id: string;
  name: string;
  args?: Record<string, unknown>;
  resultSummary?: string;
  isError?: boolean;
  durationMs?: number;
}

/** One node of a pipeline graph visual, as projected by the `get_pipeline` MCP tool (MCP.md §5.7). */
export interface PipelineGraphNode {
  id: string;
  kind?: string;
  label: string;
  /** Node category, used for colouring. Mirrors the `NodeCategory` of the node descriptors. */
  category?: string;
}

/** One port-to-port connection of a pipeline graph visual. */
export interface PipelineGraphEdge {
  source: string;
  sourcePort?: string;
  target: string;
  targetPort?: string;
  /** Only set on edges leaving a filter node: `ANY | PASS | REJECT`. */
  branch?: string;
}

/** Render payload of a `pipeline-graph` visual. */
export interface PipelineGraphPayload {
  pipelineUuid?: string;
  name?: string;
  description?: string;
  enabled?: boolean;
  versionNumber?: number;
  nodes: PipelineGraphNode[];
  edges: PipelineGraphEdge[];
  /** True when the tool clipped the graph because it exceeded the payload caps. */
  truncated?: boolean;
}

/**
 * A renderable payload attached to a tool result, drawn inline in the transcript instead of only
 * being described in text (CHAT.md §6.1). `type` discriminates the payload shape.
 */
export interface ChatVisual {
  type: "pipeline-graph" | string;
  /** Uuid of the entity depicted, so the card can link into the matching view. */
  id: string;
  label: string;
  payload: PipelineGraphPayload | Record<string, unknown>;
}

export interface ChatMessage {
  id: string;
  role: ChatMessageRole;
  content: string;
  createdAt: string;
  /** Model reasoning ("thinking") — hidden by default in the UI. */
  reasoning?: string;
  toolCalls?: ChatToolCall[];
  references?: ChatReference[];
  /** Inline visualizations produced by tool calls (e.g. a pipeline graph). */
  visuals?: ChatVisual[];
  actions?: AgentAction[];
  suggestedFollowUps?: string[];
}

// Transcript
export interface TranscriptWord {
  word: string;
  startTime: number;
  endTime: number;
  confidence: number;
}

export interface TranscriptSection {
  id: string;
  title: string;
  startTime: number;
  endTime: number;
  words: TranscriptWord[];
}

// Face Detection
export interface DetectedFace {
  id: string;
  assetId: string;
  timestamp?: number; // for video: the time of detection
  boundingBox: { x: number; y: number; width: number; height: number }; // normalized 0-1
  confidence: number;
  thumbnailUrl: string;
  clusterId?: string;
}

export interface FaceCluster {
  id: string;
  label: string;
  representativeThumbnailUrl: string;
  /**
   * Detection uuids of the faces in this cluster.
   *
   * Detections rather than embeddings, because a detection is what addresses a face crop. Empty
   * until the members are loaded — the list route reports only `faceCount`, so a page of cards costs
   * one request rather than one per card.
   */
  faceIds: string[];
  /** How many faces the server says are in this cluster, known before the members are loaded. */
  faceCount: number;
  /** The asset the cluster was computed within; needed to address its members' crops. */
  assetId?: string;
  /** Review verdict: PENDING, CONFIRMED or REJECTED. */
  reviewStatus?: string;
  /** When a human decided, or undefined while nobody has. Not the `edited` audit timestamp. */
  reviewedAt?: string;
  /** The user who decided, or undefined while nobody has. Not the `editor`, which the node overwrites. */
  reviewerUuid?: string;
  /** Cohesion, or undefined for a single-member cluster. */
  score?: number;
  personId?: string;
}

export interface Person {
  id: string;
  name: string;
  description: string;
  avatarUrl: string;
  clusterIds: string[];
  createdAt: string;
}

// Object Detection
export interface DetectedObject {
  id: string;
  assetId: string;
  label: string;
  confidence: number;
  boundingBox: { x: number; y: number; width: number; height: number }; // normalized 0-1
  timestamp?: number;
}

// Asset Pools
export type AssetPoolType = "filesystem" | "s3";

export interface AssetPool {
  id: string;
  name: string;
  type: AssetPoolType;
  fsPath?: string;
  s3Bucket?: string;
  s3Region?: string;
  s3Endpoint?: string;
  freeSpace?: number; // bytes
  usedSpace?: number; // bytes
  assetCount: number;
  totalSize: number; // bytes
  createdAt: string;
  updatedAt: string;
}

// Search
//
// Shared vocabulary only. The wire response interfaces (SearchHitResponse, SearchMetaInfo, …)
// live in `api/search.ts` beside their module, like every other client's response types.

/**
 * Kind of thing a search hit points at.
 *
 * The wire form is lowercase — it is the value stored in `search_document.entity_type` and the
 * server filters on the raw string. Do not uppercase it.
 */
export type SearchEntityType =
  | "asset"
  | "transcript"
  | "tag"
  | "annotation"
  | "person"
  | "collection"
  | "library"
  | "detection"
  | "segment"
  | "cluster";

/** Every type the API accepts as `types=` input. */
export const SEARCH_ENTITY_TYPES: readonly SearchEntityType[] = [
  "asset", "transcript", "tag", "annotation", "person",
  "collection", "library", "detection", "segment", "cluster",
];

/**
 * Types the indexer never writes a `search_document` row for.
 *
 * They are valid input and are permission-narrowed like the rest, but they can never produce a
 * hit: detection labels and segment titles are folded into the owning asset's keywords instead.
 * Offering them as a filter would offer a guaranteed-empty result.
 */
export const UNINDEXED_SEARCH_ENTITY_TYPES: readonly SearchEntityType[] = ["detection", "segment"];

/** The types a filter row may offer — everything that can actually be returned as a hit. */
export const SEARCHABLE_ENTITY_TYPES: readonly SearchEntityType[] =
  SEARCH_ENTITY_TYPES.filter((type) => !UNINDEXED_SEARCH_ENTITY_TYPES.includes(type));

export type SearchMode = "LEXICAL" | "SEMANTIC" | "HYBRID";

export type SearchSortMode = "RELEVANCE" | "NEWEST" | "OLDEST" | "NAME" | "SIZE";

/**
 * What the bound provider can do, as advertised by `GET /search/status`.
 *
 * The UI hides controls the provider does not support rather than issuing requests that will be
 * rejected — the server answers 400 for an unsupported mode instead of silently degrading, and
 * that rejection must never reach the user.
 */
export type SearchCapability =
  | "LEXICAL"
  | "PHRASE"
  | "FUZZY"
  | "HIGHLIGHT"
  | "FACETS"
  | "EXACT_TOTAL"
  | "DEEP_PAGING"
  | "SEMANTIC"
  | "HYBRID"
  | "SUGGEST";

/** The only facet names the provider computes; anything else is silently dropped. */
export type SearchFacetName = "mime_type" | "entity_type" | "lang";

export const SEARCH_FACET_NAMES: readonly SearchFacetName[] = ["mime_type", "entity_type", "lang"];

// Server-side caps, mirrored here so the UI can refuse before the server answers 400.
/** `SearchRequest.MAX_QUERY_LENGTH` — a longer term is a 400. */
export const SEARCH_MAX_QUERY_LENGTH = 512;
/** `LOOM_SEARCH_MAX_OFFSET` — paging past this is a 400, not an empty page. */
export const SEARCH_MAX_OFFSET = 1000;
/** `SearchQueryParameterKey.LIMIT` default. Also the pager's step. */
export const SEARCH_PAGE_SIZE = 25;
