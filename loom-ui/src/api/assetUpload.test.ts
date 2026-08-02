import { afterEach, describe, expect, it, vi } from "vitest";
import { buildUploadForm, uploadAsset, uploadAssetWithProgress, UploadAbortedError } from "./assets";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";

function fileOf(name = "photo.jpg", type = "image/jpeg"): File {
  return new File(["hello-bytes"], name, { type });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("buildUploadForm", () => {
  it("always carries the file and the target library", () => {
    const form = buildUploadForm(fileOf(), "lib-1");

    expect((form.get("file") as File).name).toBe("photo.jpg");
    expect(form.get("libraryUuid")).toBe("lib-1");
  });

  it("omits origin and poolUuid when they are not set", () => {
    const form = buildUploadForm(fileOf(), "lib-1");

    expect(form.get("origin")).toBeNull();
    // Absent rather than blank: the field only appears when the caller actually chose a pool.
    expect(form.get("poolUuid")).toBeNull();
  });

  it("appends origin and poolUuid when provided", () => {
    const form = buildUploadForm(fileOf(), "lib-1", { origin: "import", poolUuid: "pool-7" });

    expect(form.get("origin")).toBe("import");
    expect(form.get("poolUuid")).toBe("pool-7");
  });

  it("treats an empty pool string as no override", () => {
    // The select renders "" for "let the library decide"; that must not reach the wire.
    const form = buildUploadForm(fileOf(), "lib-1", { poolUuid: "" });

    expect(form.get("poolUuid")).toBeNull();
  });
});

describe("uploadAsset", () => {
  it("POSTs multipart to /assets/upload without an explicit Content-Type", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 201, json: async () => ({ uuid: "a1" }), text: async () => "",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await uploadAsset(TOKEN, fileOf(), "lib-1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/upload`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    // The browser has to pick the multipart boundary itself.
    expect("Content-Type" in options.headers).toBe(false);
    expect(options.body).toBeInstanceOf(FormData);
  });
});

/** Minimal XMLHttpRequest stand-in — vitest runs in a node environment with no DOM. */
class FakeXhr {
  static last: FakeXhr | null = null;
  status = 0;
  responseText = "";
  upload: { onprogress?: (e: { loaded: number; total: number; lengthComputable: boolean }) => void } = {};
  onload?: () => void;
  onerror?: () => void;
  onabort?: () => void;
  method = "";
  url = "";
  headers: Record<string, string> = {};
  body: unknown = null;

  constructor() {
    FakeXhr.last = this;
  }
  open(method: string, url: string) { this.method = method; this.url = url; }
  setRequestHeader(key: string, value: string) { this.headers[key] = value; }
  send(body: unknown) { this.body = body; }
  abort() { this.onabort?.(); }
}

function stubXhr(): typeof FakeXhr {
  vi.stubGlobal("XMLHttpRequest", FakeXhr);
  return FakeXhr;
}

describe("uploadAssetWithProgress", () => {
  it("opens a POST to the upload route with the bearer token", () => {
    stubXhr();
    uploadAssetWithProgress(TOKEN, fileOf(), "lib-1");

    const xhr = FakeXhr.last!;
    expect(xhr.method).toBe("POST");
    expect(xhr.url).toBe(`${API_BASE_URL}/assets/upload`);
    expect(xhr.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(xhr.headers["Content-Type"]).toBeUndefined();
    expect(xhr.body).toBeInstanceOf(FormData);
  });

  it("reports upload progress", () => {
    stubXhr();
    const onProgress = vi.fn();
    uploadAssetWithProgress(TOKEN, fileOf(), "lib-1", { onProgress });

    FakeXhr.last!.upload.onprogress?.({ loaded: 40, total: 100, lengthComputable: true });

    expect(onProgress).toHaveBeenCalledWith({ loaded: 40, total: 100 });
  });

  it("reports a zero total while the length is still unknown", () => {
    stubXhr();
    const onProgress = vi.fn();
    uploadAssetWithProgress(TOKEN, fileOf(), "lib-1", { onProgress });

    FakeXhr.last!.upload.onprogress?.({ loaded: 40, total: 0, lengthComputable: false });

    expect(onProgress).toHaveBeenCalledWith({ loaded: 40, total: 0 });
  });

  it("resolves with created=true on 201", async () => {
    stubXhr();
    const handle = uploadAssetWithProgress(TOKEN, fileOf(), "lib-1");

    const xhr = FakeXhr.last!;
    xhr.status = 201;
    xhr.responseText = JSON.stringify({ uuid: "a1" });
    xhr.onload!();

    await expect(handle.promise).resolves.toEqual({ asset: { uuid: "a1" }, created: true });
  });

  it("resolves with created=false on 200, the already-known-content answer", async () => {
    stubXhr();
    const handle = uploadAssetWithProgress(TOKEN, fileOf(), "lib-1");

    const xhr = FakeXhr.last!;
    xhr.status = 200;
    xhr.responseText = JSON.stringify({ uuid: "existing" });
    xhr.onload!();

    await expect(handle.promise).resolves.toEqual({ asset: { uuid: "existing" }, created: false });
  });

  it("rejects with the status and body on an error response", async () => {
    stubXhr();
    const handle = uploadAssetWithProgress(TOKEN, fileOf(), "lib-1");

    const xhr = FakeXhr.last!;
    xhr.status = 403;
    xhr.responseText = "Invalid permissions";
    xhr.onload!();

    await expect(handle.promise).rejects.toThrow(/403.*Invalid permissions/);
  });

  it("rejects with UploadAbortedError when cancelled", async () => {
    stubXhr();
    const handle = uploadAssetWithProgress(TOKEN, fileOf(), "lib-1");

    handle.abort();

    await expect(handle.promise).rejects.toBeInstanceOf(UploadAbortedError);
  });

  it("keeps the abort rejection when a trailing error event follows", async () => {
    stubXhr();
    const handle = uploadAssetWithProgress(TOKEN, fileOf(), "lib-1");

    handle.abort();
    // Some browsers fire onerror right after onabort; that must not turn a cancel into a failure.
    FakeXhr.last!.onerror?.();

    await expect(handle.promise).rejects.toBeInstanceOf(UploadAbortedError);
  });
});
