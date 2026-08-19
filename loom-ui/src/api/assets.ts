import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { AnnotationResponseItem, AreaInfo } from "./annotations";
import { authHeaders, handleResponse } from "./http";

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
  /**
   * Identity of *this* placement of the tag on the asset. A tag may sit on one asset several
   * times — once per face, once per timecode — and this is what identifies the one you mean.
   * Deleting a placement and deleting the tag from the asset are different operations.
   */
  placementUuid?: string;
  /**
   * Which node kind attached the tag, or `"manual"` when a person did. Absent counts as manual:
   * the column defaults to `'manual'`, deliberately, because a machine tag mislabelled human is
   * merely not filtered out, while a human tag mislabelled machine could be deleted by a
   * reconciling node.
   */
  nodeKind?: string;
  /** Pipeline node id of the writer, so two instances of one node kind stay distinguishable. */
  nodeId?: string;
  /** How sure the writer was, 0.0–1.0. Absent when the question does not apply — a person. */
  confidence?: number;
  /** When the tag was attached to this asset — not when the tag itself was created. */
  attached?: string;
  /** The principal that made the call, person or worker token. */
  attachedBy?: string;
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

/** @deprecated Re-export kept for existing importers — the definition lives in `./paging`. */
export type { PagingInfo };

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

export async function listAssets(token: string, paging?: PagingParams): Promise<AssetListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets${pagingQuery(paging)}`, {
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
export interface UploadOptions {
  origin?: string;
  /**
   * Storage pool to write into, overriding the target library's pool. Requires the READ_ASSET_POOL
   * permission server-side; omit to let the library decide.
   */
  poolUuid?: string;
}

/**
 * Build the multipart body for an upload. Extracted so both transports share one definition of the
 * wire format, and so the field shaping is testable without a network.
 */
export function buildUploadForm(file: File, libraryUuid: string, opts?: UploadOptions): FormData {
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("libraryUuid", libraryUuid);
  if (opts?.origin) {
    form.append("origin", opts.origin);
  }
  // Only sent when explicitly chosen: an empty value would still be a field the server has to
  // interpret, and "" means "library's pool" only because the backend treats blank as absent.
  if (opts?.poolUuid) {
    form.append("poolUuid", opts.poolUuid);
  }
  return form;
}

export async function uploadAsset(
  token: string,
  file: File,
  libraryUuid: string,
  opts?: UploadOptions
): Promise<AssetResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/upload`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: buildUploadForm(file, libraryUuid, opts),
  });
  return handleResponse<AssetResponse>(res);
}

/** What an in-flight upload reports back while the bytes are still going out. */
export interface UploadProgress {
  /** Bytes handed to the socket so far. */
  loaded: number;
  /** Total bytes of the request body, or 0 while the length is still unknown. */
  total: number;
}

export interface UploadResult {
  asset: AssetResponse;
  /**
   * False when the server answered 200 instead of 201, meaning an asset with these bytes already
   * existed and was reused. Worth surfacing — the file was not rejected, but nothing new was made.
   */
  created: boolean;
}

export interface UploadHandle {
  /** Resolves with the created (or already-known) asset; rejects on HTTP error or abort. */
  promise: Promise<UploadResult>;
  /** Stop the transfer. The promise rejects with an {@link UploadAbortedError}. */
  abort: () => void;
}

/** Thrown when an upload is cancelled by the user, so callers can tell it apart from a real failure. */
export class UploadAbortedError extends Error {
  constructor() {
    super("Upload aborted");
    this.name = "UploadAbortedError";
  }
}

/**
 * Upload a file with progress reporting and cancellation.
 *
 * Uses XMLHttpRequest rather than fetch deliberately: fetch exposes no upload-progress event, and
 * request-body streams are not supported widely enough to substitute. Everything else in the API
 * layer stays on fetch.
 */
export function uploadAssetWithProgress(
  token: string,
  file: File,
  libraryUuid: string,
  opts?: UploadOptions & { onProgress?: (progress: UploadProgress) => void }
): UploadHandle {
  const xhr = new XMLHttpRequest();
  let aborted = false;

  const promise = new Promise<UploadResult>((resolve, reject) => {
    xhr.open("POST", `${API_BASE_URL}/assets/upload`);
    xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    // No Content-Type: the browser has to set the multipart boundary itself.

    xhr.upload.onprogress = (event) => {
      opts?.onProgress?.({ loaded: event.loaded, total: event.lengthComputable ? event.total : 0 });
    };

    xhr.onload = () => {
      if (xhr.status < 200 || xhr.status >= 300) {
        reject(new Error(`API error ${xhr.status}: ${xhr.responseText ?? ""}`));
        return;
      }
      try {
        resolve({ asset: JSON.parse(xhr.responseText) as AssetResponse, created: xhr.status === 201 });
      } catch {
        reject(new Error("Upload succeeded but the response could not be parsed"));
      }
    };

    // A cancelled transfer fires both onabort and onerror in some browsers; the flag keeps the
    // first (correct) rejection and makes the second a no-op.
    xhr.onabort = () => {
      aborted = true;
      reject(new UploadAbortedError());
    };
    xhr.onerror = () => {
      if (!aborted) reject(new Error("Upload failed: the request could not be completed"));
    };

    xhr.send(buildUploadForm(file, libraryUuid, opts));
  });

  return { promise, abort: () => xhr.abort() };
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
