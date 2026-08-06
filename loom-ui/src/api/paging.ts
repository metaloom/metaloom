/**
 * Keyset paging for the Loom list routes.
 *
 * Every collection endpoint answers with `AbstractListResponse` — `{ data, _metainfo }` — and
 * accepts `?limit=` / `?from=`. `limit` defaults to **25** server-side
 * (`QueryParameterKey.LIMIT`), so a bare `fetch('/assets')` silently returns a first page, not
 * the collection.
 */

/**
 * The `_metainfo` block of a list response, mirroring `PagingInfo.java`.
 *
 * These three fields are the whole wire contract — there is no `currentPage`, `pageCount` or
 * `totalElements`, whatever older UI code and task notes claim.
 */
export interface PagingInfo {
  /** UUID of the last element on this page — feed it back as `from` to seek the next one. */
  lastUuid?: string;
  /** Page size the server actually applied. */
  perPage?: number;
  /** Total elements matching the query across all pages. */
  totalCount?: number;
}

/** Paging arguments accepted by the list clients. */
export interface PagingParams {
  limit?: number;
  /** Seek cursor — the UUID to continue *after*, NOT a numeric offset. */
  from?: string;
}

/** A list response envelope. */
export interface ListResponse<T> {
  data: T[];
  _metainfo?: PagingInfo;
}

/**
 * Serialize paging arguments into a query string.
 *
 * Returns `""` when nothing is set, so callers can append it unconditionally. Unset keys are
 * omitted entirely rather than sent empty — the server parses `?limit=` as a value and fails.
 */
export function pagingQuery(paging?: PagingParams): string {
  if (!paging) return "";
  const params = new URLSearchParams();
  if (paging.limit !== undefined) params.set("limit", String(paging.limit));
  if (paging.from !== undefined && paging.from !== "") params.set("from", paging.from);
  const query = params.toString();
  return query ? `?${query}` : "";
}

/**
 * Append paging arguments to a path that may already carry a query string.
 *
 * Use this instead of `pagingQuery` when the route has its own parameters (`?scope=`, `?ref=`),
 * so the `?` does not appear twice.
 */
export function withPaging(url: string, paging?: PagingParams): string {
  const query = pagingQuery(paging);
  if (!query) return url;
  return url.includes("?") ? `${url}&${query.slice(1)}` : `${url}${query}`;
}
