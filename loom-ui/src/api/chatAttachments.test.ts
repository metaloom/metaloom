import { afterEach, describe, expect, it, vi } from "vitest";

import {
  AttachmentUploadAbortedError,
  deleteChatAttachment,
  fetchChatAttachmentBlob,
  isImageKind,
  isReadableKind,
  listChatAttachments,
  promoteChatAttachment,
  uploadChatAttachment,
} from "./chatAttachments";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";
const CHAT = "chat-1";
const ATT = "att-1";

function fileOf(name = "brief.md", type = "text/markdown"): File {
  return new File(["hello"], name, { type });
}

/**
 * XMLHttpRequest stand-in. The vitest environment is "node" with no DOM, and the upload path uses
 * XHR deliberately (fetch exposes no upload-progress event), so it has to be stubbed rather than
 * driven.
 */
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

function stubXhr() {
  vi.stubGlobal("XMLHttpRequest", FakeXhr);
}

function stubFetch(response: Partial<Response>) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true, status: 200, json: async () => ({}), text: async () => "", ...response,
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  FakeXhr.last = null;
});

describe("uploadChatAttachment", () => {
  it("POSTs multipart to the chat's attachment route with the bearer token", () => {
    stubXhr();
    uploadChatAttachment(TOKEN, CHAT, fileOf());

    const xhr = FakeXhr.last!;
    expect(xhr.method).toBe("POST");
    expect(xhr.url).toBe(`${API_BASE_URL}/chats/${CHAT}/attachments`);
    expect(xhr.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    // The browser has to pick the multipart boundary itself.
    expect(xhr.headers["Content-Type"]).toBeUndefined();
    expect(xhr.body).toBeInstanceOf(FormData);
  });

  it("sends no libraryUuid, because an attachment is not filed anywhere", () => {
    stubXhr();
    uploadChatAttachment(TOKEN, CHAT, fileOf());

    expect((FakeXhr.last!.body as FormData).get("libraryUuid")).toBeNull();
  });

  it("reports progress as bytes go out", () => {
    stubXhr();
    const onProgress = vi.fn();
    uploadChatAttachment(TOKEN, CHAT, fileOf(), { onProgress });

    FakeXhr.last!.upload.onprogress?.({ loaded: 40, total: 100, lengthComputable: true });

    expect(onProgress).toHaveBeenCalledWith({ loaded: 40, total: 100 });
  });

  it("reports total 0 while the length is unknown, so the bar stays indeterminate", () => {
    stubXhr();
    const onProgress = vi.fn();
    uploadChatAttachment(TOKEN, CHAT, fileOf(), { onProgress });

    FakeXhr.last!.upload.onprogress?.({ loaded: 40, total: 0, lengthComputable: false });

    expect(onProgress).toHaveBeenCalledWith({ loaded: 40, total: 0 });
  });

  it("resolves with the stored attachment", async () => {
    stubXhr();
    const handle = uploadChatAttachment(TOKEN, CHAT, fileOf());

    const xhr = FakeXhr.last!;
    xhr.status = 201;
    xhr.responseText = JSON.stringify({ uuid: ATT, filename: "brief.md", mimeType: "text/markdown", size: 5 });
    xhr.onload?.();

    await expect(handle.promise).resolves.toMatchObject({ uuid: ATT, filename: "brief.md" });
  });

  it("rejects with the status and body on an error response", async () => {
    stubXhr();
    const handle = uploadChatAttachment(TOKEN, CHAT, fileOf());

    const xhr = FakeXhr.last!;
    xhr.status = 409;
    xhr.responseText = "too many attachments";
    xhr.onload?.();

    // The 409 message names the limit, and the chip shows it — so it has to survive.
    await expect(handle.promise).rejects.toThrow("too many attachments");
  });

  it("rejects with AttachmentUploadAbortedError when cancelled, not a generic failure", async () => {
    stubXhr();
    const handle = uploadChatAttachment(TOKEN, CHAT, fileOf());

    handle.abort();

    await expect(handle.promise).rejects.toBeInstanceOf(AttachmentUploadAbortedError);
  });

  it("keeps the abort rejection when a browser also fires onerror afterwards", async () => {
    stubXhr();
    const handle = uploadChatAttachment(TOKEN, CHAT, fileOf());

    handle.abort();
    FakeXhr.last!.onerror?.();

    await expect(handle.promise).rejects.toBeInstanceOf(AttachmentUploadAbortedError);
  });
});

describe("listChatAttachments", () => {
  it("GETs the chat's attachment route", async () => {
    const fetchMock = stubFetch({ json: async () => ({ data: [] }) });

    await listChatAttachments(TOKEN, CHAT);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/${CHAT}/attachments`);
    expect(options.method).toBe("GET");
  });
});

describe("deleteChatAttachment", () => {
  it("DELETEs the single attachment", async () => {
    const fetchMock = stubFetch({ ok: true, status: 204 });

    await deleteChatAttachment(TOKEN, CHAT, ATT);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/${CHAT}/attachments/${ATT}`);
    expect(options.method).toBe("DELETE");
  });

  it("throws on a non-2xx so the caller can tell the user it stayed", async () => {
    stubFetch({ ok: false, status: 404, text: async () => "gone" });

    await expect(deleteChatAttachment(TOKEN, CHAT, ATT)).rejects.toThrow("404");
  });
});

