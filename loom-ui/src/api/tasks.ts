import { API_BASE_URL } from "./config";
import { CommentResponse } from "./comments";

// ── Types matching the Loom REST API task models ──────────────────────

export interface TaskResponse {
  uuid: string;
  title: string;
  description?: string;
  priority?: string;
  // Workflow status of the task (PENDING | REJECTED | ACCEPTED | REVIEW).
  // Named taskStatus because "status" carries the creator/editor audit info.
  taskStatus?: string;
  dueDate?: string;
  comments?: CommentResponse[];
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface TaskListResponse {
  data: TaskResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface TaskCreateRequest {
  title: string;
  description?: string;
  priority?: string;
  taskStatus?: string;
  dueDate?: string;
  meta?: Record<string, unknown>;
}

export interface TaskUpdateRequest {
  title?: string;
  description?: string;
  priority?: string;
  taskStatus?: string;
  dueDate?: string;
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

export async function listTasks(token: string): Promise<TaskListResponse> {
  const res = await fetch(`${API_BASE_URL}/tasks`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TaskListResponse>(res);
}

export async function loadTask(token: string, uuid: string): Promise<TaskResponse> {
  const res = await fetch(`${API_BASE_URL}/tasks/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TaskResponse>(res);
}

export async function createTask(token: string, request: TaskCreateRequest): Promise<TaskResponse> {
  const res = await fetch(`${API_BASE_URL}/tasks`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TaskResponse>(res);
}

export async function updateTask(token: string, uuid: string, request: TaskUpdateRequest): Promise<TaskResponse> {
  const res = await fetch(`${API_BASE_URL}/tasks/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TaskResponse>(res);
}

export async function deleteTask(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/tasks/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

// ── Asset task assignment ─────────────────────────────────────────────

export async function listAssetTasks(token: string, assetUuid: string): Promise<TaskListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/tasks`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TaskListResponse>(res);
}

export async function assignTaskToAsset(token: string, assetUuid: string, taskUuid: string): Promise<TaskResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/tasks/${encodeURIComponent(taskUuid)}`, {
    method: "POST",
    headers: authHeaders(token),
  });
  return handleResponse<TaskResponse>(res);
}

export async function unassignTaskFromAsset(token: string, assetUuid: string, taskUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/tasks/${encodeURIComponent(taskUuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
