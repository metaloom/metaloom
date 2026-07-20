// ── Domain Types ─────────────────────────────────────────────────────────────

export type AssetType = "video" | "image" | "audio" | "document" | "unknown";
export type AssetStatus = "processing" | "ready" | "failed" | "archived";
export type TaskStatus = "open" | "in_progress" | "review" | "done" | "blocked";
export type TaskPriority = "low" | "medium" | "high" | "critical";
export type PipelineStatus = "idle" | "running" | "success" | "failed" | "paused" | "cancelled";
export type ReactionType = "approve" | "flag" | "reject" | "favorite" | "question";

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
  thumbnailUrl: string;
  url: string;
  ownerId: string;
  collectionIds: string[];
  taskIds: string[];
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

// Tasks
export interface Task {
  id: string;
  spaceId: string;
  title: string;
  description: string;
  status: TaskStatus;
  priority: TaskPriority;
  assigneeId: string;
  assetId?: string;
  collectionId?: string;
  dueDate?: string;
  createdAt: string;
  updatedAt: string;
  tags: string[];
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
  title: string;
  description: string;
  timestampStart?: number;
  timestampEnd?: number;
  region?: { x: number; y: number; width: number; height: number }; // normalized 0-1
  color: string;
  createdAt: string;
}

// Reactions
export interface Reaction {
  id: string;
  assetId: string;
  userId: string;
  type: ReactionType;
  rating?: number; // 1-5
  timestamp?: number; // video position
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
  data: Record<string, unknown>;
}

export type EdgeKind = "PASS" | "REJECT" | "ANY";

export interface PipelineEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  animated?: boolean;
  edgeType?: EdgeKind;
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

// Metrics
export interface MetricPoint {
  ts: string;
  value: number;
}

export interface MetricSeries {
  label: string;
  color: string;
  data: MetricPoint[];
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

export interface ChatMessage {
  id: string;
  role: ChatMessageRole;
  content: string;
  createdAt: string;
  references?: ChatReference[];
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
  faceIds: string[];
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
