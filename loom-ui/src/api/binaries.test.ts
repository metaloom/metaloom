import { afterEach, describe, expect, it, vi } from "vitest";
import {
  uploadAssetBinary,
  fetchAssetBinaryBlob,
  downloadAssetBinary,
  loadAssetBinaryMeta,
  deleteAssetBinary,
} from "./binaries";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";

function mockFetchOk(body: unknown = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => "",
    blob: async () => new Blob(["bytes"]),
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("binaries API client", () => {
  it("uploadAssetBinary POSTs a multipart FormData body without a Content-Type header", async () => {
    const fetchMock = mockFetchOk({ uuid: "b1", assetUuid: "a1" });
    const file = new File(["hello"], "clip.mp4", { type: "video/mp4" });

    await uploadAssetBinary(TOKEN, "a1", file);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/binary/data`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    // The browser must set the multipart boundary itself — no explicit Content-Type.
    expect("Content-Type" in options.headers).toBe(false);
    expect(options.body).toBeInstanceOf(FormData);
    const form = options.body as FormData;
    expect(form.get("file")).toBeTruthy();
    expect((form.get("file") as File).name).toBe("clip.mp4");
    // libraryUuid is omitted when not provided.
    expect(form.get("libraryUuid")).toBeNull();
  });

  it("uploadAssetBinary appends libraryUuid when provided", async () => {
    const fetchMock = mockFetchOk({ uuid: "b1" });
    const file = new File(["x"], "x.bin");

    await uploadAssetBinary(TOKEN, "a1", file, "lib-9");

    const [, options] = fetchMock.mock.calls[0];
    const form = options.body as FormData;
    expect(form.get("libraryUuid")).toBe("lib-9");
  });

  it("uploadAssetBinary encodes the asset uuid into the URL path", async () => {
    const fetchMock = mockFetchOk();
    const file = new File(["x"], "x.bin");

    await uploadAssetBinary(TOKEN, "a b/c", file);

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a%20b%2Fc/binary/data`);
  });

  it("fetchAssetBinaryBlob GETs the raw bytes as a Blob", async () => {
    const fetchMock = mockFetchOk();

    const blob = await fetchAssetBinaryBlob(TOKEN, "a1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/binary/data`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(blob).toBeInstanceOf(Blob);
  });

  it("fetchAssetBinaryBlob throws on a non-ok response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => "missing",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchAssetBinaryBlob(TOKEN, "a1")).rejects.toThrow(/404/);
  });

  it("downloadAssetBinary fetches the blob and triggers a browser download", async () => {
    const blob = new Blob(["bytes"]);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => blob,
      text: async () => "",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    const createObjectURL = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:mock");
    const revokeObjectURL = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const anchor = { href: "", download: "", click: vi.fn() };
    vi.stubGlobal("document", { createElement: vi.fn(() => anchor) });

    await downloadAssetBinary(TOKEN, "a1", "clip.mp4");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/binary/data`);
    expect(options.method).toBe("GET");
    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect(anchor.href).toBe("blob:mock");
    expect(anchor.download).toBe("clip.mp4");
    expect(anchor.click).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:mock");
  });

  it("loadAssetBinaryMeta GETs the JSON metadata route", async () => {
    const fetchMock = mockFetchOk({ uuid: "b1", filesystem: { path: "/x" } });

    const meta = await loadAssetBinaryMeta(TOKEN, "a1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/binary`);
    expect(options.method).toBe("GET");
    expect(options.headers["Content-Type"]).toBe("application/json");
    expect(meta.filesystem?.path).toBe("/x");
  });

  it("deleteAssetBinary DELETEs the binary route", async () => {
    const fetchMock = mockFetchOk();

    await deleteAssetBinary(TOKEN, "a1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/binary`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });
});
