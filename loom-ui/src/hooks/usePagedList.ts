import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ListResponse, PagingInfo, PagingParams } from "../api/paging";
import { PAGE_SIZE, displayCount, hasMorePages, isTruncated, mergePage, nextPaging } from "./pagedList";

/** What a loader hands back: the rows for this page, already in the view's own shape. */
export interface Page<T> {
  items: T[];
  metainfo?: PagingInfo;
}

export interface PagedList<T> {
  /** Every row loaded so far, across all fetched pages. */
  items: T[];
  /**
   * Apply a local mutation — a create, an edit, an optimistic delete.
   *
   * `totalCount` moves with the length change, so "showing 24 of 299" stays true after a delete
   * without a round trip. Do not use this to append a fetched page; that is `loadMore`'s job.
   */
  setItems: (update: T[] | ((prev: T[]) => T[])) => void;
  /** What to render as the collection size — the server total when known, not `items.length`. */
  totalCount: number;
  /** True while the collection is larger than what has been loaded. */
  truncated: boolean;
  loading: boolean;
  /** True while a `loadMore()` is in flight, so the button can disable itself. */
  loadingMore: boolean;
  error: unknown;
  hasMore: boolean;
  loadMore: () => void;
  /** Discard everything and re-fetch the first page. */
  reload: () => void;
}

/**
 * Load a Loom collection page by page.
 *
 * The server caps every list route at 25 rows by default and the UI historically issued bare
 * `fetch`es against them, so a "collection" was in fact a silent first page. This hook makes the
 * truncation visible (`totalCount`, `truncated`) and seekable (`loadMore`).
 *
 * `loader` maps the response into the view's own item shape and must be **stable** — build it with
 * `useMemo`/`useCallback` at the call site, or the effect re-fires on every render. Pass `null`
 * while there is no token.
 */
export function usePagedList<T>(
  loader: ((paging: PagingParams) => Promise<Page<T>>) | null,
  keyOf: (item: T) => string,
  pageSize: number = PAGE_SIZE,
): PagedList<T> {
  const [items, setRawItems] = useState<T[]>([]);
  const [metainfo, setMetainfo] = useState<PagingInfo | undefined>(undefined);
  const [lastPageLength, setLastPageLength] = useState(0);
  const [loading, setLoading] = useState(Boolean(loader));
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [reloadToken, setReloadToken] = useState(0);

  // Guards a late response from overwriting rows fetched by a newer reload.
  const generation = useRef(0);

  useEffect(() => {
    if (!loader) {
      setRawItems([]);
      setMetainfo(undefined);
      setLoading(false);
      return;
    }
    const mine = ++generation.current;
    setLoading(true);
    setError(null);
    loader({ limit: pageSize })
      .then(page => {
        if (generation.current !== mine) return;
        setRawItems(page.items);
        setMetainfo(page.metainfo);
        setLastPageLength(page.items.length);
      })
      .catch(e => {
        if (generation.current !== mine) return;
        setError(e);
        setRawItems([]);
        setMetainfo(undefined);
        setLastPageLength(0);
      })
      .finally(() => {
        if (generation.current === mine) setLoading(false);
      });
  }, [loader, pageSize, reloadToken]);

  const loadMore = useCallback(() => {
    if (!loader || loadingMore) return;
    const paging = nextPaging(metainfo, pageSize);
    // No cursor means the server cannot tell us where to seek from; leave the rows as they are
    // rather than re-requesting page one and appending duplicates.
    if (!paging) return;
    const mine = generation.current;
    setLoadingMore(true);
    loader(paging)
      .then(page => {
        if (generation.current !== mine) return;
        setRawItems(prev => mergePage(prev, page.items, keyOf));
        setMetainfo(page.metainfo);
        setLastPageLength(page.items.length);
      })
      .catch(e => {
        if (generation.current === mine) setError(e);
      })
      .finally(() => {
        if (generation.current === mine) setLoadingMore(false);
      });
  }, [loader, loadingMore, metainfo, pageSize, keyOf]);

  const setItems = useCallback((update: T[] | ((prev: T[]) => T[])) => {
    setRawItems(prev => {
      const next = typeof update === "function" ? (update as (p: T[]) => T[])(prev) : update;
      const delta = next.length - prev.length;
      if (delta !== 0) {
        setMetainfo(info =>
          info?.totalCount === undefined ? info : { ...info, totalCount: Math.max(0, info.totalCount + delta) },
        );
      }
      return next;
    });
  }, []);

  const reload = useCallback(() => setReloadToken(n => n + 1), []);

  return useMemo(
    () => ({
      items,
      setItems,
      totalCount: displayCount(items.length, metainfo),
      truncated: isTruncated(items.length, metainfo),
      loading,
      loadingMore,
      error,
      hasMore: hasMorePages(items.length, metainfo, lastPageLength, pageSize),
      loadMore,
      reload,
    }),
    [items, setItems, metainfo, loading, loadingMore, error, lastPageLength, pageSize, loadMore, reload],
  );
}

/** Convenience for the common case: a `{ data, _metainfo }` response mapped row by row. */
export function pageFrom<R, T>(resp: ListResponse<R>, map: (row: R) => T): Page<T> {
  return { items: (resp.data ?? []).map(map), metainfo: resp._metainfo };
}
