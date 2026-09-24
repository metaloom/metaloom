import { useCallback, useEffect, useRef, useState } from "react";

import {
  AttachmentUploadAbortedError,
  deleteChatAttachment,
  listChatAttachments,
  promoteChatAttachment,
  uploadChatAttachment,
} from "../../api/chatAttachments";
import { AttachmentItem, acceptFiles, replaceItem, toItem } from "./attachmentState";

/**
 * The composer's attachments: upload, list, remove, and save to the library.
 *
 * <p>Two things here are load-bearing.</p>
 *
 * <p><b>The chat has to exist first.</b> A conversation is created lazily on the first message, so
 * dropping a file into a brand-new chat has nothing to attach it to. `ensureChat` is the same
 * function `sendMessage` uses, passed in rather than duplicated, so a drop and a first message
 * create the session identically.</p>
 *
 * <p><b>An in-flight upload is a chip too.</b> The item appears the moment the file is dropped and
 * is patched in place as the bytes go out, so a large file does not look like nothing happened. Its
 * temporary id is replaced by the server uuid on success — that uuid is what the agent is given and
 * what `generate_image` resolves, so nothing may reference the item until it has settled.</p>
 */
export interface ChatAttachmentsApi {
  items: AttachmentItem[];
  /** Attach files, creating the chat if this is the first thing to happen in it. */
  add: (files: File[]) => Promise<void>;
  remove: (item: AttachmentItem) => Promise<void>;
  save: (item: AttachmentItem) => Promise<void>;
  /** Replace the list when a different conversation is opened. */
  load: (chatUuid: string | null) => void;
  /** Start again with nothing, for a new chat. */
  clear: () => void;
  busy: boolean;
}

export interface ChatAttachmentLimits {
  maxFiles: number;
  maxBytes: number;
}

export function useChatAttachments(
  token: string | null,
  chatUuid: string | null,
  ensureChat: () => Promise<string>,
  limits: ChatAttachmentLimits,
  onError: (message: string) => void
): ChatAttachmentsApi {
  const [items, setItems] = useState<AttachmentItem[]>([]);
  const [busy, setBusy] = useState(false);
  // Read inside async callbacks, where a captured `items` would be the list from before the drop.
  const itemsRef = useRef<AttachmentItem[]>([]);
  useEffect(() => {
    itemsRef.current = items;
  }, [items]);

  const load = useCallback(
    (uuid: string | null) => {
      if (!token || !uuid) {
        setItems([]);
        return;
      }
      listChatAttachments(token, uuid)
        .then(res => setItems((res.data ?? []).map(toItem)))
        // A conversation whose attachments cannot be listed is still usable; the chips are missing,
        // not the chat.
        .catch(e => console.error("Failed to list chat attachments", e));
    },
    [token]
  );

  const clear = useCallback(() => setItems([]), []);

  const add = useCallback(
    async (files: File[]) => {
      if (!token || files.length === 0) return;

      const { accepted, rejected } = acceptFiles(files, itemsRef.current.length, limits);
      rejected.forEach(onError);
      if (accepted.length === 0) return;

      let target = chatUuid;
      if (!target) {
        try {
          target = await ensureChat();
        } catch (e) {
          console.error("Could not start a conversation for the attachment", e);
          onError("The conversation could not be started, so the file was not attached.");
          return;
        }
      }

      setBusy(true);
      try {
        for (const file of accepted) {
          // Unique per drop rather than per name: dropping the same file twice is two attachments,
          // and two chips sharing an id would patch each other's progress.
          const tempId = `pending_${Date.now()}_${Math.random().toString(36).slice(2)}`;
          setItems(prev => [
            ...prev,
            { id: tempId, filename: file.name, mimeType: file.type, size: file.size, status: "uploading", progress: 0 },
          ]);

          try {
            const handle = uploadChatAttachment(token, target, file, {
              onProgress: p => {
                setItems(prev =>
                  replaceItem(prev, tempId, { progress: p.total > 0 ? p.loaded / p.total : undefined })
                );
              },
            });
            const stored = await handle.promise;
            setItems(prev => replaceItem(prev, tempId, { ...toItem(stored), id: tempId }));
          } catch (e) {
            if (e instanceof AttachmentUploadAbortedError) {
              setItems(prev => prev.filter(item => item.id !== tempId));
              continue;
            }
            console.error("Failed to attach file", e);
            setItems(prev => replaceItem(prev, tempId, { status: "failed", progress: undefined, error: message(e) }));
          }
        }
      } finally {
        setBusy(false);
      }
    },
    [token, chatUuid, ensureChat, limits, onError]
  );

  const remove = useCallback(
    async (item: AttachmentItem) => {
      // A failed upload has no server row, so it is only a chip and goes without a round trip.
      if (!token || !chatUuid || !item.uuid) {
        setItems(prev => prev.filter(i => i.id !== item.id));
        return;
      }
      try {
        await deleteChatAttachment(token, chatUuid, item.uuid);
        setItems(prev => prev.filter(i => i.id !== item.id));
      } catch (e) {
        console.error("Failed to detach file", e);
        onError(`${item.filename} could not be removed.`);
      }
    },
    [token, chatUuid, onError]
  );

  const save = useCallback(
    async (item: AttachmentItem) => {
      if (!token || !chatUuid || !item.uuid) return;
      try {
        await promoteChatAttachment(token, chatUuid, item.uuid);
      } catch (e) {
        console.error("Failed to save attachment to the library", e);
        onError(`${item.filename} could not be saved to the library.`);
      }
    },
    [token, chatUuid, onError]
  );

  return { items, add, remove, save, load, clear, busy };
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : "Upload failed";
}
