import { API_BASE_URL } from "./config";
import type { PagingInfo } from "./paging";
import { authHeaders, handleResponse } from "./http";

/**
 * The memory denylist: instance-wide regular expressions that must never be written into the agent
 * memory bank. A match rejects the `put_memory` call with the rule's own message.
 *
 * Administered separately from the notes themselves — these routes are gated by the
 * `*_MEMORY_DENY_RULE` permissions, not the `*_MEMORY` ones every chat user holds.
 */
export interface MemoryDenyRuleResponse {
  uuid: string;
  name: string;
  /** Java regular expression. Several phrases fit in one rule via alternation. */
  pattern: string;
  /** Returned to the agent on rejection; must not echo the matched text. */
  message: string;
  enabled: boolean;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface MemoryDenyRuleListResponse {
  data?: MemoryDenyRuleResponse[];
  _metainfo?: PagingInfo;
}

export interface MemoryDenyRuleCreateRequest {
  name: string;
  pattern: string;
  message: string;
  enabled?: boolean;
}

export interface MemoryDenyRuleUpdateRequest {
  name?: string;
  pattern?: string;
  message?: string;
  enabled?: boolean;
}

export async function listMemoryDenyRules(token: string): Promise<MemoryDenyRuleListResponse> {
  const res = await fetch(`${API_BASE_URL}/memory-deny-rules`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<MemoryDenyRuleListResponse>(res);
}

export async function createMemoryDenyRule(token: string, request: MemoryDenyRuleCreateRequest): Promise<MemoryDenyRuleResponse> {
  const res = await fetch(`${API_BASE_URL}/memory-deny-rules`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<MemoryDenyRuleResponse>(res);
}

// Updates use POST (not PUT) — loom convention
export async function updateMemoryDenyRule(
  token: string, uuid: string, request: MemoryDenyRuleUpdateRequest,
): Promise<MemoryDenyRuleResponse> {
  const res = await fetch(`${API_BASE_URL}/memory-deny-rules/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<MemoryDenyRuleResponse>(res);
}

export async function deleteMemoryDenyRule(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/memory-deny-rules/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
