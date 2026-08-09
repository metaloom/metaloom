import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  cancelSearchIndexJob,
  createSearchIndexJob,
  formatBytes,
  indexStateLabel,
  indexTone,
  isTerminal,
  jobProgress,
  listSearchIndexJobs,
  listSearchIndices,
  loadSearchIndexJob,
  SearchIndexApiError,
  type IndexJobResponse,
  type SearchIndexResponse,
} from "./searchIndices";
import { API_BASE_URL } from "./config";

const TOKEN = "fake-jwt";

function ok(body: unknown) {
  return { ok: true, status: 200, json: async () => body } as unknown as Response;
}

function err(status: number, text = "") {
  return { ok: false, status, text: async () => text } as unknown as Response;
}

function index(overrides: Partial<SearchIndexResponse> = {}): SearchIndexResponse {
  return {
    id: "vector-face-inspireface-r18-512",
    kind: "VECTOR",
    backendId: "vector",
    enabled: true,
    available: true,
    documentCount: 100,
    indexedCount: 100,
    pendingCount: 0,
    supportedActions: ["REINDEX", "DELTA_SYNC", "DROP"],
    ...overrides,
  };
}

function job(overrides: Partial<IndexJobResponse> = {}): IndexJobResponse {
  return {
    uuid: "job-1",
    indexId: "lexical",
    action: "REINDEX",
    state: "RUNNING",
    processed: 0,
    removed: 0,
    ...overrides,
  };
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("request shaping", () => {
  it("listSearchIndices reads the collection with a bearer token", async () => {
    fetchMock.mockResolvedValue(ok({ data: [], backends: [] }));
    await listSearchIndices(TOKEN);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/search-indices`);
    expect(fetchMock.mock.calls[0][1].method).toBe("GET");
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("createSearchIndexJob posts the action in the body, not in the path", async () => {
    fetchMock.mockResolvedValue(ok(job()));
    await createSearchIndexJob(TOKEN, "lexical", "REINDEX");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/search-indices/lexical/jobs`);
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ action: "REINDEX" });
  });

  it("encodes the index id, which is a slug the server resolves by lookup", async () => {
    fetchMock.mockResolvedValue(ok({ data: [] }));
    await listSearchIndexJobs(TOKEN, "vector-text-nomic embed/1.5-768");

    expect(fetchMock.mock.calls[0][0]).toBe(
      `${API_BASE_URL}/search-indices/vector-text-nomic%20embed%2F1.5-768/jobs`,
    );
  });

  it("loadSearchIndexJob and cancelSearchIndexJob address the job beneath its index", async () => {
    fetchMock.mockResolvedValue(ok(job()));
    await loadSearchIndexJob(TOKEN, "lexical", "abc");
    await cancelSearchIndexJob(TOKEN, "lexical", "abc");

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/search-indices/lexical/jobs/abc`);
    expect(fetchMock.mock.calls[0][1].method).toBe("GET");
    expect(fetchMock.mock.calls[1][1].method).toBe("DELETE");
  });

  it("surfaces the status code so a 403 can be told from a network failure", async () => {
    fetchMock.mockResolvedValue(err(403, "no permission"));
    const error = await listSearchIndices(TOKEN).catch(e => e);

    expect(error).toBeInstanceOf(SearchIndexApiError);
    expect(error.status).toBe(403);
  });
});

describe("formatBytes", () => {
  it("reports binary units, matching what a filesystem shows", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1024)).toBe("1.0 KiB");
    expect(formatBytes(1_476_395_008)).toBe("1.4 GiB");
  });

  it("does not produce NaN for a missing or negative figure", () => {
    expect(formatBytes(Number.NaN)).toBe("0 B");
    expect(formatBytes(-1)).toBe("0 B");
  });
});

describe("jobProgress", () => {
  it("returns a percentage when the total is known", () => {
    expect(jobProgress(job({ processed: 25, total: 100 }))).toBe(25);
  });

  it("returns null when the total is unknown, so the caller draws an indeterminate bar", () => {
    // The lexical rebuild is one SQL call. A determinate bar pinned at 0% reads as a hung
    // job, which is the opposite of what is happening.
    expect(jobProgress(job({ processed: 0, total: null }))).toBeNull();
    expect(jobProgress(job({ processed: 0 }))).toBeNull();
    expect(jobProgress(job({ processed: 5, total: 0 }))).toBeNull();
  });

  it("clamps at 100 rather than overshooting", () => {
    expect(jobProgress(job({ processed: 150, total: 100 }))).toBe(100);
  });
});

describe("isTerminal", () => {
  it("recognises the states a job will not leave", () => {
    expect(isTerminal(job({ state: "RUNNING" }))).toBe(false);
    expect(isTerminal(job({ state: "PENDING" }))).toBe(false);
    expect(isTerminal(job({ state: "SUCCEEDED" }))).toBe(true);
    expect(isTerminal(job({ state: "FAILED" }))).toBe(true);
    expect(isTerminal(job({ state: "CANCELLED" }))).toBe(true);
  });
});

describe("indexTone / indexStateLabel", () => {
  it("treats a healthy index as green", () => {
    expect(indexTone(index())).toBe("green");
    expect(indexStateLabel(index())).toBe("Healthy");
  });

  it("treats never-switched-on as neutral, not as a fault", () => {
    const disabled = index({ enabled: false, available: false });
    expect(indexTone(disabled)).toBe("neutral");
    expect(indexStateLabel(disabled)).toBe("Disabled");
  });

  it("treats configured-but-unopenable as red — that one is an operational fault", () => {
    const broken = index({ enabled: true, available: false });
    expect(indexTone(broken)).toBe("red");
    expect(indexStateLabel(broken)).toBe("Unavailable");
  });

  it("flags a backlog as amber", () => {
    const behind = index({ pendingCount: 812 });
    expect(indexTone(behind)).toBe("amber");
    expect(indexStateLabel(behind)).toBe("Behind");
  });

  it("flags orphans as amber too — an index holding more than the database is drifted", () => {
    const drifted = index({ documentCount: 100, indexedCount: 140 });
    expect(indexTone(drifted)).toBe("amber");
    expect(indexStateLabel(drifted)).toBe("Drifted");
  });

  it("reports a running job as working, ahead of the backlog it is draining", () => {
    const working = index({ pendingCount: 500, activeJob: job({ state: "RUNNING" }) });
    expect(indexStateLabel(working)).toBe("Working");
  });

  it("ignores a finished job when labelling the state", () => {
    const done = index({ activeJob: job({ state: "SUCCEEDED" }) });
    expect(indexStateLabel(done)).toBe("Healthy");
  });
});
