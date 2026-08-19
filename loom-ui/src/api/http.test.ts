import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, SESSION_EXPIRED_EVENT, authHeaders, handleResponse, isApiError, noteUnauthorized, traceIdOf } from "./http";

/**
 * The shared response handler that replaced 36 private copies.
 *
 * The three things the copies threw away - the trace id, the status as a number, and the fact that
 * a 401 is a session event rather than one more failed widget - are what these assert.
 */

const TRACE = "9f2c41ab7d0e4c6fa1b83e5d72c09148";

function response(init: {
  ok?: boolean;
  status: number;
  body?: unknown;
  text?: string;
  headers?: Record<string, string>;
  url?: string;
}): Response {
  const headers = init.headers;
  return {
    ok: init.ok ?? (init.status >= 200 && init.status < 300),
    status: init.status,
    url: init.url ?? "http://loom.test/api/v1/persons",
    headers: headers
      ? ({ get: (name: string) => headers[name] ?? null } as unknown as Headers)
      : (undefined as unknown as Headers),
    json: async () => init.body,
    text: async () => init.text ?? "",
  } as Response;
}

/**
 * These tests run in the node environment (there is no jsdom in this repo - LOOM_UI.md 8.1), so
 * `window` does not exist. `http.ts` guards for that, which is why a stand-in is installed here
 * rather than the module being changed: the event path is real behaviour and has to be asserted,
 * and a plain `EventTarget` is exactly the part of `window` it uses.
 */
function withWindow(assertions: (listener: ReturnType<typeof vi.fn>) => void | Promise<void>) {
  const target = new EventTarget();
  vi.stubGlobal("window", target);
  const listener = vi.fn();
  target.addEventListener(SESSION_EXPIRED_EVENT, listener);
  return Promise.resolve(assertions(listener)).finally(() => {
    target.removeEventListener(SESSION_EXPIRED_EVENT, listener);
    vi.unstubAllGlobals();
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("authHeaders", () => {
  it("is the same shape the 36 copies produced", () => {
    expect(authHeaders("abc")).toEqual({
      "Content-Type": "application/json",
      Authorization: "Bearer abc",
    });
  });
});

describe("handleResponse", () => {
  it("returns the parsed body on 2xx", async () => {
    await expect(handleResponse(response({ status: 200, body: { uuid: "x" } }))).resolves.toEqual({ uuid: "x" });
  });

  it("returns undefined for 204 rather than trying to parse an empty body", async () => {
    await expect(handleResponse(response({ status: 204 }))).resolves.toBeUndefined();
  });

  it("throws an ApiError carrying the status", async () => {
    const error = await handleResponse(response({ status: 500, text: "boom" })).catch(e => e);
    expect(isApiError(error)).toBe(true);
    expect((error as ApiError).status).toBe(500);
  });

  it("carries the trace id from the response header", async () => {
    const error = await handleResponse(
      response({ status: 500, headers: { "X-Trace-Id": TRACE } }),
    ).catch(e => e);
    expect((error as ApiError).traceId).toBe(TRACE);
  });

  it("falls back to the traceId in the body when the header is hidden", async () => {
    // The cross-origin case: without Access-Control-Expose-Headers the browser hides the header
    // from JS entirely, and the body copy is the only way the id is still reachable.
    const error = await handleResponse(
      response({ status: 500, text: JSON.stringify({ message: "Internal Server Error", traceId: TRACE }) }),
    ).catch(e => e);
    expect((error as ApiError).traceId).toBe(TRACE);
  });

  it("prefers the server's message over the generic one", async () => {
    const error = await handleResponse(
      response({ status: 409, text: JSON.stringify({ message: "The resource already exists." }) }),
    ).catch(e => e);
    expect((error as ApiError).serverMessage).toBe("The resource already exists.");
    expect((error as Error).message).toBe("The resource already exists.");
  });

  it("does not render a non-JSON body as the message", async () => {
    // An HTML error page from a proxy must not end up in a toast.
    const error = await handleResponse(response({ status: 502, text: "<html>Bad Gateway</html>" })).catch(e => e);
    expect((error as ApiError).serverMessage).toBeNull();
  });

  it("records the action and path for the report", async () => {
    const error = await handleResponse(response({ status: 500 }), {
      action: "createPerson",
      method: "POST",
    }).catch(e => e);
    expect((error as ApiError).action).toBe("createPerson");
    expect((error as ApiError).method).toBe("POST");
    expect((error as ApiError).path).toBe("/api/v1/persons");
  });

  it("survives a response object with no headers at all", async () => {
    // Hand-rolled stubs in the older api tests have no `headers`. A missing trace id must never be
    // the reason a request fails.
    await expect(handleResponse(response({ status: 200, body: { ok: true } }))).resolves.toEqual({ ok: true });
    expect(traceIdOf(response({ status: 200 }))).toBeNull();
  });

  it("emits one session-expired event per 401", async () =>
    withWindow(async listener => {
      await handleResponse(response({ status: 401 })).catch(() => {});
      expect(listener).toHaveBeenCalledTimes(1);

      // Ten parallel 401s emit ten events; collapsing them into one message is AuthProvider's job,
      // because only it knows whether it has already logged out.
      await Promise.all(
        Array.from({ length: 10 }, () => handleResponse(response({ status: 401 })).catch(() => {})),
      );
      expect(listener).toHaveBeenCalledTimes(11);
    }));

  it("does not emit the event for any other status", async () =>
    withWindow(async listener => {
      await handleResponse(response({ status: 403 })).catch(() => {});
      await handleResponse(response({ status: 500 })).catch(() => {});
      expect(listener).not.toHaveBeenCalled();
    }));

  it("still throws when there is no window to dispatch into", async () => {
    // Server-side rendering, a worker, a node test: the failure must still surface as an ApiError
    // rather than a TypeError about `window`.
    const error = await handleResponse(response({ status: 401 })).catch(e => e);
    expect((error as ApiError).status).toBe(401);
  });
});

describe("noteUnauthorized", () => {
  it("is the hook the typed per-module error classes use", async () =>
    withWindow(listener => {
      noteUnauthorized(401);
      noteUnauthorized(404);
      expect(listener).toHaveBeenCalledTimes(1);
    }));
});
