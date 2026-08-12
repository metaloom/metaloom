import { useEffect } from "react";
import { useLayout, type NavGuard } from "../context/LayoutContext";

/**
 * Guards for work that only exists in the browser: an in-flight upload batch, an edited pipeline
 * canvas. Two exits have to be covered and they are covered differently.
 *
 * * Leaving the *document* — reload, close, an external link — can only be met with the browser's
 *   own confirm, via `beforeunload`.
 * * Leaving the *screen* — a sidebar click, a notification deep link — never unloads the document,
 *   so no browser event fires. Those are intercepted in-app through the `LayoutContext` nav guard,
 *   which lets the screen show its own dialog and resume the navigation afterwards.
 *
 * The wiring of both lives in plain functions below so it is testable under the node-environment
 * vitest tier (§8.1 of `spec/loom/ui/LOOM_UI.md`) — the hooks are the `useEffect` around them.
 */

/** The slice of `window` the unload warning needs; narrowed so a test can pass a stub. */
export interface UnloadTarget {
  addEventListener(type: "beforeunload", listener: (e: BeforeUnloadEvent) => void): void;
  removeEventListener(type: "beforeunload", listener: (e: BeforeUnloadEvent) => void): void;
}

/**
 * Register the unload warning on `target`.
 *
 * @returns the teardown that removes the very listener that was added — callers must invoke it, or
 *   a stale listener keeps warning about work that has since been saved.
 */
export function bindUnloadWarning(target: UnloadTarget, message: string): () => void {
  const onBeforeUnload = (e: BeforeUnloadEvent) => {
    // Browsers show their own wording and ignore `message`, but the dialog appears at all only if
    // the event is cancelled — both of these are needed to cover every engine.
    e.preventDefault();
    e.returnValue = message;
  };
  target.addEventListener("beforeunload", onBeforeUnload);
  return () => target.removeEventListener("beforeunload", onBeforeUnload);
}

/** Hand `proceed` to the registered guard, or run it straight away when no screen is guarding. */
export function runGuarded(guard: NavGuard | null, proceed: () => void): void {
  if (guard) guard(proceed);
  else proceed();
}

/**
 * Warn before the document unloads while `isDirty` — the reload/close half of the guard.
 *
 * @param message shown by browsers that still honour custom text; pass the same wording as the
 *   in-app confirm so the two do not disagree
 */
export function useUnsavedChanges(isDirty: boolean, message: string): void {
  useEffect(() => {
    if (!isDirty) return;
    return bindUnloadWarning(window, message);
  }, [isDirty, message]);
}

/**
 * Intercept in-app navigation while `active` — the route-change half of the guard.
 *
 * `onBlocked` receives the deferred navigation and owns it: nothing moves until it calls `proceed`,
 * and dropping it cancels the navigation. Keep it referentially stable (`useCallback`), otherwise
 * the guard is re-registered on every render.
 */
export function useNavigationGuard(active: boolean, onBlocked: NavGuard): void {
  const { setNavGuard } = useLayout();
  useEffect(() => {
    if (!active) return;
    setNavGuard(onBlocked);
    // Unmounting is itself an exit: the screen is gone, so its guard must go with it or every
    // later navigation would be handed to a dialog nobody is rendering.
    return () => setNavGuard(null);
  }, [active, onBlocked, setNavGuard]);
}
