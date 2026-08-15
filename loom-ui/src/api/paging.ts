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

/**
 * The columns a list route can be ordered by, as spelled in `?sort=`.
 *
 * Mirrors `LoomSortKey`. Only the four the UI offers are listed; the server accepts more, but a
 * control that offers a column half the types do not have would show a 400 as often as a result.
 *
 * `name` is mapped per type server-side — an asset's display name is its `filename`.
 */
export type ListSortKey = "name" | "created" | "edited" | "uuid";

export type ListSortDirection = "asc" | "desc";

/**
 * One LHS filter term, e.g. `{ key: "creator", value: "<uuid>" }` → `creator[eq]=<uuid>`.
 *
 * Only equality is expressible: the server's filter grammar has no `contains`, so these narrow a
 * listing but cannot substring-search it. That is what `/search/*` is for.
 */
export interface ListFilter {
  key: string;
  value: string;
}

/** Paging, sorting and filtering arguments accepted by the list clients. */
export interface PagingParams {
  limit?: number;
  /** Seek cursor — the UUID to continue *after*, NOT a numeric offset. */
  from?: string;
  /**
   * Column to order by. Omit for the server default, which is the uuid — and since keys are
   * UUIDv7, that is insertion order.
   */
  sort?: ListSortKey;
  /** Order direction. The server defaults to ascending. */
  dir?: ListSortDirection;
  /**
   * Filter terms, ANDed together.
   *
   * Must stay stable across the pages of one listing: the cursor points into a filtered ordering,
   * so changing a term mid-scroll has to restart from the first page.
   */
  filters?: ListFilter[];
}

/**
 * Serialize filter terms into the server's LHS grammar.
 *
 * Exported for the unit tests. Terms are joined with `,` — `LHSFilterParserImpl` splits on it —
 * and the whole string is encoded once as a single `filter` value.
 */
export function filterExpression(filters?: ListFilter[]): string {
  if (!filters || filters.length === 0) return "";
  return filters
    .filter(f => f.key && f.value !== undefined && f.value !== "")
    .map(f => `${f.key}[eq]=${f.value}`)
    .join(",");
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
  if (paging.sort) params.set("sort", paging.sort);
  // Only sent alongside a sort. On its own it would reverse the default uuid order, which is a
  // real thing the server supports but not something any control here asks for.
  if (paging.dir && paging.sort) params.set("dir", paging.dir);
  const filter = filterExpression(paging.filters);
  if (filter) params.set("filter", filter);
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
