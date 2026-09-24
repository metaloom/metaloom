import { ChatAttachmentResponse, isImageKind, isReadableKind } from "../../api/chatAttachments";

/**
 * The composer's attachment list, as pure data.
 *
 * Kept out of the component on purpose: `loom-ui` has no jsdom and no component-render tests
 * (`vitest.config.ts` is `environment: "node"`), so anything worth testing has to be a plain module.
 * The rules here are all worth testing — which files are refused and why, how an upload turns into a
 * settled chip, and what the drag counter does — and none of them need a DOM.
 */

export type AttachmentStatus = "uploading" | "ready" | "failed";

/** One chip in the composer. */
export interface AttachmentItem {
  /** Stable across the upload; becomes the server uuid once the upload settles. */
  id: string;
  filename: string;
  mimeType?: string;
  size: number;
  status: AttachmentStatus;
  /** 0..1 while uploading, undefined once settled or when the length is unknown. */
  progress?: number;
  /** Why it failed, shown on the chip. */
  error?: string;
  /** The server-side uuid, present once the upload has succeeded. */
  uuid?: string;
}

/** What the chip promises the agent can do with this file. */
export type AttachmentKind = "text" | "image" | "opaque";

export function kindOf(mimeType?: string): AttachmentKind {
  if (isReadableKind(mimeType)) return "text";
  if (isImageKind(mimeType)) return "image";
  return "opaque";
}

/** A settled item built from what the server stored. */
export function toItem(response: ChatAttachmentResponse): AttachmentItem {
  return {
    id: response.uuid,
    uuid: response.uuid,
    filename: response.filename,
    mimeType: response.mimeType,
    size: response.size,
    status: "ready",
  };
}

export interface AcceptDecision {
  accepted: File[];
  /** One message per refused file, ready to show as a toast. */
  rejected: string[];
}

/**
 * Decide which dropped files may be uploaded.
 *
 * <p>Checked here rather than left to the server so the user finds out at drop time. The server
 * enforces the same two limits again — this is a courtesy, not the boundary.</p>
 *
 * @param existing how many files the chat already carries, including in-flight ones
 */
export function acceptFiles(
  files: File[],
  existing: number,
  limits: { maxFiles: number; maxBytes: number }
): AcceptDecision {
  const accepted: File[] = [];
  const rejected: string[] = [];
  let room = Math.max(limits.maxFiles - existing, 0);

  for (const file of files) {
    if (file.size > limits.maxBytes) {
      rejected.push(`${file.name} is too large (limit ${formatBytes(limits.maxBytes)})`);
      continue;
    }
    if (room === 0) {
      rejected.push(`${file.name} was not attached: a conversation holds at most ${limits.maxFiles} files`);
      continue;
    }
    // A directory dropped onto the page arrives as a zero-byte File with no type. Uploading it
    // produces an empty attachment the agent would then be told it can read.
    if (file.size === 0) {
      rejected.push(`${file.name} is empty, or is a folder`);
      continue;
    }
    accepted.push(file);
    room -= 1;
  }
  return { accepted, rejected };
}

/**
 * Whether a drag carries files, as opposed to text or an element being reordered.
 *
 * The chat already has internal drags (the split divider), and highlighting the whole column when
 * somebody selects a word would be noise. `types` is the only thing readable during `dragover` —
 * the files themselves are not exposed until `drop`.
 */
export function dragCarriesFiles(types: readonly string[] | undefined): boolean {
  return !!types && Array.from(types).includes("Files");
}

/**
 * Bytes as a person would write them.
 *
 * Matches `AttachmentPromptBuilder.humanSize` on the server so the size on a chip and the size the
 * agent quotes back are the same number.
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

/** Replace one item in the list, leaving the rest untouched. */
export function replaceItem(items: AttachmentItem[], id: string, patch: Partial<AttachmentItem>): AttachmentItem[] {
  return items.map(item => (item.id === id ? { ...item, ...patch } : item));
}
