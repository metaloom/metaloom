import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createTranscript,
  loadTranscript,
  updateTranscript,
  deleteTranscript,
} from "./transcripts";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";

function mockFetchOk(body: unknown = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => "",
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("transcripts API client", () => {
  it("createTranscript POSTs to the asset-scoped transcripts route", async () => {
    const fetchMock = mockFetchOk({ uuid: "t1", assetUuid: "a1" });
    const request = { source: "whisper", lang: "en", transcriptJson: { sections: [] } };

    await createTranscript(TOKEN, "a1", request);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/transcripts`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify(request));
  });

  it("loadTranscript GETs the single transcript route", async () => {
    const fetchMock = mockFetchOk({ uuid: "t1" });

    await loadTranscript(TOKEN, "a1", "t1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/transcripts/t1`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("updateTranscript POSTs (not PUT) to the single transcript route", async () => {
    const fetchMock = mockFetchOk({ uuid: "t1" });
    const request = { transcriptJson: { sections: [{ id: "s1", title: "Intro", startTime: 0, endTime: 5, words: [] }] } };

    await updateTranscript(TOKEN, "a1", "t1", request);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/transcripts/t1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify(request));
  });

  it("deleteTranscript DELETEs the single transcript route", async () => {
    const fetchMock = mockFetchOk();

    await deleteTranscript(TOKEN, "a1", "t1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/transcripts/t1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes asset and transcript uuids into the URL path", async () => {
    const fetchMock = mockFetchOk();

    await updateTranscript(TOKEN, "a b", "t/1", { source: "x" });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a%20b/transcripts/t%2F1`);
  });

  it("throws on a non-ok delete response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({}),
      text: async () => "boom",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(deleteTranscript(TOKEN, "a1", "t1")).rejects.toThrow(/API error 500/);
  });
});
