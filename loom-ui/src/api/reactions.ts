import { API_BASE_URL } from "./config";
import type { PagingInfo } from "./paging";
import { authHeaders, handleResponse } from "./http";

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
  _metainfo?: PagingInfo;
}

/** Reaction type values, matching the backend `io.metaloom.loom.api.reaction.ReactionType` enum. */
export type TaskReactionType = "THUMBSUP" | "THUMBSDOWN" | "SATISFIED" | "PLUS_ONE" | "MINUS_ONE" | "RATING";

export interface ReactionCreateRequest {
  type?: TaskReactionType;
  rating?: number;
}

// ── Helpers ───────────────────────────────────────────────────────────

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

/** Create a reaction on an asset. POST /assets/:assetUuid/reactions */
export async function createAssetReaction(token: string, assetUuid: string, request: ReactionCreateRequest): Promise<ReactionResponseItem> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/reactions`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ReactionResponseItem>(res);
}

/** Update an asset reaction. POST /assets/:assetUuid/reactions/:reactionUuid (update is POST, not PUT/PATCH). */
export async function updateAssetReaction(
  token: string,
  assetUuid: string,
  reactionUuid: string,
  request: ReactionCreateRequest,
): Promise<ReactionResponseItem> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    },
  );
  return handleResponse<ReactionResponseItem>(res);
}

/** Delete an asset reaction. DELETE /assets/:assetUuid/reactions/:reactionUuid (204). */
export async function deleteAssetReaction(token: string, assetUuid: string, reactionUuid: string): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
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

// ── API for comment reactions (sub-resource of comments) ──────────────

/** List the reactions of a comment. GET /comments/:commentUuid/reactions */
export async function listCommentReactions(token: string, commentUuid: string): Promise<ReactionListResponse> {
  const res = await fetch(`${API_BASE_URL}/comments/${encodeURIComponent(commentUuid)}/reactions`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ReactionListResponse>(res);
}

/** Create a reaction on a comment. POST /comments/:commentUuid/reactions */
export async function createCommentReaction(token: string, commentUuid: string, request: ReactionCreateRequest): Promise<ReactionResponseItem> {
  const res = await fetch(`${API_BASE_URL}/comments/${encodeURIComponent(commentUuid)}/reactions`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ReactionResponseItem>(res);
}

/** Update a comment reaction. POST /comments/:commentUuid/reactions/:reactionUuid (update is POST, not PUT/PATCH). */
export async function updateCommentReaction(
  token: string,
  commentUuid: string,
  reactionUuid: string,
  request: ReactionCreateRequest,
): Promise<ReactionResponseItem> {
  const res = await fetch(
    `${API_BASE_URL}/comments/${encodeURIComponent(commentUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    },
  );
  return handleResponse<ReactionResponseItem>(res);
}

/** Delete a comment reaction. DELETE /comments/:commentUuid/reactions/:reactionUuid (204). */
export async function deleteCommentReaction(token: string, commentUuid: string, reactionUuid: string): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/comments/${encodeURIComponent(commentUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
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

// ── API for annotation reactions (sub-resource of annotations) ────────

/** List the reactions of an annotation. GET /annotations/:annotationUuid/reactions */
export async function listAnnotationReactions(token: string, annotationUuid: string): Promise<ReactionListResponse> {
  const res = await fetch(`${API_BASE_URL}/annotations/${encodeURIComponent(annotationUuid)}/reactions`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ReactionListResponse>(res);
}

/** Load a single annotation reaction. GET /annotations/:annotationUuid/reactions/:reactionUuid */
export async function loadAnnotationReaction(token: string, annotationUuid: string, reactionUuid: string): Promise<ReactionResponseItem> {
  const res = await fetch(
    `${API_BASE_URL}/annotations/${encodeURIComponent(annotationUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
    {
      method: "GET",
      headers: authHeaders(token),
    },
  );
  return handleResponse<ReactionResponseItem>(res);
}

/** Create a reaction on an annotation. POST /annotations/:annotationUuid/reactions */
export async function createAnnotationReaction(token: string, annotationUuid: string, request: ReactionCreateRequest): Promise<ReactionResponseItem> {
  const res = await fetch(`${API_BASE_URL}/annotations/${encodeURIComponent(annotationUuid)}/reactions`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ReactionResponseItem>(res);
}

/** Update an annotation reaction. POST /annotations/:annotationUuid/reactions/:reactionUuid (update is POST, not PUT/PATCH). */
export async function updateAnnotationReaction(
  token: string,
  annotationUuid: string,
  reactionUuid: string,
  request: ReactionCreateRequest,
): Promise<ReactionResponseItem> {
  const res = await fetch(
    `${API_BASE_URL}/annotations/${encodeURIComponent(annotationUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    },
  );
  return handleResponse<ReactionResponseItem>(res);
}

/** Delete an annotation reaction. DELETE /annotations/:annotationUuid/reactions/:reactionUuid (204). */
export async function deleteAnnotationReaction(token: string, annotationUuid: string, reactionUuid: string): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/annotations/${encodeURIComponent(annotationUuid)}/reactions/${encodeURIComponent(reactionUuid)}`,
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
