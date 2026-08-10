import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API storage models ───────────────────────
// Mirrors io.metaloom.loom.rest.model.storage.*

/**
 * How close a backend is to full.
 *
 * `UNKNOWN` is not a degraded `OK`. An object store reports no capacity at all, so the
 * question was never answerable — painting it green would be an answer nobody gave.
 */
export type Watermark = "OK" | "WARN" | "CRITICAL" | "UNKNOWN";

/**
 * The kinds of stored content the report groups by.
 *
 * A plain string, not a union the server has to stay inside: a release that adds a kind
 * should show up here as an extra row, not as a client that fails to parse the response.
 */
export type StorageCategoryName = string;

/**
 * The one category that is not an attachment.
 *
 * `ASSET_BINARY` is the original uploaded media, counted out of `asset_location` rather than out of
 * `attachment_binary`. The distinction matters wherever the report's own totals are combined with a
 * category figure — the two sets are disjoint, which is what makes adding them valid.
 */
export const ASSET_BINARY_CATEGORY = "ASSET_BINARY";

export interface StorageCategory {
  category: StorageCategoryName;
  /** How many elements of this kind exist. */
  elements: number;
  /** The sum of those elements' sizes, counting duplicated content once per element. */
  logicalBytes: number;
  /** How many distinct stored objects those elements resolve to. */
  distinctObjects: number;
  /** What those objects occupy. Not summable across categories — one object can belong to two. */
  distinctBytes: number;
}

export interface StorageBackend {
  /** null (or absent) for the deployment's default local storage. */
  poolUuid?: string | null;
  poolName: string;
  kind: string;
  description: string | null;
  // 🔴 Absent, not null, when the backend cannot say: the server omits null fields entirely, so an
  // object store's capacity arrives as `undefined`. Every read of these has to treat the two the
  // same - `x === null` alone lets undefined through into arithmetic and renders NaN.
  freeBytes?: number | null;
  totalBytes?: number | null;
  watermark: Watermark;
  objects: number;
  bytes: number;
  /** Why the backend could not be reached, or absent. A broken pool is reported, not thrown. */
  error?: string | null;
}

export interface StorageThresholds {
  minFreeSpaceBytes: number;
  warnFreeSpaceBytes: number;
  maxUploadSizeBytes: number;
}

export interface StorageReport {
  timestamp: string;
  thresholds: StorageThresholds;
  categories: StorageCategory[];
  backends: StorageBackend[];
  objects: number;
  /** The physical total. NOT the sum of the categories' distinctBytes — see StorageCategory. */
  distinctBytes: number;
  orphanObjects: number;
  orphanBytes: number;
}

export interface StorageBackendList {
  backends: StorageBackend[];
  thresholds: StorageThresholds;
}

function authHeaders(token: string): Record<string, string> {
  return { "Content-Type": "application/json", Authorization: `Bearer ${token}` };
}

/**
 * A typed error, so a caller can tell a 403 from a network failure.
 *
 * The status is carried as a field rather than left in the message: the screen renders a
 * "you may not see this" state for 403 and a "try again" state for everything else, and
 * string-matching on `API error 403` is how that goes wrong quietly later.
 */
