import { API_BASE_URL } from "./config";
import { AnnotationResponseItem } from "./annotations";

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
  locations?: unknown[];
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
  hash?: {
    sha512?: string;
  };
  tags?: { name: string; collection?: string }[];
  meta?: Record<string, unknown>;
}

export interface AssetUpdateRequest {
  filename?: string;
  meta?: Record<string, unknown>;
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
