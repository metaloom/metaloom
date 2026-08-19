import React, { createContext, useContext, useState, useCallback } from "react";
import { Snackbar, Alert, AlertColor, Button } from "@mui/material";

/** An optional button rendered inside the toast. Used by the failure surface for "Report". */
export interface ToastAction {
  label: string;
  onClick: () => void;
  /** Test hook, so a spec can press the button without matching on translated text. */
  testId?: string;
}

export interface ToastOptions {
  action?: ToastAction;
  /**
   * How long the toast stays, in ms. Null pins it until dismissed.
   *
   * The default suits a confirmation. A failure that offers an action needs longer - four seconds
   * is not enough time to read what broke and decide to report it - so the failure surface passes
   * its own value rather than relying on this one.
   */
  duration?: number | null;
}

interface Toast {
  id: number;
  message: string;
  severity: AlertColor;
  action?: ToastAction;
  duration: number | null;
}

interface ToastContextValue {
  showToast: (message: string, severity?: AlertColor, options?: ToastOptions) => void;
}

const ToastContext = createContext<ToastContextValue>({ showToast: () => {} });

export function useToast() {
  return useContext(ToastContext);
}

/** Default lifetime of a toast, in ms. */
const DEFAULT_DURATION = 4000;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const showToast = useCallback(
    (message: string, severity: AlertColor = "success", options?: ToastOptions) => {
      // Date.now() collides when two toasts are raised in the same millisecond - which is exactly
      // what a page whose parallel loads all fail does - and React then renders two nodes with the
      // same key. The counter makes the id unique regardless.
      const id = nextToastId();
      setToasts(prev => [
        ...prev,
        {
          id,
          message,
          severity,
          action: options?.action,
          duration: options?.duration === undefined ? DEFAULT_DURATION : options.duration,
        },
      ]);
    },
    [],
  );

  const handleClose = useCallback((id: number) => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {toasts.map((toast, idx) => (
        <Snackbar
          key={toast.id}
          open
          autoHideDuration={toast.duration ?? null}
          // A click anywhere else must not dismiss a toast carrying an action: the user reaching
          // for the Report button would otherwise lose the thing they were reaching for.
          onClose={(_event, reason) => {
            if (reason === "clickaway" && toast.action) return;
            handleClose(toast.id);
          }}
          anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
          sx={{ bottom: `${24 + idx * 60}px !important` }}
        >
          <Alert
            onClose={() => handleClose(toast.id)}
            severity={toast.severity}
            variant="filled"
            sx={{ minWidth: 260, fontSize: "0.82rem" }}
            action={
              toast.action ? (
                <>
                  <Button
                    color="inherit"
                    size="small"
                    data-testid={toast.action.testId}
                    onClick={() => {
                      const { onClick } = toast.action!;
                      handleClose(toast.id);
                      onClick();
                    }}
                  >
                    {toast.action.label}
                  </Button>
                  <Button color="inherit" size="small" onClick={() => handleClose(toast.id)}>
                    ✕
                  </Button>
                </>
              ) : undefined
            }
          >
            {toast.message}
          </Alert>
        </Snackbar>
      ))}
    </ToastContext.Provider>
  );
}

let toastCounter = 0;
function nextToastId(): number {
  toastCounter += 1;
  return toastCounter;
}
