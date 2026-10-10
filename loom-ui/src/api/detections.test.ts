import { afterEach, describe, expect, it, vi } from "vitest";
import {
  bulkReviewDetections, confirmDetection, deleteDetection, fetchDetectionCrop, listAssetDetections,
  listDetections, rejectDetection, updateDetection,
} from "./detections";
import { deletePerson, deletePersonImage } from "./persons";
import { deleteCluster, listClusters } from "./clusters";
import { deleteCollection } from "./collections";
import { deleteTag } from "./tags";
import { API_BASE_URL } from "./config";
import { SESSION_EXPIRED_EVENT, isApiError } from "./http";

/**
 * The detection client had no test at all, and it was the one module in `src/api` that still
 * interpolated raw uuids into its paths. The delete routes of the face/person/tag/collection
 * clients shared a second defect: each inlined its own `throw new Error(...)`, so a 401 on a delete
 * never reached the session-expired listener and a failed delete lost its trace id.
 */

const TOKEN = "test-token";

function stubFetch(init: { ok?: boolean; status?: number; body?: unknown; text?: string; headers?: Record<string, string> } = {}) {
  const status = init.status ?? 200;
  const fetchMock = vi.fn().mockResolvedValue({
    ok: init.ok ?? (status >= 200 && status < 300),
    status,
    url: "",
    headers: new Headers(init.headers ?? {}),
    json: async () => init.body ?? {},
    text: async () => init.text ?? "",
    blob: async () => new Blob(["x"]),
  } as unknown as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** `window` stand-in, so the session-expired dispatch in `http.ts` has somewhere to go. */
function listenForSessionExpiry() {
  const target = new EventTarget();
  const listener = vi.fn();
  target.addEventListener(SESSION_EXPIRED_EVENT, listener);
  vi.stubGlobal("window", target);
  return listener;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("detections API client", () => {
  it("encodes the asset and detection uuids into every per-detection path", async () => {
    const fetchMock = stubFetch({ body: {} });

    await listAssetDetections(TOKEN, "a b/c");
    await updateDetection(TOKEN, "a b/c", "d/1", { confidence: 1 });
    await confirmDetection(TOKEN, "a b/c", "d/1");
    await rejectDetection(TOKEN, "a b/c", "d/1");
    await bulkReviewDetections(TOKEN, "a b/c", { reviews: [] });
    stubFetch({ status: 204 });
    await deleteDetection(TOKEN, "a b/c", "d/1");

    const urls = fetchMock.mock.calls.map(c => c[0]);
    expect(urls).toEqual([
      `${API_BASE_URL}/assets/a%20b%2Fc/detections`,
      `${API_BASE_URL}/assets/a%20b%2Fc/detections/d%2F1`,
      `${API_BASE_URL}/assets/a%20b%2Fc/detections/d%2F1/confirm`,
      `${API_BASE_URL}/assets/a%20b%2Fc/detections/d%2F1/reject`,
      `${API_BASE_URL}/assets/a%20b%2Fc/detections/review-bulk`,
    ]);
    expect(fetchMock.mock.calls.map(c => c[1].method)).toEqual(["GET", "POST", "POST", "POST", "POST"]);
  });

  it("confirm sends an empty object when there is no corrected label", async () => {
    const fetchMock = stubFetch({ body: {} });
    await confirmDetection(TOKEN, "a1", "d1");
    expect(fetchMock.mock.calls[0][1].body).toBe("{}");
  });

  it("listDetections puts status and type into the query string, not the path", async () => {
    const fetchMock = stubFetch({ body: { data: [] } });
    await listDetections(TOKEN, "PENDING", "object detection");
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/detections?status=PENDING&type=object+detection`);

    await listDetections(TOKEN);
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/detections`);
  });

  it("a missing crop is null, not an error", async () => {
    stubFetch({ status: 404, ok: false });
    await expect(fetchDetectionCrop(TOKEN, "a1", "d1")).resolves.toBeNull();
  });

  it("a crop the session may no longer read raises the session-expired event", async () => {
    const listener = listenForSessionExpiry();
    stubFetch({ status: 401, ok: false });
    await expect(fetchDetectionCrop(TOKEN, "a1", "d1")).rejects.toSatisfy(isApiError);
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("void routes fail through ApiError", () => {
  const cases: Array<[string, () => Promise<unknown>]> = [
    ["deleteDetection", () => deleteDetection(TOKEN, "a1", "d1")],
    ["deletePerson", () => deletePerson(TOKEN, "p1")],
    ["deletePersonImage", () => deletePersonImage(TOKEN, "p1", "i1")],
    ["deleteCluster", () => deleteCluster(TOKEN, "c1")],
    ["deleteCollection", () => deleteCollection(TOKEN, "c1")],
    ["deleteTag", () => deleteTag(TOKEN, "t1")],
  ];

  for (const [name, call] of cases) {
    it(`${name}: a 401 fires session-expired and the error keeps status and trace id`, async () => {
      const listener = listenForSessionExpiry();
      stubFetch({ status: 401, ok: false, text: '{"message":"Token expired"}', headers: { "X-Trace-Id": "trace-1" } });

      const error = await call().then(() => null, (e: unknown) => e);

      expect(isApiError(error)).toBe(true);
      expect(error).toMatchObject({ status: 401, traceId: "trace-1", serverMessage: "Token expired" });
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it(`${name}: a 204 resolves`, async () => {
      stubFetch({ status: 204 });
      await expect(call()).resolves.toBeUndefined();
    });
  }
});

describe("clusters list query", () => {
  it("joins paging and filters into one query string", async () => {
    const fetchMock = stubFetch({ body: { data: [] } });
    await listClusters(TOKEN, { limit: 25 }, { status: "PENDING", type: "face" });
    const url = new URL(fetchMock.mock.calls[0][0]);
    expect(url.pathname.endsWith("/clusters")).toBe(true);
    expect(url.searchParams.get("limit")).toBe("25");
    expect(url.searchParams.get("status")).toBe("PENDING");
    expect(url.searchParams.get("type")).toBe("face");
  });
});
