import { describe, expect, it, vi } from "vitest";
import { bindUnloadWarning, runGuarded, type UnloadTarget } from "./useUnsavedChanges";

/**
 * The hooks themselves need a renderer this repo does not have (§8.1), so the listener wiring and
 * the guard dispatch live in plain functions and are tested here.
 */

/** Stands in for `window`, recording what was added and removed. */
function fakeWindow() {
  const listeners: Array<(e: BeforeUnloadEvent) => void> = [];
  const target: UnloadTarget = {
    addEventListener: (_type, listener) => {
      listeners.push(listener);
    },
    removeEventListener: (_type, listener) => {
      const idx = listeners.indexOf(listener);
      if (idx >= 0) listeners.splice(idx, 1);
    },
  };
  return { target, listeners };
}

/** A `beforeunload` event with only the two members the handler touches. */
function fakeEvent() {
  return { preventDefault: vi.fn(), returnValue: undefined as unknown } as unknown as
    BeforeUnloadEvent & { preventDefault: ReturnType<typeof vi.fn> };
}

describe("bindUnloadWarning", () => {
  it("registers a beforeunload listener", () => {
    const { target, listeners } = fakeWindow();
    bindUnloadWarning(target, "unsaved");
    expect(listeners).toHaveLength(1);
  });

  it("removes the listener it added, and only that one", () => {
    const { target, listeners } = fakeWindow();
    const other = () => {};
    target.addEventListener("beforeunload", other);
    const teardown = bindUnloadWarning(target, "unsaved");
    expect(listeners).toHaveLength(2);

    teardown();
    expect(listeners).toEqual([other]);
  });

  it("survives a double teardown without disturbing a re-registered listener", () => {
    // React's strict mode runs effect setup/teardown twice; a teardown that removed by type
    // rather than by identity would take the second registration's listener with it.
    const { target, listeners } = fakeWindow();
    const first = bindUnloadWarning(target, "unsaved");
    first();
    bindUnloadWarning(target, "unsaved");
    first();
    expect(listeners).toHaveLength(1);
  });

  it("cancels the event and sets returnValue — both are needed for the browser to prompt", () => {
    const { target, listeners } = fakeWindow();
    bindUnloadWarning(target, "unsaved edits");

    const e = fakeEvent();
    listeners[0](e);
    expect(e.preventDefault).toHaveBeenCalled();
    expect(e.returnValue).toBe("unsaved edits");
  });

  it("does not warn once torn down", () => {
    const { target, listeners } = fakeWindow();
    const teardown = bindUnloadWarning(target, "unsaved");
    teardown();
    expect(listeners).toHaveLength(0);
  });
});

describe("runGuarded", () => {
  it("navigates straight away when no screen is guarding", () => {
    const proceed = vi.fn();
    runGuarded(null, proceed);
    expect(proceed).toHaveBeenCalledTimes(1);
  });

  it("hands the navigation to the guard instead of running it", () => {
    const proceed = vi.fn();
    const guard = vi.fn();
    runGuarded(guard, proceed);
    expect(guard).toHaveBeenCalledTimes(1);
    expect(proceed).not.toHaveBeenCalled();

    // The guard owns it from here: nothing moves until it says so.
    guard.mock.calls[0][0]();
    expect(proceed).toHaveBeenCalledTimes(1);
  });

  it("cancels the navigation when the guard drops it", () => {
    const proceed = vi.fn();
    runGuarded(() => {}, proceed);
    expect(proceed).not.toHaveBeenCalled();
  });
});
