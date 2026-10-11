import { afterEach, describe, expect, it, vi } from "vitest";
import { API_BASE_URL } from "./config";
import { audioClipUrl, fetchAudioClip, fetchAuthedBlob, resolveApiUrl } from "./media";
import { importPersonVoiceSample, setPersonHeroVoice, uploadPersonVoiceSample } from "./persons";

const TOKEN = "test-token";

function mockFetch(init: { ok?: boolean; status?: number; body?: unknown } = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    url: "",
    headers: new Headers(),
    json: async () => init.body ?? {},
    text: async () => JSON.stringify(init.body ?? {}),
    blob: async () => new Blob(["RIFF"]),
  } as unknown as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("media API client", () => {
  it("audioClipUrl sends whole milliseconds and never a negative start", () => {
    expect(audioClipUrl("a1", 1500.4, 4500.2)).toBe(`${API_BASE_URL}/assets/a1/audio-clip?from=1500&to=4501`);
    expect(audioClipUrl("a1", -20, 10)).toBe(`${API_BASE_URL}/assets/a1/audio-clip?from=0&to=10`);
  });

  it("fetchAudioClip asks with the bearer token", async () => {
    const fetchMock = mockFetch();
    await fetchAudioClip(TOKEN, "a1", 0, 30_000);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/audio-clip?from=0&to=30000`);
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("resolveApiUrl keeps a server path on the API origin", () => {
    const origin = new URL(API_BASE_URL).origin;
    expect(resolveApiUrl("/api/v1/persons/p/voice-samples/s/data")).toBe(`${origin}/api/v1/persons/p/voice-samples/s/data`);
    expect(resolveApiUrl("https://example.org/x")).toBe("https://example.org/x");
  });

  it("fetchAuthedBlob throws on a failed response instead of handing back an error page", async () => {
    mockFetch({ ok: false, status: 422, body: { message: "no audio" } });
    await expect(fetchAuthedBlob(TOKEN, "/api/v1/assets/a1/audio-clip?from=0&to=1")).rejects.toThrow("no audio");
  });
});

describe("person voice API client", () => {
  it("uploads as multipart without a Content-Type header", async () => {
    const fetchMock = mockFetch({ body: { uuid: "s1" } });
    await uploadPersonVoiceSample(TOKEN, "p1", new File(["x"], "voice.wav", { type: "audio/wav" }));
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/persons/p1/voice-samples`);
    expect(options.method).toBe("POST");
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.headers["Content-Type"]).toBeUndefined();
  });

  it("imports from a cluster with the range only when given", async () => {
    const fetchMock = mockFetch({ body: { uuid: "s1" } });
    await importPersonVoiceSample(TOKEN, "p1", { clusterUuid: "c1" });
    await importPersonVoiceSample(TOKEN, "p1", { clusterUuid: "c1", timeFrom: 1000, timeTo: 4000 });
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/persons/p1/voice-samples/from-cluster`);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ clusterUuid: "c1" });
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ clusterUuid: "c1", timeFrom: 1000, timeTo: 4000 });
  });

  it("clears the hero voice with a blank sample uuid", async () => {
    const fetchMock = mockFetch({ body: { uuid: "p1" } });
    await setPersonHeroVoice(TOKEN, "p1", null);
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/persons/p1/hero-voice`);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ sampleUuid: "" });
  });
});
