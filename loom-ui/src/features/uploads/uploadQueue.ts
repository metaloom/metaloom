import {
  UploadAbortedError,
  UploadHandle,
  UploadResult,
  uploadAssetWithProgress,
} from "../../api/assets";

/**
 * A module-level upload queue.
 *
 * Uploads must keep running while the user moves around the app, so the transfers cannot live in a
 * React component — unmounting the upload screen would abort them. This module owns the state and
 * the in-flight `XMLHttpRequest`s; React subscribes to it, the same shape the pipeline event socket
 * uses (`src/api/pipelineEvents.ts`).
 *
 * A full page reload still kills everything: the bytes only exist in the page's memory and the
 * upload endpoint is single-shot with no resume (see `spec/features/rest/REST_BINARY_HANDLING.md`).
 * `UploadProvider` therefore warns via `beforeunload` while a batch is active.
 */

export type UploadStatus =
  | "queued"
  | "uploading"
  /**
   * Held back by the user: it will not start until it is resumed. Only a waiting item can be
   * paused — a transfer already in flight runs to completion, because the endpoint is single-shot
   * and stopping it would throw away every byte sent so far (`pause` below).
   */
  | "paused"
  | "done"
  /** Uploaded fine, but the server already held these bytes (HTTP 200 rather than 201). */
  | "duplicate"
  | "error"
  | "cancelled";

export interface UploadTarget {
  libraryUuid: string;
  libraryName?: string;
  /** Explicit pool override. Absent means the library's own pool decides. */
  poolUuid?: string;
  poolName?: string;
  origin?: string;
}

export interface UploadItem extends UploadTarget {
  id: string;
  file: File;
  fileName: string;
  size: number;
  status: UploadStatus;
  /** Bytes sent so far. */
  loaded: number;
  assetUuid?: string;
  error?: string;
}

/** Aggregate view of the queue, recomputed on every change. */
export interface UploadSummary {
  items: UploadItem[];
  /** Queued + uploading. Paused items are deliberately **not** active. */
  activeCount: number;
  doneCount: number;
  duplicateCount: number;
  errorCount: number;
  pausedCount: number;
  /** 0..100 across the active batch, weighted by file size. */
  percent: number;
  isActive: boolean;
}

/** Emitted once each time the queue drains, so the UI can raise a single toast per batch. */
export interface BatchOutcome {
  uploaded: number;
  duplicates: number;
  failed: number;
  cancelled: number;
}

type Listener = (summary: UploadSummary) => void;
type BatchListener = (outcome: BatchOutcome) => void;

/** How many transfers run at once by default. Enough to keep a link busy without starving the UI. */
export const DEFAULT_CONCURRENCY = 3;
/** Below 1 nothing would ever start; above 8 the browser queues the sockets anyway. */
export const MIN_CONCURRENCY = 1;
export const MAX_CONCURRENCY = 8;

/**
 * Resolve `VITE_UPLOAD_CONCURRENCY` into a usable limit.
 *
 * Three parallel transfers suit a fast link; a saturated uplink or an S3-backed pool with
 * per-request latency wants fewer. Anything unparseable or outside {@link MIN_CONCURRENCY}..
 * {@link MAX_CONCURRENCY} falls back to the default rather than being clamped silently — a
 * build-time typo should behave like the setting was never made, and `0` would wedge the queue with
 * nothing ever starting.
 */
export function clampConcurrency(raw: unknown): number {
  const parsed = typeof raw === "number" ? raw : parseInt(String(raw ?? ""), 10);
  if (!Number.isFinite(parsed)) return DEFAULT_CONCURRENCY;
  const value = Math.trunc(parsed);
  if (value < MIN_CONCURRENCY || value > MAX_CONCURRENCY) return DEFAULT_CONCURRENCY;
  return value;
}

/** Build-time setting, so this is resolved once at module load. */
const MAX_CONCURRENT = clampConcurrency(import.meta.env.VITE_UPLOAD_CONCURRENCY);

const items: UploadItem[] = [];
const handles = new Map<string, UploadHandle>();
const listeners = new Set<Listener>();
const batchListeners = new Set<BatchListener>();

