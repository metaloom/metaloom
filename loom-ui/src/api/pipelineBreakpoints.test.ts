import { afterEach, describe, expect, it, vi } from "vitest";
import {
  continuePipelineRunBreakpoint,
  loadPipelineRunBreakpoints,
  setPipelineRunBreakpoints,
  stepPipelineRun,
} from "./pipelines";
import { API_BASE_URL } from "./config";

/**
 * The four breakpoint clients.
 *
 * Two behaviours matter beyond the plumbing, and both are about what happens when the server
 * says no. A *read* degrades to "nothing armed, nothing held", because a run whose engine is
 * gone genuinely is holding nothing and the debug view should render that rather than an error.
 * A *write* throws and carries the server's message, because a Step that quietly did nothing is
 * indistinguishable from a Step that advanced the run.
 */

const TOKEN = "test-token";

function mockFetch(ok: boolean, status = 200, body: unknown = {}, text = "") {
  const fetchMock = vi.fn().mockResolvedValue({
    ok,
    status,
    json: async () => body,
    text: async () => text,
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("loadPipelineRunBreakpoints", () => {
  it("GETs the run's breakpoints with a bearer token", async () => {
    const fetchMock = mockFetch(true, 200, { nodeIds: ["thumb"], held: [] });

    const result = await loadPipelineRunBreakpoints(TOKEN, "p1", "r1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/pipelines/p1/runs/r1/breakpoints`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(result.nodeIds).toEqual(["thumb"]);
  });

  it("returns an empty result rather than throwing when the run is gone", async () => {
    // A 404 or 409 here means "no live engine". The debug view has nothing to show, which is
    // a state it can render — unlike an exception.
    mockFetch(false, 404);

    await expect(loadPipelineRunBreakpoints(TOKEN, "p1", "r1")).resolves.toEqual({
      nodeIds: [],
      held: [],
    });
  });

  it("tolerates a response missing either list", async () => {
    mockFetch(true, 200, {});

    await expect(loadPipelineRunBreakpoints(TOKEN, "p1", "r1")).resolves.toEqual({
      nodeIds: [],
      held: [],
    });
  });

  it("percent-encodes both uuids", async () => {
    const fetchMock = mockFetch(true, 200, { nodeIds: [], held: [] });

    await loadPipelineRunBreakpoints(TOKEN, "p/1 a", "r/1 b");

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/pipelines/p%2F1%20a/runs/r%2F1%20b/breakpoints`);
  });
});

describe("setPipelineRunBreakpoints", () => {
  it("PUTs the whole armed set as the body", async () => {
    // A whole-set replacement, not a delta: the editor sends what the set should become, so
    // the two sides cannot end up disagreeing about what is armed.
    const fetchMock = mockFetch(true, 200, { nodeIds: ["a", "b"], held: [] });

    const result = await setPipelineRunBreakpoints(TOKEN, "p1", "r1", ["a", "b"]);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/pipelines/p1/runs/r1/breakpoints`);
    expect(options.method).toBe("PUT");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(options.body)).toEqual({ nodeIds: ["a", "b"] });
    expect(result.nodeIds).toEqual(["a", "b"]);
  });

  it("sends an empty list to disarm everything", async () => {
    const fetchMock = mockFetch(true, 200, { nodeIds: [], held: [] });

    await setPipelineRunBreakpoints(TOKEN, "p1", "r1", []);

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ nodeIds: [] });
  });

  it("rejects with the server's message when a node id is unknown", async () => {
    // The 400 body names the offending id, which is the whole value of the check — a typo'd
    // breakpoint would otherwise arm silently and never fire.
    mockFetch(false, 400, {}, "No such node in this pipeline: hahs");

    await expect(setPipelineRunBreakpoints(TOKEN, "p1", "r1", ["hahs"])).rejects.toThrow(
      "API error 400: No such node in this pipeline: hahs",
    );
  });
});

describe("continuePipelineRunBreakpoint", () => {
  it("POSTs to the node's continue sub-route", async () => {
    const fetchMock = mockFetch(true, 200);

    await continuePipelineRunBreakpoint(TOKEN, "p1", "r1", "thumb");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/pipelines/p1/runs/r1/breakpoints/thumb/continue`);
    expect(options.method).toBe("POST");
  });

  it("percent-encodes the node id", async () => {
    // A node id is whatever the pipeline author typed, so it cannot be pasted into a path.
    const fetchMock = mockFetch(true, 200);

    await continuePipelineRunBreakpoint(TOKEN, "p1", "r1", "my node/1");

    expect(fetchMock.mock.calls[0][0]).toBe(
      `${API_BASE_URL}/pipelines/p1/runs/r1/breakpoints/my%20node%2F1/continue`,
    );
  });

  it("rejects with the server's message on a non-2xx", async () => {
    mockFetch(false, 409, {}, "Pipeline run is not live and cannot be debugged.");

    await expect(continuePipelineRunBreakpoint(TOKEN, "p1", "r1", "thumb")).rejects.toThrow(
      "API error 409: Pipeline run is not live and cannot be debugged.",
    );
  });
});

describe("stepPipelineRun", () => {
  it("POSTs to the run's steps collection", async () => {
    const fetchMock = mockFetch(true, 200, { nodeIds: ["thumb"], held: [] });

    const result = await stepPipelineRun(TOKEN, "p1", "r1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/pipelines/p1/runs/r1/steps`);
    expect(options.method).toBe("POST");
    expect(result.held).toEqual([]);
  });

  it("returns what is still held after the step", async () => {
    // The response is how the caller learns whether more items are queued behind this one,
    // without a second round trip.
    mockFetch(true, 200, {
      nodeIds: ["thumb"],
      held: [{ nodeId: "thumb", itemUuid: "i2", elementSeq: 0 }],
    });

    const result = await stepPipelineRun(TOKEN, "p1", "r1");

    expect(result.held).toEqual([{ nodeId: "thumb", itemUuid: "i2", elementSeq: 0 }]);
  });

  it("rejects when the run is not holding anything", async () => {
    // Deliberately not a silent success: a step that did nothing must not look like a step
    // that advanced the run.
    mockFetch(false, 409, {}, "Pipeline run is not holding at a breakpoint, so there is nothing to step.");

    await expect(stepPipelineRun(TOKEN, "p1", "r1")).rejects.toThrow(
      "API error 409: Pipeline run is not holding at a breakpoint, so there is nothing to step.",
    );
  });
});
