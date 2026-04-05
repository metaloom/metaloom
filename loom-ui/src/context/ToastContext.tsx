import React, { createContext, useContext, useState, useCallback } from "react";
import { Snackbar, Alert, AlertColor } from "@mui/material";

interface Toast {
  id: number;
  message: string;
  severity: AlertColor;
}

interface ToastContextValue {
  showToast: (message: string, severity?: AlertColor) => void;
}

const ToastContext = createContext<ToastContextValue>({ showToast: () => {} });

export function useToast() {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const showToast = useCallback((message: string, severity: AlertColor = "success") => {
    const id = Date.now();
    setToasts(prev => [...prev, { id, message, severity }]);
  }, []);

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
          autoHideDuration={4000}
          onClose={() => handleClose(toast.id)}
          anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
          sx={{ bottom: `${24 + idx * 60}px !important` }}
        >
          <Alert
            onClose={() => handleClose(toast.id)}
            severity={toast.severity}
            variant="filled"
            sx={{ minWidth: 260, fontSize: "0.82rem" }}
          >
            {toast.message}
          </Alert>
        </Snackbar>
      ))}
    </ToastContext.Provider>
  );
}
