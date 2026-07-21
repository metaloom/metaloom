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

// --- Reconnection configuration ---
//
// Reconnections use exponential backoff with jitter and a bounded number of
// attempts. Jitter spreads retries out so that many clients waking from a
// server restart don't reconnect in lock-step (thundering herd).

export interface ReconnectOptions {
  /** First-attempt delay in ms; doubled on each subsequent attempt. */
  baseDelayMs: number;
  /** Upper bound on the (pre-jitter) backoff delay in ms. */
  maxDelayMs: number;
  /** Maximum number of reconnection attempts before giving up. */
  maxAttempts: number;
  /** When true, multiply the delay by 0.5 + random() to de-synchronize peers. */
  jitter: boolean;
}

const DEFAULT_RECONNECT_OPTIONS: ReconnectOptions = {
  baseDelayMs: 1000,
  maxDelayMs: 30000,
  maxAttempts: 10,
  jitter: true,
};

let reconnectOptions: ReconnectOptions = { ...DEFAULT_RECONNECT_OPTIONS };

/** Override reconnection behaviour. Merges over the current options. */
export function configureReconnect(opts: Partial<ReconnectOptions>): void {
  reconnectOptions = { ...reconnectOptions, ...opts };
}

/** Current reconnection options (a copy — mutating it has no effect). */
export function getReconnectOptions(): ReconnectOptions {
  return { ...reconnectOptions };
}

/**
 * Backoff delay for a given zero-based attempt:
 *
 *   delay = min(baseDelay * 2^attempt, maxDelay)
 *
 * and, when jitter is enabled, `delay *= 0.5 + random()` so the effective
 * delay lands anywhere in [0.5x, 1.5x) of the capped value.
 */
export function computeReconnectDelay(
  attempt: number,
  opts: ReconnectOptions = reconnectOptions,
): number {
  const capped = Math.min(opts.maxDelayMs, opts.baseDelayMs * 2 ** attempt);
  if (!opts.jitter) {
    return capped;
  }
  return capped * (0.5 + Math.random());
}

// --- Connection state events ---

export type ConnectionState = "connecting" | "connected" | "disconnected" | "failed";
type ConnectionStateListener = (state: ConnectionState) => void;
const connectionStateListeners = new Set<ConnectionStateListener>();

/**
 * Observe connection-lifecycle transitions: `connecting` (socket opening),
 * `connected` (handshake complete), `disconnected` (socket closed, a retry may
 * follow) and `failed` (retry budget exhausted). Returns an unsubscribe fn.
 */
export function subscribeConnectionState(listener: ConnectionStateListener): () => void {
  connectionStateListeners.add(listener);
  return () => {
    connectionStateListeners.delete(listener);
  };
}

function emitConnectionState(state: ConnectionState): void {
  for (const listener of connectionStateListeners) {
    listener(state);
  }
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
  emitConnectionState("connecting");

  ws.onopen = () => {
    reconnectAttempts = 0;
    emitConnectionState("connected");
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
    emitConnectionState("disconnected");
    // 4401 = unauthorized close code from server; do not attempt to reconnect
    if (e.code === 4401) {
      console.error("[ui-events] Unauthorized, WebSocket closed");
      emitConnectionState("failed");
      return;
    }
    if (totalListeners() > 0) {
      if (reconnectAttempts >= reconnectOptions.maxAttempts) {
        console.error(
          `[ui-events] Giving up after ${reconnectAttempts} reconnection attempts`,
        );
        emitConnectionState("failed");
        return;
      }
      // Exponential backoff with jitter (see computeReconnectDelay).
      const delay = computeReconnectDelay(reconnectAttempts);
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
