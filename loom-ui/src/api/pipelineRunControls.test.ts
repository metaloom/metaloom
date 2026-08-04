import { afterEach, describe, expect, it, vi } from "vitest";
import { cancelPipelineRun, pausePipelineRun, resumePipelineRun } from "./pipelines";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";

function mockFetch(ok: boolean, status = 200, text = "") {
  const fetchMock = vi.fn().mockResolvedValue({
    ok,
    status,
    json: async () => ({}),
    text: async () => text,
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("pipeline run control clients", () => {
  // The three controls are deliberately identical in shape: same verb, same auth, same
  // encoding. Testing them together is what makes an accidental divergence visible.
  const cases: Array<[string, typeof pausePipelineRun, string]> = [
    ["pausePipelineRun", pausePipelineRun, "pause"],
    ["resumePipelineRun", resumePipelineRun, "resume"],
    ["cancelPipelineRun", cancelPipelineRun, "cancel"],
  ];

  it.each(cases)("%s POSTs the run sub-route with a bearer token", async (_name, fn, segment) => {
    const fetchMock = mockFetch(true, 200);

    await fn(TOKEN, "p1", "r1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/pipelines/p1/runs/r1/${segment}`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it.each(cases)("%s percent-encodes both uuids", async (_name, fn, segment) => {
    const fetchMock = mockFetch(true, 200);

    await fn(TOKEN, "p/1 a", "r/1 b");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/pipelines/p%2F1%20a/runs/r%2F1%20b/${segment}`);
  });

  it.each(cases)("%s rejects with the server's message on a non-2xx", async (_name, fn) => {
    // A resume is refused with 409 when the run is no longer live, and that body is the
    // only thing that explains why — so it has to survive into the thrown error.
    mockFetch(false, 409, "Pipeline run is not live and cannot be resumed.");

    await expect(fn(TOKEN, "p1", "r1")).rejects.toThrow(
      "API error 409: Pipeline run is not live and cannot be resumed.",
    );
  });
});
