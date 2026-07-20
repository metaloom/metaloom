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

/** Reaction type values, matching the backend `io.metaloom.loom.api.reaction.ReactionType` enum. */
export type TaskReactionType = "THUMBSUP" | "THUMBSDOWN" | "SATISFIED" | "PLUS_ONE" | "MINUS_ONE";

export interface ReactionCreateRequest {
  type?: TaskReactionType;
  rating?: number;
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

// ── API for task reactions (sub-resource of tasks) ────────────────────

/** List the reactions of a task. GET /tasks/:taskUuid/reactions */
export async function listTaskReactions(token: string, taskUuid: string): Promise<ReactionListResponse> {
  const res = await fetch(`${API_BASE_URL}/tasks/${encodeURIComponent(taskUuid)}/reactions`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ReactionListResponse>(res);
}

/** Create a reaction on a task. POST /tasks/:taskUuid/reactions */
export async function createTaskReaction(token: string, taskUuid: string, request: ReactionCreateRequest): Promise<ReactionResponseItem> {
  const res = await fetch(`${API_BASE_URL}/tasks/${encodeURIComponent(taskUuid)}/reactions`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ReactionResponseItem>(res);
}

/** Update a task reaction. POST /tasks/:taskUuid/reactions/:reactionUuid (update is POST, not PUT/PATCH). */
export async function updateTaskReaction(
  token: string,
  taskUuid: string,
  reactionUuid: string,
  request: ReactionCreateRequest,
): Promise<ReactionResponseItem> {
  const res = await fetch(
    `${API_BASE_URL}/tasks/${encodeURIComponent(taskUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    },
  );
  return handleResponse<ReactionResponseItem>(res);
}

/** Delete a task reaction. DELETE /tasks/:taskUuid/reactions/:reactionUuid (204). */
export async function deleteTaskReaction(token: string, taskUuid: string, reactionUuid: string): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/tasks/${encodeURIComponent(taskUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
    {
      method: "DELETE",
      headers: authHeaders(token),
    },
  );
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