describe("promoteChatAttachment", () => {
  it("POSTs to the /asset route", async () => {
    const fetchMock = stubFetch({ status: 201, json: async () => ({ uuid: "asset-1" }) });

    await promoteChatAttachment(TOKEN, CHAT, ATT);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/${CHAT}/attachments/${ATT}/asset`);
    expect(options.method).toBe("POST");
  });

  it("omits libraryUuid when none was chosen, so the server default applies", async () => {
    const fetchMock = stubFetch({ status: 201, json: async () => ({ uuid: "asset-1" }) });

    await promoteChatAttachment(TOKEN, CHAT, ATT);

    expect(fetchMock.mock.calls[0][0]).not.toContain("libraryUuid");
    // No body at all: the route takes none, which is why libraryUuid is a query parameter.
    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
  });

  it("sends libraryUuid as a query parameter when one was chosen", async () => {
    const fetchMock = stubFetch({ status: 201, json: async () => ({ uuid: "asset-1" }) });

    await promoteChatAttachment(TOKEN, CHAT, ATT, "lib-9");

    expect(fetchMock.mock.calls[0][0]).toContain("?libraryUuid=lib-9");
  });
});

describe("fetchChatAttachmentBlob", () => {
  it("sends the Authorization header, which is why a blob URL is needed at all", async () => {
    const fetchMock = stubFetch({ blob: async () => new Blob(["x"]) } as Partial<Response>);

    await fetchChatAttachmentBlob(TOKEN, CHAT, ATT);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/chats/${CHAT}/attachments/${ATT}/data`);
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });
});

describe("kind predicates", () => {
  it("mirrors the server's text family", () => {
    // PlainTextExtractor.supports is the authority; these are the cases that differ from a naive
    // text/* check and are therefore the ones worth pinning.
    expect(isReadableKind("application/json")).toBe(true);
    expect(isReadableKind("application/vnd.api+json")).toBe(true);
    expect(isReadableKind("application/x-yaml")).toBe(true);
    expect(isReadableKind("text/csv; charset=utf-8")).toBe(true);
    expect(isReadableKind("application/pdf")).toBe(false);
    expect(isReadableKind(undefined)).toBe(false);
  });

  it("recognises pictures", () => {
    expect(isImageKind("image/png")).toBe(true);
    expect(isImageKind("IMAGE/PNG")).toBe(true);
    expect(isImageKind("application/pdf")).toBe(false);
    expect(isImageKind(undefined)).toBe(false);
  });
});
