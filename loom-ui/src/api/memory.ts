import { API_BASE_URL } from "./config";

/**
 * The agent memory bank: scoped markdown notes addressed by a path-like id.
 *
 * Ids are nested paths, so they travel as the `id` query parameter rather than in the route —
 * the same approach the session filesystem proxy uses for `?path=`.
 */
export interface MemoryScopeInfo {
  /** "user" | "group" | "space" */
  scope: string;
  uuid: string;
  /** Human readable name; "user" for the private scope. */
  label: string;
  /** How the scope is addressed, e.g. "user" or "group:editors". */
  ref: string;
  count: number;
  bytes: number;
  maxEntries: number;
  maxBytes: number;
  /** False when the deployment keeps shared scopes human-curated. */
  writable: boolean;
}

export interface MemoryScopeListResponse {
  scopes: MemoryScopeInfo[];
}

export interface MemoryEntrySummary {
  uuid: string;
  scope: string;
  id: string;
  title?: string;
  size: number;
  version: number;
  /** Name of the chat session that last wrote the note. */
  sessionName?: string;
  created?: string;
  edited?: string;
  editor?: string;
}

export interface MemoryEntryDetail extends MemoryEntrySummary {
  /** Markdown body without the provenance header, which is rendered from the row. */
  body: string;
}

export interface MemoryListResponse {
  scope: string;
  entries: MemoryEntrySummary[];
}

export interface MemoryWriteRequest {
  body: string;
  title?: string;
}

/**
 * Carries the HTTP status alongside the message.
 *
 * The caller has to tell a 409 (this id is taken — the editor says so inline and keeps the draft)
 * apart from every other failure, which a bare `Error` cannot.
 */
export class MemoryApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "MemoryApiError";
    this.status = status;
  }
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
    throw new MemoryApiError(res.status, `API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

/** Build the `?scope=&ref=&id=` query shared by every entry route. */
function entryQuery(scope: string, id: string, ref?: string): string {
  const params = new URLSearchParams({ scope, id });
  if (ref) {
    params.set("ref", ref);
  }
  return params.toString();
}

/**
 * The scopes the caller may use, with usage and quota.
 *
 * @param chatUuid optional chat to derive the space (project) scope from
 */
export async function listMemoryScopes(token: string, chatUuid?: string): Promise<MemoryScopeListResponse> {
  const query = chatUuid ? `?chat=${encodeURIComponent(chatUuid)}` : "";
  const res = await fetch(`${API_BASE_URL}/memory/scopes${query}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<MemoryScopeListResponse>(res);
}

export async function listMemory(token: string, scope: string, ref?: string, prefix?: string): Promise<MemoryListResponse> {
  const params = new URLSearchParams({ scope });
  if (ref) {
    params.set("ref", ref);
  }
  if (prefix) {
    params.set("prefix", prefix);
  }
  const res = await fetch(`${API_BASE_URL}/memory?${params.toString()}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<MemoryListResponse>(res);
}

export async function loadMemoryEntry(token: string, scope: string, id: string, ref?: string): Promise<MemoryEntryDetail> {
  const res = await fetch(`${API_BASE_URL}/memory/entry?${entryQuery(scope, id, ref)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<MemoryEntryDetail>(res);
}

/** Create a note. Fails with 409 when the id already exists. */
export async function createMemoryEntry(
  token: string, scope: string, id: string, request: MemoryWriteRequest, ref?: string,
): Promise<MemoryEntrySummary> {
  const res = await fetch(`${API_BASE_URL}/memory/entry?${entryQuery(scope, id, ref)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<MemoryEntrySummary>(res);
}

/** Create or overwrite a note. */
export async function saveMemoryEntry(
  token: string, scope: string, id: string, request: MemoryWriteRequest, ref?: string,
): Promise<MemoryEntrySummary> {
  const res = await fetch(`${API_BASE_URL}/memory/entry?${entryQuery(scope, id, ref)}`, {
    method: "PUT",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<MemoryEntrySummary>(res);
}

export async function deleteMemoryEntry(token: string, scope: string, id: string, ref?: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/memory/entry?${entryQuery(scope, id, ref)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new MemoryApiError(res.status, `API error ${res.status}: ${text}`);
  }
}
