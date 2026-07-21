import { API_BASE_URL } from "./config";
import { ChatMessage } from "../types";

export interface ChatResponse {
  uuid: string;
  title: string;
  messages: ChatMessage[];
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface ChatListResponse {
  data: ChatResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface ChatCreateRequest {
  title: string;
  messages: ChatMessage[];
  meta?: Record<string, unknown>;
}

export interface ChatUpdateRequest {
  title?: string;
  messages?: ChatMessage[];
  meta?: Record<string, unknown>;
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

export async function listChats(token: string): Promise<ChatListResponse> {
  const res = await fetch(`${API_BASE_URL}/chats`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ChatListResponse>(res);
}

export async function loadChat(token: string, uuid: string): Promise<ChatResponse> {
  const res = await fetch(`${API_BASE_URL}/chats/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ChatResponse>(res);
}

export async function createChat(token: string, request: ChatCreateRequest): Promise<ChatResponse> {
  const res = await fetch(`${API_BASE_URL}/chats`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ChatResponse>(res);
}

export async function updateChat(token: string, uuid: string, request: ChatUpdateRequest): Promise<ChatResponse> {
  const res = await fetch(`${API_BASE_URL}/chats/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ChatResponse>(res);
}

export async function deleteChat(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/chats/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
