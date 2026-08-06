import { API_BASE_URL } from "./config";
import type {
  SearchCapability,
  SearchEntityType,
  SearchFacetName,
  SearchMode,
  SearchSortMode,
} from "../types";

// ── Types matching the Loom REST API search models ────────────────────

/** Which field produced the match. Derived server-side while building the snippet. */
export type SearchMatchedIn = "title" | "subtitle" | "body" | "keywords" | "fuzzy";

export interface SearchHitResponse {
  /** Lowercase entity id — the field is `type`, not `entityType`. */
  type: SearchEntityType;
  /** The found element's own uuid — the field is `uuid`, not `entityUuid`. */
  uuid: string;
  /** The asset this hit belongs to, when there is one. Equals `uuid` for asset hits. */
  assetUuid?: string;
  /** Relevance score. Only comparable within one response. */
  score: number;
  title: string;
  subtitle?: string;
  matchedIn?: SearchMatchedIn;
  /**
   * Match snippets — an array, not a single string, and only present when `highlight=true` was
   * requested and the provider supports it.
   *
   * Each fragment carries raw `<b>` markup around the match and is otherwise the source document
   * verbatim. It is NOT sanitised HTML. Render it through `features/search/highlight.ts`.
   */
  highlights?: string[];
  /** Offset into the media, for transcript and annotation hits. Milliseconds. */
  timeFromMs?: number;
  mimeType?: string;
  size?: number;
  /** Epoch seconds with a fractional part — the server serializes Instant as a number, not ISO. */
  sortDate?: number;
}

export interface SearchFacetBucket {
  value: string;
  count: number;
}

export interface SearchMetaInfo {
  totalHits: number;
  /** Whether `totalHits` is exact rather than capped or estimated. */
  totalExact: boolean;
  /**
   * Echoes the *requested* limit, not the effective one — asking for 500 returns at most 100 rows
   * but still reports 500 here. Never do paging math with it; use `data.length`.
   */
  perPage: number;
  offset: number;
  /**
   * Opaque cursor for the next page. Always absent under the Postgres provider, which has no
   * DEEP_PAGING capability. Prefer it over `offset` when present.
   */
  nextCursor?: string;
  tookMs: number;
  provider: string;
  capabilities: SearchCapability[];
  /**
   * Non-fatal notes — most importantly the entity types dropped because the caller lacks the
   * matching read permission. Surface them: a narrowed result is otherwise indistinguishable
   * from an empty index.
   */
  warnings: string[];
}

export interface SearchResultResponse {
  data: SearchHitResponse[];
  _metainfo: SearchMetaInfo;
  /** Keyed by the facet name the caller asked for — the server echoes the spelling back. */
  facets?: Record<string, SearchFacetBucket[]>;
}

export interface SearchSuggestionResponse {
  text: string;
  type: SearchEntityType;
  uuid: string;
  score: number;
}

/** `data` is absent rather than `[]` when there is nothing to suggest. */
interface SearchSuggestionListResponse {
  data?: SearchSuggestionResponse[];
}

export interface SearchStatusResponse {
  /** `postgres`, `elasticsearch` or `none`. */
  provider: string;
  available: boolean;
  /** Why search is unavailable. Absent when available. */
  reason?: string;
  capabilities: SearchCapability[];
  documentCount: number;
  dirtyCount: number;
  /** Epoch seconds with a fractional part — the server serializes Instant as a number, not ISO. */
  lastSyncedAt?: number;
}

/** Every query parameter the `/search/*` routes accept. */
export interface SearchRequestParams {
  /** The search term. Supports "quoted phrases", `or`, and `-negation`. Must be non-blank. */
  q: string;
  types?: SearchEntityType[];
  mode?: SearchMode;
  limit?: number;
  offset?: number;
  cursor?: string;
  sort?: SearchSortMode;
  highlight?: boolean;
  /** Mime type prefix, e.g. `image/`. */
  mime?: string;
  library?: string;
  space?: string;
  collection?: string;
  tag?: string[];
  /** ISO-8601 instant. */
  from?: string;
  /** ISO-8601 instant. */
  to?: string;
  lang?: string;
  profile?: string;
  facets?: SearchFacetName[];
}

// ── Errors ────────────────────────────────────────────────────────────

/**
 * A failed `/search/*` call.
 *
 * The server discards its own error code before writing the response — the body is always
 * `{"message": "..."}` with nothing machine readable in it — so `status` is the entire signal
 * and callers must branch on it:
 *
 * - `503` the Noop provider is bound; search is unavailable on this deployment
 * - `400` blank or over-long term, an unsupported mode, or paging past the offset cap
 * - `403` missing READ_SEARCH, or no requested entity type survived permission narrowing
 */
export class SearchApiError extends Error {
  readonly status: number;
  /** The server's human-readable message, unwrapped from the JSON envelope. */
  readonly body: string;

  constructor(status: number, body: string) {
    // Same message shape the other clients throw, so existing log scraping still reads.
    super(`API error ${status}: ${body}`);
    this.name = "SearchApiError";
    this.status = status;
    this.body = body;
  }
}

// ── Helpers ───────────────────────────────────────────────────────────

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

