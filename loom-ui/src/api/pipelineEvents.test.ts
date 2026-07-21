import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { computeReconnectDelay, type ReconnectOptions } from "./pipelineEvents";

const OPTS = (over: Partial<ReconnectOptions> = {}): ReconnectOptions => ({
  baseDelayMs: 1000,
  maxDelayMs: 30000,
  maxAttempts: 10,
  jitter: false,
  ...over,
});

// --- Fake WebSocket ---------------------------------------------------------
//
// vitest runs in a node environment where the WebSocket global is absent, so we
// stub a minimal fake that records instances and lets a test drive the socket
// lifecycle by hand.

interface CloseEvent {
  code: number;
}

class FakeWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  url: string;
  readyState = FakeWebSocket.CONNECTING;
  onopen: (() => void) | null = null;
  onclose: ((e: CloseEvent) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  close(): void {
    if (this.readyState === FakeWebSocket.CLOSED) return;
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.({ code: 1000 });
  }

  // Test helpers -----------------------------------------------------------
  open(): void {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }

  serverClose(code = 1006): void {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.({ code });
  }

  static reset(): void {
    FakeWebSocket.instances = [];
  }

  static get last(): FakeWebSocket {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  }
}

describe("computeReconnectDelay", () => {
  it("grows exponentially: base * 2^attempt", () => {
    const o = OPTS();
    expect(computeReconnectDelay(0, o)).toBe(1000);
    expect(computeReconnectDelay(1, o)).toBe(2000);
    expect(computeReconnectDelay(2, o)).toBe(4000);
    expect(computeReconnectDelay(3, o)).toBe(8000);
    expect(computeReconnectDelay(4, o)).toBe(16000);
  });

  it("caps the delay at maxDelayMs", () => {
    const o = OPTS({ maxDelayMs: 30000 });
    // 2^5 * 1000 = 32000 would exceed the cap.
    expect(computeReconnectDelay(5, o)).toBe(30000);
    expect(computeReconnectDelay(20, o)).toBe(30000);
  });

  it("with jitter, stays within [0.5x, 1.5x) of the capped delay", () => {
    const o = OPTS({ jitter: true });
    const randSpy = vi.spyOn(Math, "random");

    randSpy.mockReturnValue(0); // 0.5x
    expect(computeReconnectDelay(1, o)).toBe(1000);

    randSpy.mockReturnValue(0.9999); // ~1.5x
    expect(computeReconnectDelay(1, o)).toBeCloseTo(2999.8, 1);

    randSpy.mockRestore();
  });

  it("with jitter, different randoms yield different delays (adds randomness)", () => {
    const o = OPTS({ jitter: true });
    const randSpy = vi.spyOn(Math, "random");

    randSpy.mockReturnValueOnce(0.1);
    const a = computeReconnectDelay(3, o);
    randSpy.mockReturnValueOnce(0.8);
    const b = computeReconnectDelay(3, o);

    expect(a).not.toBe(b);
    randSpy.mockRestore();
  });

  it("without jitter, the delay is deterministic", () => {
    const o = OPTS({ jitter: false });
    expect(computeReconnectDelay(2, o)).toBe(computeReconnectDelay(2, o));
  });
});

describe("pipelineEvents reconnection lifecycle", () => {
  let mod: typeof import("./pipelineEvents");

  beforeEach(async () => {
    vi.resetModules();
    vi.useFakeTimers();
    FakeWebSocket.reset();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    // Fresh module instance so the shared singleton state is reset per test.
    mod = await import("./pipelineEvents");
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("emits connecting → connected on a successful handshake", () => {
    const states: string[] = [];
    mod.subscribeConnectionState((s) => states.push(s));

    const unsub = mod.subscribePipelineEvents(() => {});
    expect(FakeWebSocket.instances).toHaveLength(1);
    FakeWebSocket.last.open();

    expect(states).toEqual(["connecting", "connected"]);
    unsub();
  });

  it("reconnects with backoff after an unexpected close", () => {
    mod.configureReconnect({ baseDelayMs: 1000, maxDelayMs: 30000, jitter: false, maxAttempts: 10 });
    const states: string[] = [];
    mod.subscribeConnectionState((s) => states.push(s));

    const unsub = mod.subscribePipelineEvents(() => {});
    FakeWebSocket.last.open();

    // Server drops the connection.
    FakeWebSocket.last.serverClose(1006);
    expect(states).toContain("disconnected");
    // No immediate reconnect — a timer is scheduled first.
    expect(FakeWebSocket.instances).toHaveLength(1);

    // First backoff is baseDelayMs (attempt 0). Just before it fires: nothing.
    vi.advanceTimersByTime(999);
    expect(FakeWebSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeWebSocket.instances).toHaveLength(2); // reconnected

    unsub();
  });

  it("stops after maxAttempts and emits a failed state", () => {
    mod.configureReconnect({ baseDelayMs: 10, maxDelayMs: 10, jitter: false, maxAttempts: 3 });
    const states: string[] = [];
    mod.subscribeConnectionState((s) => states.push(s));

    mod.subscribePipelineEvents(() => {});

    // Drive close→reconnect cycles until the budget is exhausted.
    for (let i = 0; i < 3; i++) {
      FakeWebSocket.last.serverClose(1006);
      vi.advanceTimersByTime(10);
    }
    // 1 initial + 3 reconnects = 4 sockets created.
    expect(FakeWebSocket.instances).toHaveLength(4);

    // The 4th close exceeds maxAttempts: no new socket, failed emitted.
    FakeWebSocket.last.serverClose(1006);
    expect(FakeWebSocket.instances).toHaveLength(4);
    expect(states).toContain("failed");
  });

  it("does not reconnect on an unauthorized (4401) close", () => {
    const states: string[] = [];
    mod.subscribeConnectionState((s) => states.push(s));

    mod.subscribePipelineEvents(() => {});
    FakeWebSocket.last.serverClose(4401);

    vi.advanceTimersByTime(60000);
    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(states).toContain("failed");
  });

  it("aborts a pending reconnection when the last listener unsubscribes", () => {
    mod.configureReconnect({ baseDelayMs: 1000, jitter: false, maxAttempts: 10 });

    const unsub = mod.subscribePipelineEvents(() => {});
    FakeWebSocket.last.open();
    FakeWebSocket.last.serverClose(1006); // schedules a reconnect timer

    unsub(); // unmount before the timer fires

    vi.advanceTimersByTime(60000);
    // No reconnection socket should have been created after teardown.
    expect(FakeWebSocket.instances).toHaveLength(1);
  });
});
