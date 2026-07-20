import { API_BASE_URL } from "./config";
import type { Processor } from "./processors";

// --- Types ---

export type PipelineEventType =
  | "PIPELINE_STARTED"
  | "PIPELINE_COMPLETED"
  | "NODE_STARTED"
  | "NODE_COMPLETED"
  | "NODE_FAILED"
  | "NODE_SKIPPED"
  | "NODE_BUFFERED"
  | "NODE_STATS";

export interface PipelineEventMessage {
  type: PipelineEventType;
  pipelineName: string;
  nodeId?: string;
  mediaPath?: string;
  timestamp: number;
  durationMs?: number;
  message?: string;
  activeCount?: number;
  pendingCount?: number;
  processedCount?: number;
  failedCount?: number;
}

// Processor lifecycle events share this same socket, discriminated by
// `channel === "PROCESSOR"`. Pipeline frames carry no `channel` field.

export type ProcessorEventType =
  | "REGISTERED"
  | "STATE_CHANGED"
  | "STATUS_UPDATED"
  | "HEARTBEAT"
  | "DISCONNECTED";

export interface ProcessorEventMessage {
  channel: "PROCESSOR";
  type: ProcessorEventType;
  nodeId: string;
  processor?: Processor;
  lastSeen?: string;
}

// --- WebSocket URL derivation ---

function buildWsUrl(token: string | null): string {
  // Convert http(s)://host/api/v1 → ws(s)://host/api/v1/pipelines/events/ws
  const base = API_BASE_URL.replace(/^http/, "ws");
  const url = `${base}/pipelines/events/ws`;
  return token ? `${url}?token=${encodeURIComponent(token)}` : url;
}

// --- Listener management ---
//
// A single module-level WebSocket is shared by all subscribers. Incoming frames
// are routed to the pipeline or processor listener set by their `channel` field,
// so the UI never opens more than one socket to the backend.

type PipelineEventListener = (event: PipelineEventMessage) => void;
type ProcessorEventListener = (event: ProcessorEventMessage) => void;

let ws: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectAttempts = 0;
let currentToken: string | null = null;
const pipelineListeners = new Set<PipelineEventListener>();
const processorListeners = new Set<ProcessorEventListener>();

function totalListeners(): number {
  return pipelineListeners.size + processorListeners.size;
}

function ensureConnection() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }

  const url = buildWsUrl(currentToken);
  ws = new WebSocket(url);

  ws.onopen = () => {
    reconnectAttempts = 0;
    console.log("[ui-events] WebSocket connected");
  };

  ws.onmessage = (e) => {
    try {
      const event = JSON.parse(e.data);
      if (event && event.channel === "PROCESSOR") {
        for (const listener of processorListeners) {
          listener(event as ProcessorEventMessage);
        }
      } else {
        for (const listener of pipelineListeners) {
          listener(event as PipelineEventMessage);
        }
      }
    } catch (err) {
      console.warn("[ui-events] Failed to parse event:", err);
    }
  };

  ws.onclose = (e) => {
    ws = null;
    // 4401 = unauthorized close code from server; do not attempt to reconnect
    if (e.code === 4401) {
      console.error("[ui-events] Unauthorized, WebSocket closed");
      return;
    }
    if (totalListeners() > 0) {
      // Exponential backoff: 1s, 2s, 4s, … capped at 30s.
      const delay = Math.min(30000, 1000 * 2 ** reconnectAttempts);
      reconnectAttempts += 1;
      reconnectTimer = setTimeout(ensureConnection, delay);
    }
  };

  ws.onerror = (err) => {
    console.error("[ui-events] WebSocket error:", err);
    ws?.close();
  };
}

/**
 * Register a listener on the shared UI events socket. Opens the connection on
 * the first subscriber (re-connecting if the token changed) and tears it down
 * once the last listener across both channels unsubscribes. Returns an
 * unsubscribe function.
 */
function subscribe(add: () => void, remove: () => void, token: string | null): () => void {
  // If the token changed while a connection was open, tear it down so the
  // new subscription re-connects with the fresh token.
  if (token !== currentToken && ws) {
    ws.close();
    ws = null;
    reconnectAttempts = 0;
  }
  currentToken = token;
  add();
  ensureConnection();

  return () => {
    remove();
    if (totalListeners() === 0) {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      reconnectAttempts = 0;
      ws?.close();
      ws = null;
      currentToken = null;
    }
  };
}

/**
 * Subscribe to live pipeline events. Returns an unsubscribe function.
 *
 * @param listener callback invoked for each pipeline event
 * @param token   optional bearer token used to authenticate the WebSocket handshake
 */
export function subscribePipelineEvents(listener: PipelineEventListener, token: string | null = null): () => void {
  return subscribe(
    () => pipelineListeners.add(listener),
    () => pipelineListeners.delete(listener),
    token,
  );
}

/**
 * Subscribe to live processor lifecycle events over the same shared socket.
 * Returns an unsubscribe function.
 *
 * @param listener callback invoked for each processor event
 * @param token   optional bearer token used to authenticate the WebSocket handshake
 */
export function subscribeProcessorEvents(listener: ProcessorEventListener, token: string | null = null): () => void {
  return subscribe(
    () => processorListeners.add(listener),
    () => processorListeners.delete(listener),
    token,
  );
}
