import { AssetResponse } from "./assets";
import { API_BASE_URL } from "./config";
import { authHeaders, handleResponse } from "./http";

/**
 * Files attached to a chat: `/chats/:uuid/attachments`.
 *
 * A chat attachment is **not** a library asset. It belongs to the conversation, is deleted with it,
 * and is never indexed, thumbnailed or run through pipelines — which is the point: dropping a
 * reference photo into a chat should not add it to a curated catalogue. {@link promoteChatAttachment}
 * is the deliberate way to keep one.
 *
 * Deliberately separate from `uploadQueue.ts`. That queue is the library-upload screen's state, and
 * routing chat files through it would make them appear in the `/uploads` view as if the user had
 * imported them.
 */

/** One file attached to a chat, as the backend reports it. */
export interface ChatAttachmentResponse {
  uuid: string;
  filename: string;
  mimeType?: string;
  size: number;
  sha512sum?: string;
}

export interface ChatAttachmentListResponse {
  data: ChatAttachmentResponse[];
}

/** Bytes handed to the socket so far; `total` is 0 while the length is still unknown. */
export interface AttachmentUploadProgress {
  loaded: number;
  total: number;
}

export interface AttachmentUploadHandle {
  promise: Promise<ChatAttachmentResponse>;
  /** Stop the transfer. The promise rejects with an {@link AttachmentUploadAbortedError}. */
  abort: () => void;
}

/** Thrown when an upload is cancelled, so callers can tell it apart from a real failure. */
export class AttachmentUploadAbortedError extends Error {
  constructor() {
    super("Attachment upload aborted");
    this.name = "AttachmentUploadAbortedError";
  }
}

function attachmentsUrl(chatUuid: string): string {
  return `${API_BASE_URL}/chats/${encodeURIComponent(chatUuid)}/attachments`;
}

/**
 * Upload a file to a chat, reporting progress.
 *
 * XMLHttpRequest rather than fetch for the same reason `uploadAssetWithProgress` uses it: fetch
 * exposes no upload-progress event. Everything else in this module stays on fetch.
 */
export function uploadChatAttachment(
  token: string,
  chatUuid: string,
  file: File,
  opts?: { onProgress?: (progress: AttachmentUploadProgress) => void }
): AttachmentUploadHandle {
  const xhr = new XMLHttpRequest();
  let aborted = false;

  const promise = new Promise<ChatAttachmentResponse>((resolve, reject) => {
    xhr.open("POST", attachmentsUrl(chatUuid));
    xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    // No Content-Type: the browser has to set the multipart boundary itself.

    xhr.upload.onprogress = (event) => {
      opts?.onProgress?.({ loaded: event.loaded, total: event.lengthComputable ? event.total : 0 });
    };

    xhr.onload = () => {
      if (xhr.status < 200 || xhr.status >= 300) {
        reject(new Error(`API error ${xhr.status}: ${xhr.responseText ?? ""}`));
        return;
      }
      try {
        resolve(JSON.parse(xhr.responseText) as ChatAttachmentResponse);
      } catch {
        reject(new Error("Upload succeeded but the response could not be parsed"));
      }
    };

    // A cancelled transfer fires both onabort and onerror in some browsers; the flag keeps the
    // first (correct) rejection and makes the second a no-op.
    xhr.onabort = () => {
      aborted = true;
      reject(new AttachmentUploadAbortedError());
    };
    xhr.onerror = () => {
      if (!aborted) reject(new Error("Upload failed: the request could not be completed"));
    };

    const form = new FormData();
    form.append("file", file, file.name);
    xhr.send(form);
  });

  return { promise, abort: () => xhr.abort() };
}

/** The files currently attached to a chat, newest first. */
export async function listChatAttachments(token: string, chatUuid: string): Promise<ChatAttachmentListResponse> {
  const res = await fetch(attachmentsUrl(chatUuid), { method: "GET", headers: authHeaders(token) });
  return handleResponse<ChatAttachmentListResponse>(res);
}

/** Detach a file. The bytes stay in content-addressed storage; only the chat's reference goes. */
export async function deleteChatAttachment(token: string, chatUuid: string, attachmentUuid: string): Promise<void> {
  const res = await fetch(`${attachmentsUrl(chatUuid)}/${encodeURIComponent(attachmentUuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/**
 * Save an attachment into the media library as a real asset.
 *
 * The attachment stays on the chat — a file can be in the conversation and in the library at once,
 * and deleting the chat afterwards must not take the asset with it.
 */
export async function promoteChatAttachment(
  token: string,
  chatUuid: string,
  attachmentUuid: string,
  libraryUuid?: string
): Promise<AssetResponse> {
  // A query parameter rather than a form field: the route takes no body, so it does not have to
  // consume multipart for the sake of one optional value.
  const query = libraryUuid ? `?libraryUuid=${encodeURIComponent(libraryUuid)}` : "";
  const res = await fetch(`${attachmentsUrl(chatUuid)}/${encodeURIComponent(attachmentUuid)}/asset${query}`, {
    method: "POST",
    headers: authHeaders(token),
  });
  return handleResponse<AssetResponse>(res);
}

/**
 * An attachment's bytes as a Blob.
 *
 * The route needs an `Authorization` header and an `<img src>` cannot send one, so a thumbnail is
 * built from an object URL rather than pointed at the endpoint — the same thing `useAuthedImage`
 * does for asset binaries. The `?mt=` media token is scoped to asset uuids and deliberately mounted
 * on two asset routes only, so it does not apply here.
 */
export async function fetchChatAttachmentBlob(token: string, chatUuid: string, attachmentUuid: string): Promise<Blob> {
  const res = await fetch(`${attachmentsUrl(chatUuid)}/${encodeURIComponent(attachmentUuid)}/data`, {
    method: "GET",
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.blob();
}

/**
 * Whether a file is one the agent can actually read, which decides what the chip promises.
 *
 * ⚠️ This mirrors `PlainTextExtractor.supports` on the server, which is the authority. It is
 * duplicated rather than fetched because the chip has to say something the moment a file is dropped,
 * before any round trip. If the server's list grows — most likely when the Tika extractor lands and
 * PDF and Office files become readable — this one has to grow with it, or a chip will promise
 * something `read_attachment` then refuses. The cost of being wrong is a misleading label, not a
 * broken feature: the tool's own answer is always the truth.
 */
export function isReadableKind(mimeType?: string): boolean {
  if (!mimeType) return false;
  const type = mimeType.split(";")[0].trim().toLowerCase();
  if (type.startsWith("text/")) return true;
  if (type.endsWith("+json") || type.endsWith("+xml")) return true;
  return [
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/ecmascript",
    "application/x-yaml",
    "application/yaml",
    "application/x-sh",
    "application/sql",
    "application/toml",
    "application/csv",
    "application/x-ndjson",
  ].includes(type);
}

/** Whether a file is a picture, and so something the image tools can work on. */
export function isImageKind(mimeType?: string): boolean {
  return !!mimeType && mimeType.split(";")[0].trim().toLowerCase().startsWith("image/");
}