let idSeq = 0;
let token: string | null = null;
/** Swappable for tests, so the queue can be exercised without XMLHttpRequest. */
let uploader = uploadAssetWithProgress;
/** Counters for the batch currently draining; reset once the queue goes idle. */
let batch: BatchOutcome = { uploaded: 0, duplicates: 0, failed: 0, cancelled: 0 };

function isTerminal(status: UploadStatus): boolean {
  return status === "done" || status === "duplicate" || status === "error" || status === "cancelled";
}

export function summarize(list: UploadItem[]): UploadSummary {
  const active = list.filter((i) => i.status === "queued" || i.status === "uploading");
  // Weighted by size so one big file cannot be masked by many small ones. Finished items count as
  // fully sent, which keeps the bar monotonic as items complete.
  const totalBytes = list.reduce((sum, i) => sum + i.size, 0);
  const sentBytes = list.reduce((sum, i) => sum + (isTerminal(i.status) ? i.size : i.loaded), 0);
  return {
    items: list,
    activeCount: active.length,
    doneCount: list.filter((i) => i.status === "done").length,
    duplicateCount: list.filter((i) => i.status === "duplicate").length,
    errorCount: list.filter((i) => i.status === "error").length,
    pausedCount: list.filter((i) => i.status === "paused").length,
    percent: totalBytes === 0 ? 0 : Math.min(100, Math.round((sentBytes / totalBytes) * 100)),
    isActive: active.length > 0,
  };
}

function emit(): void {
  const summary = summarize([...items]);
  listeners.forEach((l) => l(summary));
}

/**
 * Start as many queued items as the concurrency limit allows, then report the batch when nothing is
 * left running.
 */
function pump(): void {
  const running = items.filter((i) => i.status === "uploading").length;
  const queued = items.filter((i) => i.status === "queued");

  for (const item of queued.slice(0, Math.max(0, MAX_CONCURRENT - running))) {
    start(item);
  }

  // A paused item counts as busy, so the batch stays open and no toast is raised while the user is
  // holding part of it back. Resuming finishes the same batch and reports it once; cancelling the
  // paused remainder closes it just as well.
  const stillBusy = items.some(
    (i) => i.status === "queued" || i.status === "uploading" || i.status === "paused"
  );
  const sawAnything = batch.uploaded + batch.duplicates + batch.failed + batch.cancelled > 0;
  if (!stillBusy && sawAnything) {
    const outcome = batch;
    batch = { uploaded: 0, duplicates: 0, failed: 0, cancelled: 0 };
    batchListeners.forEach((l) => l(outcome));
  }
}

function start(item: UploadItem): void {
  if (!token) {
    // No session, so nothing can be sent. Fail loudly rather than sitting queued forever.
    item.status = "error";
    item.error = "Not authenticated";
    batch.failed += 1;
    emit();
    return;
  }

  item.status = "uploading";
  item.loaded = 0;
  emit();

  const handle = uploader(token, item.file, item.libraryUuid, {
    origin: item.origin,
    poolUuid: item.poolUuid,
    onProgress: ({ loaded }) => {
      item.loaded = loaded;
      emit();
    },
  });
  handles.set(item.id, handle);

  handle.promise
    .then((result: UploadResult) => {
      item.status = result.created ? "done" : "duplicate";
      item.assetUuid = result.asset?.uuid;
      item.loaded = item.size;
      if (result.created) batch.uploaded += 1;
      else batch.duplicates += 1;
    })
    .catch((err: unknown) => {
      if (err instanceof UploadAbortedError) {
        item.status = "cancelled";
        batch.cancelled += 1;
      } else {
        item.status = "error";
        item.error = err instanceof Error ? err.message : String(err);
        batch.failed += 1;
      }
    })
    .finally(() => {
      handles.delete(item.id);
      emit();
      pump();
    });
}

// ── Public API ────────────────────────────────────────────────────────

/**
 * Hand the queue the bearer token to upload with. Called by `UploadProvider` whenever the session
 * changes; a logout clears it and anything still queued fails rather than retrying blind.
 */