/** Pull `message` out of the error envelope, falling back to the raw body. */
function extractMessage(text: string): string {
  if (!text) return "";
  try {
    const parsed = JSON.parse(text);
    return typeof parsed?.message === "string" ? parsed.message : text;
  } catch {
    return text;
  }
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new SearchApiError(res.status, extractMessage(text));
  }
  return res.json() as Promise<T>;
}

/**
 * Serialize search parameters into a query string.
 *
 * Two rules that are easy to get wrong and both earn a 400:
 *
 * - Every key is `set`, never `append`. The server rejects a repeated scalar key outright with
 *   "Parameter x was found multiple times".
 * - Undefined and blank values are omitted entirely. A stray `?mode=` is harmless today but a
 *   stray `?q=` is not, and the rule is simpler than the exceptions.
 *
 * Exported for the unit tests; callers should use the functions below.
 */
export function buildSearchQuery(params: SearchRequestParams): string {
  const query = new URLSearchParams();

  const put = (key: string, value: string | number | undefined) => {
    if (value === undefined || value === null) return;
    const text = String(value);
    if (text.trim() === "") return;
    query.set(key, text);
  };

  const putList = (key: string, values?: readonly string[]) => {
    if (!values) return;
    const joined = values.map((value) => value.trim()).filter(Boolean).join(",");
    if (joined) query.set(key, joined);
  };

  put("q", params.q);
  putList("types", params.types);
  put("mode", params.mode);
  put("limit", params.limit);
  // 0 is a meaningful offset, so this must not be treated as falsy.
  put("offset", params.offset);
  put("cursor", params.cursor);
  put("sort", params.sort);
  // Handled outside `put`: String(false) is "false", which is non-blank and would be sent. The
  // server default is already false, so only the true case needs a key.
  if (params.highlight) query.set("highlight", "true");
  put("mime", params.mime);
  put("library", params.library);
  put("space", params.space);
  put("collection", params.collection);
  putList("tag", params.tag);
  put("from", params.from);
  put("to", params.to);
  put("lang", params.lang);
  put("profile", params.profile);
  putList("facets", params.facets);

  const encoded = query.toString();
  return encoded ? `?${encoded}` : "";
}

/**
 * Refuse a blank term locally.
 *
 * The provider answers 400 "A search term (q) is required." for one, and firing it means a failed
 * request on every backspace-to-empty.
 */
function requireQuery(params: SearchRequestParams): void {
  if (!params.q || !params.q.trim()) {
    throw new SearchApiError(400, "A search term (q) is required.");
  }
}

// ── API ───────────────────────────────────────────────────────────────

/** Cross-entity ranked search. */
export async function searchResults(
  token: string,
  params: SearchRequestParams,
  opts: { signal?: AbortSignal } = {},
): Promise<SearchResultResponse> {
  requireQuery(params);
  const res = await fetch(`${API_BASE_URL}/search/results${buildSearchQuery(params)}`, {
    method: "GET",
    headers: authHeaders(token),
    signal: opts.signal,
  });
  return handleResponse<SearchResultResponse>(res);
}

/**
 * Asset-only search.
 *
 * Returns the same envelope as {@link searchResults} — hits, not AssetResponse objects. The
 * server forces `types={asset}` regardless of what the caller sends.
 */
export async function searchAssets(
  token: string,
  params: SearchRequestParams,
  opts: { signal?: AbortSignal } = {},
): Promise<SearchResultResponse> {
  requireQuery(params);
  const res = await fetch(`${API_BASE_URL}/search/assets${buildSearchQuery(params)}`, {
    method: "GET",
    headers: authHeaders(token),
    signal: opts.signal,
  });
  return handleResponse<SearchResultResponse>(res);
}

/**
 * Typeahead suggestions for a partial term.
 *
 * Note this route cannot report that search is down: the Noop provider answers it with an empty
 * list rather than a 503, so an unavailable backend is indistinguishable from "no matches".
 * Availability comes from {@link searchStatus} alone.
 */
export async function searchSuggestions(
  token: string,
  prefix: string,
  opts: { types?: SearchEntityType[]; limit?: number; signal?: AbortSignal } = {},
): Promise<SearchSuggestionResponse[]> {
  if (!prefix.trim()) return [];
  const query = buildSearchQuery({ q: prefix, types: opts.types, limit: opts.limit });
  const res = await fetch(`${API_BASE_URL}/search/suggestions${query}`, {
    method: "GET",
    headers: authHeaders(token),
    signal: opts.signal,
  });
  const body = await handleResponse<SearchSuggestionListResponse>(res);
  // `data` is omitted, not empty, when nothing matched — mapping it directly would throw.
  return body.data ?? [];
}

/**
 * Which backend is bound, whether it can serve queries and what it can do.
 *
 * Answers 200 even when search is unavailable, so asking "should I show a search box?" never
 * fails on that account. It does require READ_SEARCH, so it can still answer 403.
 */
export async function searchStatus(
  token: string,
  opts: { signal?: AbortSignal } = {},
): Promise<SearchStatusResponse> {
  const res = await fetch(`${API_BASE_URL}/search/status`, {
    method: "GET",
    headers: authHeaders(token),
    signal: opts.signal,
  });
  return handleResponse<SearchStatusResponse>(res);
}
