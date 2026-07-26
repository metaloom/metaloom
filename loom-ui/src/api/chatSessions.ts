import { API_BASE_URL } from "./config";

export interface ChatSessionSkillPin {
  skillUuid: string;
  skillVersion: number;
}

export interface ChatSessionContextRef {
  sourceSessionUuid: string;
  includeChatHistory: boolean;
  includeSkills: boolean;
  includeFilesystem: boolean;
  ordinal: number;
}

export interface ChatSessionResponse {
  uuid: string;
  chatUuid?: string | null;
  name: string;
  description?: string | null;
  tags?: string[];
  published: boolean;
  fsSize?: number | null;
  hasFilesystem?: boolean;
  skills?: ChatSessionSkillPin[];
  contextRefs?: ChatSessionContextRef[];
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface ChatSessionListResponse {
  data: ChatSessionResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface ChatSessionCreateRequest {
  /** Optional — a manually-created session has no owning chat. */
  chatUuid?: string;
  name?: string;
  description?: string;
  tags?: string[];
}

export interface ChatSessionUpdateRequest {
  name?: string;
  description?: string;
  tags?: string[];
}

export interface ChatSessionContextRequest {
  refs: ChatSessionContextRef[];
}

export interface ChatSessionContextResponse {
  refs: ChatSessionContextRef[];
}

export interface SessionFileEntry {
  name: string;
  type: "file" | "dir";
  size: number;
}

export interface SessionFilesResponse {
  entries: SessionFileEntry[];
}

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

/** List chat sessions. scope="mine" (default) lists own sessions; scope="published" lists the shared library. */
export async function listChatSessions(token: string, scope: "mine" | "published" = "mine"): Promise<ChatSessionListResponse> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions?scope=${scope}`, {
    headers: authHeaders(token),
  });
  return handleResponse<ChatSessionListResponse>(res);
}

export async function loadChatSession(token: string, uuid: string): Promise<ChatSessionResponse> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions/${encodeURIComponent(uuid)}`, {
    headers: authHeaders(token),
  });
  return handleResponse<ChatSessionResponse>(res);
}

export async function createChatSession(token: string, request: ChatSessionCreateRequest): Promise<ChatSessionResponse> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ChatSessionResponse>(res);
}

/** Updates use POST (not PUT) — loom convention. */
export async function updateChatSession(token: string, uuid: string, request: ChatSessionUpdateRequest): Promise<ChatSessionResponse> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ChatSessionResponse>(res);
}

export async function deleteChatSession(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

export async function publishChatSession(token: string, uuid: string, published: boolean): Promise<ChatSessionResponse> {
  const action = published ? "publish" : "unpublish";
  const res = await fetch(`${API_BASE_URL}/chat-sessions/${encodeURIComponent(uuid)}/${action}`, {
    method: "POST",
    headers: authHeaders(token),
  });
  return handleResponse<ChatSessionResponse>(res);
}

export async function loadChatSessionContext(token: string, uuid: string): Promise<ChatSessionContextResponse> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions/${encodeURIComponent(uuid)}/context`, {
    headers: authHeaders(token),
  });
  return handleResponse<ChatSessionContextResponse>(res);
}

export async function replaceChatSessionContext(token: string, uuid: string, request: ChatSessionContextRequest): Promise<ChatSessionContextResponse> {
  const res = await fetch(`${API_BASE_URL}/chat-sessions/${encodeURIComponent(uuid)}/context`, {
    method: "PUT",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ChatSessionContextResponse>(res);
}

/**
 * List files of a session's live coding workspace via the Phase-2 filesystem proxy
 * (GET /sessions/:chatUuid/files). Keyed by the chat uuid. Only a running coding session can be
 * browsed — the proxy answers 404 when no live runner exists.
 */
export async function listSessionFiles(token: string, chatUuid: string, path = "."): Promise<SessionFilesResponse> {
  const res = await fetch(`${API_BASE_URL}/sessions/${encodeURIComponent(chatUuid)}/files?path=${encodeURIComponent(path)}`, {
    headers: authHeaders(token),
  });
  return handleResponse<SessionFilesResponse>(res);
}

/** Download/preview URL for a single workspace file (streamed by the proxy). */
export function sessionFileDownloadUrl(chatUuid: string, path: string, inline = false): string {
  return `${API_BASE_URL}/sessions/${encodeURIComponent(chatUuid)}/download?path=${encodeURIComponent(path)}&inline=${inline}`;
}