export function setUploadToken(next: string | null): void {
  token = next;
}

/** Swap the transport. Test seam — production code never calls this. */
export function setUploaderForTesting(next: typeof uploadAssetWithProgress): void {
  uploader = next;
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  listener(summarize([...items]));
  return () => listeners.delete(listener);
}

/** Notified once per drained batch. Separate from {@link subscribe} because it must not re-fire on every byte. */
export function subscribeBatch(listener: BatchListener): () => void {
  batchListeners.add(listener);
  return () => batchListeners.delete(listener);
}

export function getSummary(): UploadSummary {
  return summarize([...items]);
}

/** Add files to the queue and begin uploading. Returns the ids of the newly queued items. */
export function enqueue(files: File[], target: UploadTarget): string[] {
  const added = files.map((file) => {
    const item: UploadItem = {
      ...target,
      id: `upload-${++idSeq}`,
      file,
      fileName: file.name,
      size: file.size,
      status: "queued",
      loaded: 0,
    };
    items.push(item);
    return item.id;
  });
  emit();
  pump();
  return added;
}

/** Cancel one item, whether it is already transferring or still waiting. */
export function cancel(id: string): void {
  const item = items.find((i) => i.id === id);
  if (!item) return;
  const handle = handles.get(id);
  if (handle) {
    // The abort callback moves the item to "cancelled" and pumps the queue.
    handle.abort();
    return;
  }
  if (item.status === "queued" || item.status === "paused") {
    item.status = "cancelled";
    batch.cancelled += 1;
    emit();
    pump();
  }
}

export function cancelAll(): void {
  items.filter((i) => !isTerminal(i.status)).forEach((i) => cancel(i.id));
}

/**
 * Hold a waiting item back. `pump()` skips "paused", so the slot goes to the next queued file
 * instead.
 *
 * **A transfer already in flight cannot be paused** and is left alone: `XMLHttpRequest.send()` hands
 * the whole body to the browser, so the only way to stop it is `abort()`, which discards everything
 * sent so far — that is `cancel`, under an honest name. Pausing therefore takes effect at the file
 * boundary. Chunked upload would move that boundary to the chunk; see
 * `spec/tasks/LOOM_UI_UPLOAD_TASKS.md` Task 5.
 */
export function pause(id: string): void {
  const item = items.find((i) => i.id === id);
  if (!item || item.status !== "queued") return;
  item.status = "paused";
  emit();
}

/** Put a paused item back in line. It keeps its place behind whatever is already queued. */
export function resume(id: string): void {
  const item = items.find((i) => i.id === id);
  if (!item || item.status !== "paused") return;
  item.status = "queued";
  emit();
  pump();
}

/** Hold the whole queue at the next file boundary; anything already transferring still finishes. */
export function pauseAll(): void {
  const held = items.filter((i) => i.status === "queued");
  if (held.length === 0) return;
  held.forEach((i) => { i.status = "paused"; });
  emit();
}

export function resumeAll(): void {
  const held = items.filter((i) => i.status === "paused");
  if (held.length === 0) return;
  held.forEach((i) => { i.status = "queued"; });
  emit();
  pump();
}

/** Put a failed or cancelled item back in the queue. */
export function retry(id: string): void {
  const item = items.find((i) => i.id === id);
  if (!item || !isTerminal(item.status)) return;
  item.status = "queued";
  item.loaded = 0;
  item.error = undefined;
  emit();
  pump();
}

export function retryFailed(): void {
  items.filter((i) => i.status === "error" || i.status === "cancelled").forEach((i) => retry(i.id));
}

/** Drop everything that finished, leaving in-flight work alone. */
export function clearFinished(): void {
  for (let i = items.length - 1; i >= 0; i--) {
    if (isTerminal(items[i].status)) items.splice(i, 1);
  }
  emit();
}

/** Wipe the queue, aborting anything in flight. Used on logout. */
export function reset(): void {
  handles.forEach((h) => h.abort());
  handles.clear();
  items.length = 0;
  batch = { uploaded: 0, duplicates: 0, failed: 0, cancelled: 0 };
  emit();
}