export class StorageApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "StorageApiError";
    this.status = status;
  }
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new StorageApiError(res.status, `API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

/** What is stored and how full every backend is. Several aggregate scans; do not poll. */
export async function loadStorageReport(token: string): Promise<StorageReport> {
  const res = await fetch(`${API_BASE_URL}/storage`, { headers: authHeaders(token) });
  return handleResponse<StorageReport>(res);
}

/** Capacity only. Cheap enough to poll, unlike the full report. */
export async function loadStorageBackends(token: string): Promise<StorageBackendList> {
  const res = await fetch(`${API_BASE_URL}/storage/backends`, { headers: authHeaders(token) });
  return handleResponse<StorageBackendList>(res);
}

// ── Presentation helpers (pure, unit-tested) ──────────────────────────────

/**
 * Bytes the content-addressed store saved by not writing the duplicates.
 *
 * Clamped at zero. The two figures come from two queries against a live database, so a
 * write landing between them can make the difference momentarily negative, and "-4 KiB
 * saved" is a worse answer than "none".
 */
export function dedupeSavings(category: StorageCategory): number {
  return Math.max(0, category.logicalBytes - category.distinctBytes);
}

/** The saving as a percentage of what the catalogue claims, or 0 when it claims nothing. */
export function savingsPercent(category: StorageCategory): number {
  if (category.logicalBytes <= 0) return 0;
  return Math.round((dedupeSavings(category) / category.logicalBytes) * 100);
}

/**
 * How full a backend is, 0..1, or null when it cannot say.
 *
 * Null rather than 0, because 0 renders as an empty bar — which reads as "plenty of
 * room" for a bucket whose capacity is simply not a thing that exists.
 */
export function usedFraction(backend: StorageBackend): number | null {
  const { freeBytes, totalBytes } = backend;
  // Both `== null` checks on purpose: the server omits a null field rather than sending it, so an
  // object store's capacity arrives as undefined. Testing only for null lets it through and the bar
  // renders at NaN%.
  if (freeBytes == null || totalBytes == null || totalBytes <= 0) return null;
  return Math.min(1, Math.max(0, (totalBytes - freeBytes) / totalBytes));
}

/** The StatusChip tone for a watermark. UNKNOWN is neutral, never green. */
export function watermarkTone(watermark: Watermark): "green" | "amber" | "red" | "neutral" {
  switch (watermark) {
    case "OK":
      return "green";
    case "WARN":
      return "amber";
    case "CRITICAL":
      return "red";
    default:
      return "neutral";
  }
}

/**
 * Backends worst-first, so whatever needs attention is at the top.
 *
 * `UNKNOWN` sorts last rather than first: it is not a problem, it is a backend that has
 * no capacity to have a problem with, and burying the one real warning under a list of
 * buckets would defeat the ordering.
 */
export function sortBackends(backends: StorageBackend[]): StorageBackend[] {
  const rank: Record<Watermark, number> = { CRITICAL: 0, WARN: 1, OK: 2, UNKNOWN: 3 };
  return [...backends].sort((a, b) => {
    const byWatermark = rank[a.watermark] - rank[b.watermark];
    if (byWatermark !== 0) return byWatermark;
    // The default storage first among equals: it is the one every install has.
    if (a.poolUuid == null) return -1;
    if (b.poolUuid == null) return 1;
    return a.poolName.localeCompare(b.poolName);
  });
}

/**
 * The whole-deployment totals, from figures that can legitimately be added together.
 *
 * Summing the categories' `distinctBytes` would double-count, because one stored object can belong
 * to two of them. The report's own `objects`/`distinctBytes` are the attachment totals and carry no
 * such overlap — but they also exclude the media files, which live in a different table and are
 * usually the largest row on the screen. So the honest total is those two disjoint sets added:
 * attachments as the report counted them, plus the `ASSET_BINARY` row.
 *
 * The saving then falls out as claimed − on disk, and picks up sharing *between* categories, which
 * per-category arithmetic cannot see. A face crop copied into somebody's gallery is exactly that
 * case, and summing per-category savings reports it as zero.
 */
export function storageTotals(report: StorageReport): {
  objects: number;
  onDiskBytes: number;
  claimedBytes: number;
  savedBytes: number;
} {
  const assets = report.categories.find(category => category.category === ASSET_BINARY_CATEGORY);
  const objects = report.objects + (assets?.distinctObjects ?? 0);
  const onDiskBytes = report.distinctBytes + (assets?.distinctBytes ?? 0);
  const claimedBytes = report.categories.reduce((sum, category) => sum + category.logicalBytes, 0);
  return { objects, onDiskBytes, claimedBytes, savedBytes: Math.max(0, claimedBytes - onDiskBytes) };
}

/**
 * Categories largest-first by what they actually occupy.
 *
 * By distinct bytes, not logical: the question the screen answers is "what is filling my
 * disk", and a category of 9000 face crops that all deduplicate onto one object is not
 * the answer however large its logical total looks.
 */
export function sortCategories(categories: StorageCategory[]): StorageCategory[] {
  return [...categories].sort((a, b) => b.distinctBytes - a.distinctBytes || a.category.localeCompare(b.category));
}
