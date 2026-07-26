import { API_BASE_URL } from "./config";
import { AnnotationResponseItem, AreaInfo } from "./annotations";

// ── Types matching the Loom REST API response models ──────────────────

export interface FileInfo {
  mimeType: string;
  filename: string;
  size: number;
  origin: string;
  firstSeen: string;
}

export interface HashInfo {
  sha512: string;
  sha256?: string;
  md5?: string;
}

export interface TagReference {
  uuid: string;
  name: string;
  collection: string;
  color?: string;
  /** Spatial/temporal region of the asset this tag references (region tags only). */
  area?: AreaInfo;
}

export interface CollectionRef {
  uuid: string;
  name: string;
}

export interface GeoLocationInfo {
  lat?: number;
  lon?: number;
  alias?: string;
}

export interface ImageInfo {
  width?: number;
  height?: number;
  dominantColor?: string;
}

export interface VideoInfo {
  width?: number;
  height?: number;
  duration?: number;
  bitrate?: number;
  encoding?: string;
}

export interface AudioInfo {
  duration?: number;
  channels?: number;
  samplingRate?: number;
  bps?: number;
  encoding?: string;
}

export interface DocumentInfo {
  wordCount?: number;
  pageCount?: number;
}

export interface CreatorEditorRef {
  uuid: string;
  username?: string;
}

export interface CreatorEditorStatus {
  creator?: CreatorEditorRef;
  editor?: CreatorEditorRef;
  created?: string;
  edited?: string;
}

/** Linux filesystem key identifying a file (see FileKey.java). */
export interface FileKeyInfo {
  inode?: number;
  stDev?: number;
  edate?: number;
  edateNano?: number;
}

/** Filesystem placement of a location (see AssetLocationFilesystemInfo.java). */
export interface AssetLocationFilesystemInfo {
  path?: string;
  filekey?: FileKeyInfo;
  lastSeen?: string;
}

/** S3 placement of a location (see AssetS3Meta.java). */
export interface AssetS3Meta {
  bucket?: string;
  objectPath?: string;
}

/**
 * A storage location of the asset binary, embedded in the asset response.
 * Matches AssetLocationResponse built by AssetLocationModelBuilder.java.
 */
export interface AssetLocationInfo {
  uuid: string;
  status?: CreatorEditorStatus;
  meta?: Record<string, unknown>;
  assetUuid?: string;
  libraryUuid?: string;
  /** Reference to the storage pool holding the binary; links to the Asset Pools view. */
  poolUuid?: string;
  mimeType?: string;
  /** Current state of the location (e.g. "PRESENT", "MISSING"). */
  state?: string;
  license?: string;
  /** Uuid of the user currently holding a lock on this location. */
  lockedByUuid?: string;
  filesystem?: AssetLocationFilesystemInfo;
  s3?: AssetS3Meta;
}

export interface AssetResponse {
  uuid: string;
  status?: CreatorEditorStatus;
  meta?: Record<string, unknown>;
  file?: FileInfo;
  hashes?: HashInfo;
  geo?: GeoLocationInfo;
  tags?: TagReference[];
  annotations?: AnnotationResponseItem[];
  collections?: CollectionRef[];
  imageComponents?: ImageInfo[];
  videoComponents?: VideoInfo[];
  audioComponents?: AudioInfo[];
  documentComponents?: DocumentInfo[];
  locations?: AssetLocationInfo[];
  embeddings?: unknown[];
  fingerprint?: unknown;
  social?: unknown;
}

export interface PagingInfo {
  totalCount?: number;
  currentPage?: number;
  pageCount?: number;
  perPage?: number;
}

export interface AssetListResponse {
  data: AssetResponse[];
  _metainfo?: PagingInfo;
}

export interface AssetCreateRequest {
  file?: {
    mimeType?: string;
    filename?: string;
    size?: number;
    origin?: string;
  };
  // NOTE: the backend field is `hashes` (plural). `sha512` is required for a create to succeed.
  hashes?: {
    sha512?: string;
  };
  tags?: { name: string; collection?: string }[];
  meta?: Record<string, unknown>;
}

export interface AssetUpdateRequest {
  filename?: string;
  meta?: Record<string, unknown>;
}

// ── Bulk request/response models (POST /assets/bulk/create|update) ─────

export interface AssetBulkCreateRequest {
  assets: AssetCreateRequest[];
}

export interface AssetBulkUpdateEntry {
  hashes: { sha512: string };
  update: AssetUpdateRequest;
}

export interface AssetBulkUpdateRequest {
  assets: AssetBulkUpdateEntry[];
}

export interface AssetBulkItemResponse {
  index: number;
  uuid?: string;
  sha512?: string;
  status: "CREATED" | "UPDATED" | "FAILED";
  error?: string;
}

export interface AssetBulkResponse {
  items: AssetBulkItemResponse[];
  total: number;
  created: number;
  failed: number;
}

// ── Helpers ───────────────────────────────────────────────────────────

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

/**
 * URL of the raw bytes stored for an asset.
 *
 * Used as an `<img src>`, which cannot carry an `Authorization` header — the request
 * authenticates with the HttpOnly JWT cookie the login endpoint sets, so this only
 * resolves for a same-origin API base (which is how the UI is served).
 */
export function assetBinaryUrl(uuid: string): string {
  return `${API_BASE_URL}/assets/${encodeURIComponent(uuid)}/binary/data`;
}

// ── CRUD API ──────────────────────────────────────────────────────────

export async function listAssets(token: string): Promise<AssetListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<AssetListResponse>(res);
}

export async function loadAsset(
  token: string,
  uuid: string
): Promise<AssetResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<AssetResponse>(res);
}

export async function createAsset(
  token: string,
  request: AssetCreateRequest
): Promise<AssetResponse> {
  const res = await fetch(`${API_BASE_URL}/assets`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<AssetResponse>(res);
}

export async function updateAsset(
  token: string,
  uuid: string,
  request: AssetUpdateRequest
): Promise<AssetResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<AssetResponse>(res);
}

export async function deleteAsset(
  token: string,
  uuid: string
): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Delete failed ${res.status}: ${text}`);
  }
}

// ── Upload API (multipart) ────────────────────────────────────────────

/**
 * Upload a real file to create an asset. The backend persists the bytes, creates
 * the asset from the derived file metadata and sha512, and dispatches an event so
 * a matching pipeline can process it.
 *
 * Note: no `Content-Type` header is set — the browser sets the multipart boundary.
 */
export async function uploadAsset(
  token: string,
  file: File,
  libraryUuid: string,
  opts?: { origin?: string }
): Promise<AssetResponse> {
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("libraryUuid", libraryUuid);
  if (opts?.origin) {
    form.append("origin", opts.origin);
  }
  const res = await fetch(`${API_BASE_URL}/assets/upload`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  return handleResponse<AssetResponse>(res);
}

// ── Bulk API ──────────────────────────────────────────────────────────

export async function bulkCreateAssets(
  token: string,
  request: AssetBulkCreateRequest
): Promise<AssetBulkResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/bulk/create`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<AssetBulkResponse>(res);
}

export async function bulkUpdateAssets(
  token: string,
  request: AssetBulkUpdateRequest
): Promise<AssetBulkResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/bulk/update`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<AssetBulkResponse>(res);
}
