import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UploadAbortedError, UploadHandle, UploadResult } from "../../api/assets";
import {
  BatchOutcome,
  UploadSummary,
  cancel,
  clearFinished,
  enqueue,
  getSummary,
  reset,
  retry,
  setUploadToken,
  setUploaderForTesting,
  subscribe,
  subscribeBatch,
} from "./uploadQueue";

/**
 * A stand-in transport. Each call is parked so the test can decide when — and how — it finishes,
 * which is what makes concurrency and cancellation observable without a real XMLHttpRequest.
 */
interface FakeCall {
  file: File;
  libraryUuid: string;
  poolUuid?: string;
  origin?: string;
  emitProgress: (loaded: number) => void;
  resolve: (result: UploadResult) => void;
  reject: (err: unknown) => void;
  aborted: boolean;
}

let calls: FakeCall[] = [];

function fakeUploader(
  _token: string,
  file: File,
  libraryUuid: string,
  opts?: { origin?: string; poolUuid?: string; onProgress?: (p: { loaded: number; total: number }) => void }
): UploadHandle {
  let resolve!: (r: UploadResult) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<UploadResult>((res, rej) => { resolve = res; reject = rej; });

  const call: FakeCall = {
    file,
    libraryUuid,
    poolUuid: opts?.poolUuid,
    origin: opts?.origin,
    emitProgress: (loaded) => opts?.onProgress?.({ loaded, total: file.size }),
    resolve,
    reject,
    aborted: false,
  };
  calls.push(call);

  return {
    promise,
    abort: () => {
      call.aborted = true;
      reject(new UploadAbortedError());
    },
  };
}

function fileOf(name: string, size: number): File {
  return new File(["x".repeat(size)], name);
}

function ok(created = true): UploadResult {
  return { asset: { uuid: `asset-${Math.random()}` } as UploadResult["asset"], created };
}

/** Let the queue's promise callbacks run. */
const settle = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  calls = [];
  reset();
  setUploadToken("test-token");
  setUploaderForTesting(fakeUploader as never);
});

afterEach(() => {
  reset();
  vi.restoreAllMocks();
});

