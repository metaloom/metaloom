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

function buildWsUrl(): string {
  // Convert http(s)://host/api/v1 → ws(s)://host/api/v1/pipelines/events/ws
  const base = API_BASE_URL.replace(/^http/, "ws");
  return `${base}/pipelines/events/ws`;
}

// --- Listener management ---

type PipelineEventListener = (event: PipelineEventMessage) => void;

let ws: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<PipelineEventListener>();

function ensureConnection() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }

  const url = buildWsUrl();
  ws = new WebSocket(url);

  ws.onopen = () => {
    console.log("[pipeline-events] WebSocket connected to", url);
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

  ws.onclose = () => {
    ws = null;
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
 */
export function subscribePipelineEvents(listener: PipelineEventListener): () => void {
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
    }
  };
}
