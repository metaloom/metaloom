import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API comment models ───────────────────

export interface CommentResponse {
  uuid: string;
  title?: string;
  text?: string;
  assetUuid?: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface CommentListResponse {
  data: CommentResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface CommentCreateRequest {
  title?: string;
  text?: string;
  meta?: Record<string, unknown>;
}

export interface CommentUpdateRequest {
  title?: string;
  text?: string;
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

export async function listComments(token: string): Promise<CommentListResponse> {
  const res = await fetch(`${API_BASE_URL}/comments`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<CommentListResponse>(res);
}

export async function listCommentsForAsset(
  token: string,
  assetUuid: string
): Promise<CommentListResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/comments`,
    {
      method: "GET",
      headers: authHeaders(token),
    }
  );
  return handleResponse<CommentListResponse>(res);
}

export async function createCommentForAsset(
  token: string,
  assetUuid: string,
  request: CommentCreateRequest
): Promise<CommentResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/comments`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<CommentResponse>(res);
}

export async function listCommentsForTask(
  token: string,
  taskUuid: string
): Promise<CommentListResponse> {
  const res = await fetch(
    `${API_BASE_URL}/tasks/${encodeURIComponent(taskUuid)}/comments`,
    {
      method: "GET",
      headers: authHeaders(token),
    }
  );
  return handleResponse<CommentListResponse>(res);
}

export async function createCommentForTask(
  token: string,
  taskUuid: string,
  request: CommentCreateRequest
): Promise<CommentResponse> {
  const res = await fetch(
    `${API_BASE_URL}/tasks/${encodeURIComponent(taskUuid)}/comments`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<CommentResponse>(res);
}

export async function loadComment(
  token: string,
  uuid: string
): Promise<CommentResponse> {
  const res = await fetch(`${API_BASE_URL}/comments/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<CommentResponse>(res);
}

export async function createComment(
  token: string,
  request: CommentCreateRequest
): Promise<CommentResponse> {
  const res = await fetch(`${API_BASE_URL}/comments`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<CommentResponse>(res);
}

export async function updateComment(
  token: string,
  uuid: string,
  request: CommentUpdateRequest
): Promise<CommentResponse> {
  const res = await fetch(`${API_BASE_URL}/comments/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<CommentResponse>(res);
}

export async function deleteComment(
  token: string,
  uuid: string
): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/comments/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
