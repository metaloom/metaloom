import { afterEach, describe, expect, it, vi } from "vitest";
import {
  listAnnotations,
  loadAnnotation,
  createAnnotation,
  updateAnnotation,
  deleteAnnotation,
} from "./annotations";
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

describe("annotations API client", () => {
  it("listAnnotations GETs the annotations route", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listAnnotations(TOKEN);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("loadAnnotation GETs the annotation uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "n1" });

    await loadAnnotation(TOKEN, "n1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1`);
    expect(options.method).toBe("GET");
  });

  it("createAnnotation POSTs to the annotations route", async () => {
    const fetchMock = mockFetchOk({ uuid: "n1", title: "Scene" });
    const request = { type: "FEEDBACK", title: "Scene", description: "d", assetUuid: "a1" };

    await createAnnotation(TOKEN, request);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(options.body).toBe(JSON.stringify(request));
  });

  it("createAnnotation forwards the area payload", async () => {
    const fetchMock = mockFetchOk({ uuid: "n1" });
    const request = { type: "FEEDBACK", title: "Clip", assetUuid: "a1", area: { from: 1000, to: 5000 } };

    await createAnnotation(TOKEN, request);

    const [, options] = fetchMock.mock.calls[0];
    expect(options.body).toBe(JSON.stringify(request));
  });

  it("updateAnnotation POSTs (not PUT) to the annotation uuid route", async () => {
    const fetchMock = mockFetchOk({ uuid: "n1", title: "edited" });

    await updateAnnotation(TOKEN, "n1", { title: "edited" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1`);
    expect(options.method).toBe("POST");
    expect(options.body).toBe(JSON.stringify({ title: "edited" }));
  });

  it("deleteAnnotation DELETEs the annotation uuid route", async () => {
    const fetchMock = mockFetchOk();

    await deleteAnnotation(TOKEN, "n1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/n1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes uuids into the URL path", async () => {
    const fetchMock = mockFetchOk();

    await updateAnnotation(TOKEN, "a b/c", { title: "x" });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/annotations/a%20b%2Fc`);
  });
});
