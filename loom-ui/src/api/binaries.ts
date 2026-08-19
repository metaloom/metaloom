import { API_BASE_URL } from "./config";
import { authHeaders, handleResponse } from "./http";

// ── Types ─────────────────────────────────────────────────────────────

export interface AssetBinaryResponse {
  uuid: string;
  assetUuid?: string;
  libraryUuid?: string;
  filesystem?: {
    path?: string;
    lastSeen?: string;
  };
  s3?: {
    bucket?: string;
    objectPath?: string;
  };
  mimeType?: string;
}

/**
 * Request body for registering binary *metadata* (POST /assets/:uuid/binary).
 * Mirrors the backend `AssetBinaryCreateRequest`. The backend currently consumes
 * `filesystem.path` + `libraryUuid` (+ optional `meta`); `s3` is not yet implemented.
 */
export interface AssetBinaryCreateRequest {
  libraryUuid?: string;
  filesystem?: { path: string; lastSeen?: string };
  s3?: { bucket: string; objectPath: string };
  meta?: Record<string, unknown>;
}

// ── Helpers ───────────────────────────────────────────────────────────

async function handleBlobResponse(res: Response): Promise<Blob> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.blob();
}

// ── Binary API ────────────────────────────────────────────────────────

/**
 * Upload raw file bytes into the binary of an existing asset. The backend
 * persists the bytes content-addressed and creates or replaces the asset's
 * binary row.
 *
 * `libraryUuid` is only required by the backend when the asset has no binary
 * yet; when replacing an existing binary it may be omitted.
 *
 * Note: no `Content-Type` header is set — the browser sets the multipart boundary.
 */
export async function uploadAssetBinary(
  token: string,
  assetUuid: string,
  file: File,
  libraryUuid?: string
): Promise<AssetBinaryResponse> {
  const form = new FormData();
  form.append("file", file, file.name);
  if (libraryUuid) {
    form.append("libraryUuid", libraryUuid);
  }
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/binary/data`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    }
  );
  return handleResponse<AssetBinaryResponse>(res);
}

/** Fetch the raw binary bytes of an asset as a Blob. */
export async function fetchAssetBinaryBlob(
  token: string,
  assetUuid: string
): Promise<Blob> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/binary/data`,
    {
      method: "GET",
      headers: { Authorization: `Bearer ${token}` },
    }
  );
  return handleBlobResponse(res);
}

/** Download the raw binary bytes of an asset, prompting the browser to save it. */
export async function downloadAssetBinary(
  token: string,
  assetUuid: string,
  filename: string
): Promise<void> {
  const blob = await fetchAssetBinaryBlob(token, assetUuid);
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Load the binary metadata record (filesystem path / S3 pointer) for an asset. */
export async function loadAssetBinaryMeta(
  token: string,
  assetUuid: string
): Promise<AssetBinaryResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/binary`,
    {
      method: "GET",
      headers: authHeaders(token),
    }
  );
  return handleResponse<AssetBinaryResponse>(res);
}

/**
 * Register binary *metadata* for an asset — point it at bytes that already exist
 * (e.g. a filesystem path / S3 object) rather than streaming the bytes.
 *
 * Unlike {@link uploadAssetBinary} this sends no bytes. The backend requires
 * `libraryUuid` and creates a new binary row (POST /assets/:uuid/binary).
 */
export async function createAssetBinaryMeta(
  token: string,
  assetUuid: string,
  request: AssetBinaryCreateRequest
): Promise<AssetBinaryResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/binary`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<AssetBinaryResponse>(res);
}

/** Delete the binary of an asset. */
export async function deleteAssetBinary(
  token: string,
  assetUuid: string
): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/binary`,
    {
      method: "DELETE",
      headers: authHeaders(token),
    }
  );
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Delete failed ${res.status}: ${text}`);
  }
}