describe("uploadQueue", () => {
  it("starts uploads immediately and reports them as active", async () => {
    enqueue([fileOf("a.jpg", 10)], { libraryUuid: "lib-1", libraryName: "Main" });

    expect(calls).toHaveLength(1);
    expect(calls[0].libraryUuid).toBe("lib-1");
    const summary = getSummary();
    expect(summary.isActive).toBe(true);
    expect(summary.activeCount).toBe(1);
    expect(summary.items[0].status).toBe("uploading");
  });

  it("caps concurrency at three and starts the rest as slots free up", async () => {
    enqueue([1, 2, 3, 4, 5].map((n) => fileOf(`f${n}.bin`, 10)), { libraryUuid: "lib-1" });

    // Five queued, only three transports created.
    expect(calls).toHaveLength(3);
    expect(getSummary().items.filter((i) => i.status === "uploading")).toHaveLength(3);
    expect(getSummary().items.filter((i) => i.status === "queued")).toHaveLength(2);

    calls[0].resolve(ok());
    await settle();

    // Finishing one admits exactly one more, keeping three in flight.
    expect(calls).toHaveLength(4);
    expect(getSummary().items.filter((i) => i.status === "uploading")).toHaveLength(3);
  });

  it("forwards the pool override and origin to the transport", () => {
    enqueue([fileOf("a.jpg", 10)], {
      libraryUuid: "lib-1",
      poolUuid: "pool-7",
      poolName: "Archive S3",
      origin: "import",
    });

    expect(calls[0].poolUuid).toBe("pool-7");
    expect(calls[0].origin).toBe("import");
  });

  it("omits the pool when none was chosen, so the library decides", () => {
    enqueue([fileOf("a.jpg", 10)], { libraryUuid: "lib-1" });
    expect(calls[0].poolUuid).toBeUndefined();
  });

  it("tracks byte progress and weights the aggregate by file size", () => {
    // One large file and one small one: the large file's progress must dominate.
    enqueue([fileOf("big.bin", 900), fileOf("small.bin", 100)], { libraryUuid: "lib-1" });

    calls[0].emitProgress(450);
    expect(getSummary().percent).toBe(45);

    calls[1].emitProgress(100);
    expect(getSummary().percent).toBe(55);
  });

  it("marks a 200 response as a duplicate rather than a new upload", async () => {
    enqueue([fileOf("dupe.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].resolve(ok(false));
    await settle();

    const summary = getSummary();
    expect(summary.items[0].status).toBe("duplicate");
    expect(summary.duplicateCount).toBe(1);
    expect(summary.doneCount).toBe(0);
  });

  it("records a failure with its message and keeps the item for retry", async () => {
    enqueue([fileOf("bad.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].reject(new Error("API error 507: disk full"));
    await settle();

    const item = getSummary().items[0];
    expect(item.status).toBe("error");
    expect(item.error).toContain("disk full");
    expect(getSummary().errorCount).toBe(1);
  });

  it("retries a failed item as a fresh transfer", async () => {
    enqueue([fileOf("retry.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].reject(new Error("boom"));
    await settle();

    retry(getSummary().items[0].id);
    expect(calls).toHaveLength(2);
    expect(getSummary().items[0].status).toBe("uploading");
    expect(getSummary().items[0].error).toBeUndefined();
  });

  it("aborts an in-flight transfer on cancel and does not count it as a failure", async () => {
    enqueue([fileOf("cancel.jpg", 10)], { libraryUuid: "lib-1" });
    cancel(getSummary().items[0].id);
    await settle();

    expect(calls[0].aborted).toBe(true);
    expect(getSummary().items[0].status).toBe("cancelled");
    expect(getSummary().errorCount).toBe(0);
  });

  it("cancels a still-queued item without ever starting a transfer", async () => {
    enqueue([1, 2, 3, 4].map((n) => fileOf(`f${n}.bin`, 10)), { libraryUuid: "lib-1" });
    const queued = getSummary().items.find((i) => i.status === "queued")!;

    cancel(queued.id);
    await settle();

    expect(getSummary().items.find((i) => i.id === queued.id)!.status).toBe("cancelled");
    // Still only the three that were already running.
    expect(calls).toHaveLength(3);
  });

  it("notifies subscribers on every change and stops after unsubscribe", async () => {
    const seen: UploadSummary[] = [];
    const unsubscribe = subscribe((s) => seen.push(s));

    // Subscribing emits the current state immediately.
    expect(seen).toHaveLength(1);

    enqueue([fileOf("a.jpg", 10)], { libraryUuid: "lib-1" });
    const afterEnqueue = seen.length;
    expect(afterEnqueue).toBeGreaterThan(1);

    unsubscribe();
    calls[0].resolve(ok());
    await settle();
    expect(seen).toHaveLength(afterEnqueue);
  });

  it("reports a batch exactly once, when the queue drains", async () => {
    const outcomes: BatchOutcome[] = [];
    subscribeBatch((o) => outcomes.push(o));

    enqueue([fileOf("a.jpg", 10), fileOf("b.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].resolve(ok());
    await settle();

    // One still running — nothing reported yet.
    expect(outcomes).toHaveLength(0);

    calls[1].resolve(ok(false));
    await settle();

    expect(outcomes).toHaveLength(1);
    expect(outcomes[0]).toEqual({ uploaded: 1, duplicates: 1, failed: 0, cancelled: 0 });
  });

  it("counts failures and cancellations separately in the batch outcome", async () => {
    const outcomes: BatchOutcome[] = [];
    subscribeBatch((o) => outcomes.push(o));

    enqueue([fileOf("a.jpg", 10), fileOf("b.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].reject(new Error("nope"));
    cancel(getSummary().items[1].id);
    await settle();

    expect(outcomes).toHaveLength(1);
    expect(outcomes[0].failed).toBe(1);
    expect(outcomes[0].cancelled).toBe(1);
  });

  it("starts a new batch after the previous one settled", async () => {
    const outcomes: BatchOutcome[] = [];
    subscribeBatch((o) => outcomes.push(o));

    enqueue([fileOf("a.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].resolve(ok());
    await settle();

    enqueue([fileOf("b.jpg", 10)], { libraryUuid: "lib-1" });
    calls[1].resolve(ok());
    await settle();

    expect(outcomes).toHaveLength(2);
    // The second report counts only the second batch, not a running total.
    expect(outcomes[1]).toEqual({ uploaded: 1, duplicates: 0, failed: 0, cancelled: 0 });
  });

  it("fails an upload outright when there is no session token", async () => {
    setUploadToken(null);
    enqueue([fileOf("a.jpg", 10)], { libraryUuid: "lib-1" });
    await settle();

    expect(calls).toHaveLength(0);
    expect(getSummary().items[0].status).toBe("error");
    expect(getSummary().items[0].error).toBe("Not authenticated");
  });

  it("clearFinished drops settled items but leaves transfers running", async () => {
    enqueue([fileOf("done.jpg", 10), fileOf("running.jpg", 10)], { libraryUuid: "lib-1" });
    calls[0].resolve(ok());
    await settle();

    clearFinished();

    const items = getSummary().items;
    expect(items).toHaveLength(1);
    expect(items[0].fileName).toBe("running.jpg");
    expect(items[0].status).toBe("uploading");
  });
});
