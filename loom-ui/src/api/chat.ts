import { API_BASE_URL } from "./config";
import type { PagingInfo } from "./paging";
import { ChatMessage, ChatReference, ChatToolCall, ChatVisual, PipelineGraphPayload } from "../types";
import { authHeaders, handleResponse } from "./http";

/**
 * Reference shape used by the backend (persisted messages and tool_end events):
 * entities are identified by `uuid`, while the UI's ChatReference uses `id`.
 */
export interface BackendChatReference {
  type: string;
  uuid?: string;
  id?: string;
  label: string;
}

/** Visual shape used by the backend (persisted messages and tool_end events, CHAT.md §6.1). */
export interface BackendChatVisual {
  type: string;
  uuid?: string;
  id?: string;
  label?: string;
  payload?: Record<string, unknown>;
}

/** Map a backend visual ({type, uuid, label, payload}) onto the UI ChatVisual ({type, id, …}). */
export function toChatVisual(raw: BackendChatVisual): ChatVisual {
  return {
    type: raw.type,
    id: raw.uuid ?? raw.id ?? "",
    label: raw.label ?? "",
    payload: (raw.payload ?? {}) as PipelineGraphPayload | Record<string, unknown>,
  };
}

/** Map a backend reference ({type, uuid, label}) onto the UI ChatReference ({type, id, label}). */
export function toChatReference(raw: BackendChatReference): ChatReference {
  return {
    type: raw.type as ChatReference["type"],
    id: raw.uuid ?? raw.id ?? "",
    label: raw.label,
  };
}

/**
 * Map a persisted backend message (CHAT.md §4.3 schema) onto the UI ChatMessage.
 * Tolerates legacy mock-era messages which already carry UI-shaped references.
 */
export function toChatMessage(raw: Record<string, unknown>): ChatMessage {
  const references = Array.isArray(raw.references)
    ? (raw.references as BackendChatReference[]).map(toChatReference).filter(r => r.id !== "")
    : undefined;
  const visuals = Array.isArray(raw.visuals)
    ? (raw.visuals as BackendChatVisual[]).map(toChatVisual).filter(v => !!v.type)
    : undefined;
  return {
    id: (raw.id as string) ?? `msg_${Math.random().toString(36).slice(2)}`,
    role: (raw.role as ChatMessage["role"]) ?? "assistant",
    content: (raw.content as string) ?? "",
    createdAt: (raw.createdAt as string) ?? new Date().toISOString(),
    reasoning: (raw.reasoning as string) || undefined,
    toolCalls: Array.isArray(raw.toolCalls) ? (raw.toolCalls as ChatToolCall[]) : undefined,
    references: references && references.length > 0 ? references : undefined,
    visuals: visuals && visuals.length > 0 ? visuals : undefined,
    actions: Array.isArray(raw.actions) ? (raw.actions as ChatMessage["actions"]) : undefined,
    suggestedFollowUps: Array.isArray(raw.suggestedFollowUps) ? (raw.suggestedFollowUps as string[]) : undefined,
  };
}

export interface ChatResponse {
  uuid: string;
  title: string;
  messages: ChatMessage[];
  /** The space (project) the chat belongs to; scopes space-level agent memory. */
  spaceUuid?: string;
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
  _metainfo?: PagingInfo;
}

export interface ChatCreateRequest {
  title: string;
  messages: ChatMessage[];
  /** Associates the chat with a space so the agent can reach that space's shared memory. */
  spaceUuid?: string;
  meta?: Record<string, unknown>;
}

export interface ChatUpdateRequest {
  title?: string;
  messages?: ChatMessage[];
  spaceUuid?: string;
  meta?: Record<string, unknown>;
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
  const chat = await handleResponse<ChatResponse>(res);
  // Persisted messages use the backend schema — normalize them for the UI
  chat.messages = (chat.messages ?? []).map(m => toChatMessage(m as unknown as Record<string, unknown>));
  return chat;
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
