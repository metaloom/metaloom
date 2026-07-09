import { API_BASE_URL } from "./config";

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

// --- WebSocket URL derivation ---

function buildWsUrl(token: string | null): string {
  // Convert http(s)://host/api/v1 → ws(s)://host/api/v1/pipelines/events/ws
  const base = API_BASE_URL.replace(/^http/, "ws");
  const url = `${base}/pipelines/events/ws`;
  return token ? `${url}?token=${encodeURIComponent(token)}` : url;
}

// --- Listener management ---

type PipelineEventListener = (event: PipelineEventMessage) => void;

let ws: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let currentToken: string | null = null;
const listeners = new Set<PipelineEventListener>();

function ensureConnection() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }

  const url = buildWsUrl(currentToken);
  ws = new WebSocket(url);

  ws.onopen = () => {
    console.log("[pipeline-events] WebSocket connected");
  };

  ws.onmessage = (e) => {
    try {
      const event: PipelineEventMessage = JSON.parse(e.data);
      for (const listener of listeners) {
        listener(event);
      }
    } catch (err) {
      console.warn("[pipeline-events] Failed to parse event:", err);
    }
  };

  ws.onclose = (e) => {
    ws = null;
    // 4401 = unauthorized close code from server; do not attempt to reconnect
    if (e.code === 4401) {
      console.error("[pipeline-events] Unauthorized, WebSocket closed");
      return;
    }
    if (listeners.size > 0) {
      reconnectTimer = setTimeout(ensureConnection, 3000);
    }
  };

  ws.onerror = (err) => {
    console.error("[pipeline-events] WebSocket error:", err);
    ws?.close();
  };
}

/**
 * Subscribe to live pipeline events. Returns an unsubscribe function.
 * The WebSocket connection is lazily opened on the first subscription
 * and closed when the last listener unsubscribes.
 *
 * @param listener callback invoked for each event
 * @param token   optional bearer token used to authenticate the WebSocket handshake
 */
export function subscribePipelineEvents(listener: PipelineEventListener, token: string | null = null): () => void {
  // If the token changed while a connection was open, tear it down so the
  // new subscription re-connects with the fresh token.
  if (token !== currentToken && ws) {
    ws.close();
    ws = null;
  }
  currentToken = token;
  listeners.add(listener);
  ensureConnection();

  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      ws?.close();
      ws = null;
      currentToken = null;
    }
  };
}
