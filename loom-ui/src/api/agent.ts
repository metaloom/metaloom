import { API_BASE_URL } from "./config";
import { BackendChatReference } from "./chat";

/**
 * Client for the chat agent stream route (POST /chats/:uuid/stream, SSE).
 *
 * EventSource can neither POST a body nor send the Authorization header, so the
 * stream is consumed via fetch + ReadableStream with an incremental SSE parser.
 * Event vocabulary: CHAT.md §4.2.
 */

export type AgentEventType =
  | "agent_start"
  | "turn_start"
  | "reasoning_delta"
  | "text_delta"
  | "tool_start"
  | "tool_end"
  | "turn_end"
  | "message_end"
  | "title"
  | "error"
  | "agent_end";

export interface AgentStartEvent {
  chatUuid: string;
  model: string;
  maxTurns: number;
}

export interface AgentDeltaEvent {
  turn: number;
  text: string;
}

export interface AgentToolStartEvent {
  turn: number;
  toolCallId: string;
  name: string;
  args: Record<string, unknown>;
}

export interface AgentToolEndEvent {
  turn: number;
  toolCallId: string;
  name: string;
  isError: boolean;
  summary: string;
  references: BackendChatReference[];
}

export interface AgentMessageEndEvent {
  message: Record<string, unknown>;
}

export interface AgentTitleEvent {
  title: string;
}

export interface AgentErrorEvent {
  code: string;
  message: string;
  terminal: boolean;
}

export interface AgentEndEvent {
  chatUuid: string;
  status: "completed" | "aborted" | "error";
}

export type AgentEventHandler = (type: AgentEventType, data: unknown) => void;

export class AgentBusyError extends Error {
  constructor() {
    super("An agent run is already active for this chat");
    this.name = "AgentBusyError";
  }
}

export interface StreamChatRequest {
  message: string;
  skillUuids?: string[];
  think?: boolean;
}

/**
 * Incremental SSE parser. Feed it network chunks; it invokes the handler once per
 * complete `event:`/`data:` record. Handles records split across chunk boundaries
 * (including mid-line and mid-multibyte via the TextDecoder stream option) and
 * ignores unknown fields/comments for forward compatibility.
 */
export function createSseParser(onEvent: AgentEventHandler): (chunk: string) => void {
  let buffer = "";
  return (chunk: string) => {
    buffer += chunk;
    // A record is terminated by a blank line
    let sep: number;
    while ((sep = buffer.indexOf("\n\n")) >= 0) {
      const frame = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      let type: string | null = null;
      let data = "";
      for (const line of frame.split("\n")) {
        if (line.startsWith("event:")) {
          type = line.slice("event:".length).trim();
        } else if (line.startsWith("data:")) {
          // Multi-line data fields are joined per SSE spec
          data += (data ? "\n" : "") + line.slice("data:".length).trimStart();
        }
      }
      if (type && data) {
        try {
          onEvent(type as AgentEventType, JSON.parse(data));
        } catch {
          // Malformed data — skip the frame rather than killing the stream
        }
      }
    }
  };
}

/**
 * Send a user message to the agent and stream the run events back.
 * Resolves when the stream ends; resolves silently on abort via the signal.
 * Rejects with AgentBusyError on 409 and Error on other non-2xx responses.
 */
export async function streamChatMessage(
  token: string,
  chatUuid: string,
  request: StreamChatRequest,
  onEvent: AgentEventHandler,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}/chats/${encodeURIComponent(chatUuid)}/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        message: request.message,
        skillUuids: request.skillUuids ?? [],
        ...(request.think !== undefined ? { think: request.think } : {}),
      }),
      signal,
    });
  } catch (e) {
    if (signal?.aborted) return;
    throw e;
  }

  if (res.status === 409) {
    throw new AgentBusyError();
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  if (!res.body) {
    throw new Error("Streaming is not supported by this environment");
  }

  const parse = createSseParser(onEvent);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parse(decoder.decode(value, { stream: true }));
    }
    // Flush a trailing partial multibyte sequence (a trailing partial SSE record is dropped by design)
    parse(decoder.decode());
  } catch (e) {
    if (signal?.aborted) return;
    throw e;
  }
}

/** Explicitly cancel the active agent run of the chat (idempotent, 204). */
export async function cancelChatStream(token: string, chatUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/chats/${encodeURIComponent(chatUuid)}/stream`, {
    method: "DELETE",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
