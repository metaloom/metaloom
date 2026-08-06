import type { PagingInfo, PagingParams } from "../api/paging";

/**
 * Pure paging arithmetic, extracted so it can be unit-tested in the node-env vitest setup —
 * there is no renderer in this repo, so {@link ../hooks/usePagedList} itself is covered by the
 * mocked Playwright specs instead (LOOM_UI.md §8.1).
 */

/** Default page size. Well above the server's own default of 25, still bounded. */
export const PAGE_SIZE = 100;

/**
 * Append a freshly fetched page to the rows already held.
 *
 * Deduplicates by uuid: keyset paging seeks *from* an element, and not every route agrees on
 * whether that element is excluded, so the boundary row can legitimately arrive twice. Order is
 * preserved and the first occurrence wins.
 */
export function mergePage<T>(existing: T[], incoming: T[], keyOf: (item: T) => string): T[] {
  const seen = new Set(existing.map(keyOf));
  const merged = [...existing];
  for (const item of incoming) {
    const key = keyOf(item);
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(item);
  }
  return merged;
}

/**
 * Is there another page we can actually fetch?
 *
 * Two conditions, and both matter. There must be more rows — `totalCount` is authoritative when
 * the server sends it, otherwise fall back to "the last page came back full", since a short page
 * means the end and an exactly-full one is ambiguous. And there must be a **cursor** to seek
 * from: without `lastUuid` the next request would repeat page one, so offering a "load more"
 * button would be offering a button that cannot do anything.
 */
export function hasMorePages(
  loadedCount: number,
  metainfo: PagingInfo | undefined,
  lastPageLength: number,
  pageSize: number = PAGE_SIZE,
): boolean {
  if (!metainfo?.lastUuid) return false;
  if (metainfo.totalCount !== undefined) return loadedCount < metainfo.totalCount;
  return lastPageLength >= pageSize;
}

/**
 * Paging arguments for the next page, or `null` when the server gave us no cursor to seek from.
 */
export function nextPaging(metainfo: PagingInfo | undefined, pageSize: number = PAGE_SIZE): PagingParams | null {
  if (!metainfo?.lastUuid) return null;
  return { limit: pageSize, from: metainfo.lastUuid };
}

/**
 * Should the "showing X of Y" line be rendered?
 *
 * Only when the two genuinely differ — repeating "showing 12 of 12" is noise.
 */
export function isTruncated(loadedCount: number, metainfo: PagingInfo | undefined): boolean {
  return metainfo?.totalCount !== undefined && loadedCount < metainfo.totalCount;
}

/**
 * The number to render as a collection's size.
 *
 * Prefers the server's total over the length of what happens to be in memory — reporting the page
 * length as the collection size is the defect this module exists to fix.
 */
export function displayCount(loadedCount: number, metainfo: PagingInfo | undefined): number {
  return metainfo?.totalCount ?? loadedCount;
}
