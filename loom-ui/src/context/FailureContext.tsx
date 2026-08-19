import React, { createContext, useCallback, useContext, useMemo, useState } from "react";
import { useLocation } from "react-router-dom";
import { useToast } from "./ToastContext";
import { toFailure, type Failure } from "../failure/failure";
import FailureReportDialog from "../components/FailureReportDialog";

/**
 * The single way a screen tells the user that something failed.
 *
 * **Why a context and not just `useToast`.** A toast alone says "it broke" and then disappears,
 * which leaves the user with nothing to do and the operator with nothing to go on. Everything
 * that makes a failure actionable - the trace id, the status, what was being attempted - exists
 * only at the moment of the catch, and is gone by the time anybody thinks to ask about it. This
 * context captures it there and keeps it long enough for the user to press "Report".
 *
 * **The rule this exists to enforce**, recorded in `spec/loom/ui/LOOM_UI.md` §11.2: a catch that
 * only `console.error`s is a bug. A mutation must call {@link FailureContextValue.reportFailure}
 * and a load must additionally render a distinct failed state - a toast that has already faded
 * cannot explain a screen that still reads as empty.
 */

/** How long a failure toast stays up. Long enough to read what broke and decide to report it. */
const FAILURE_TOAST_DURATION = 10000;

export interface FailureContextValue {
  /**
   * Show a failure to the user and offer to report it.
   *
   * @param action what was being attempted, in the client's vocabulary - "createPerson"
   * @param error whatever the catch block received; an `ApiError` yields the richest report
   * @returns the normalised failure, for a caller that also wants to render an inline state
   */
  reportFailure: (action: string, error: unknown) => Failure;
  /** Open the report form directly, without a toast. Used by the error boundary. */
  openReport: (failure: Failure) => void;
}

const FailureContext = createContext<FailureContextValue>({
  reportFailure: (action, error) => toFailure(action, error),
  openReport: () => {},
});

/**
 * The failure surface.
 *
 * Falls back to a no-op-with-normalisation when used outside a provider, so that a component
 * rendered in isolation - a Playwright component fixture, say - does not crash on its own error
 * path. It still returns the `Failure`, so an inline error state keeps working.
 */
export function useFailure(): FailureContextValue {
  return useContext(FailureContext);
}

export function FailureProvider({ children }: { children: React.ReactNode }) {
  const { showToast } = useToast();
  const [reported, setReported] = useState<Failure | null>(null);
  // Read here rather than inside reportFailure's closure, so the route recorded is the one the
  // user was looking at when it broke - not whatever they navigated to while reading the toast.
  const location = useLocation();
  const route = location.pathname;

  const openReport = useCallback((failure: Failure) => {
    setReported(failure);
  }, []);

  const reportFailure = useCallback(
    (action: string, error: unknown) => {
      const failure = toFailure(action, error, route);

      // A 401 is not this surface's to explain. AuthProvider is already showing one
      // "your session expired" for the whole page, and a second message per failed widget is
      // exactly the pile-up the global 401 path exists to prevent.
      if (failure.status === 401) {
        return failure;
      }

      showToast(failure.message, "error", {
        duration: FAILURE_TOAST_DURATION,
        action: {
          label: "Report",
          testId: "toast-report-failure",
          onClick: () => openReport(failure),
        },
      });
      return failure;
    },
    [openReport, route, showToast],
  );

  const value = useMemo(() => ({ reportFailure, openReport }), [openReport, reportFailure]);

  return (
    <FailureContext.Provider value={value}>
      {children}
      <FailureReportDialog failure={reported} onClose={() => setReported(null)} />
    </FailureContext.Provider>
  );
}
