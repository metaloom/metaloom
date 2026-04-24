import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API reaction models ──────────────────

export interface ReactionResponseItem {
  uuid: string;
  type?: string;
  rating?: number;
  status?: {
    creator?: { uuid: string; username?: string };
    created?: string;
    editor?: { uuid: string; username?: string };
    edited?: string;
  };
}

export interface ReactionListResponse {
  data: ReactionResponseItem[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
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

// ── API for asset reactions (sub-resource of assets) ──────────────────

export async function listAssetReactions(token: string, assetUuid: string): Promise<ReactionListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/reactions`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ReactionListResponse>(res);
}

export async function loadAssetReaction(token: string, assetUuid: string, reactionUuid: string): Promise<ReactionResponseItem> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/reactions/${encodeURIComponent(reactionUuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ReactionResponseItem>(res);
}
