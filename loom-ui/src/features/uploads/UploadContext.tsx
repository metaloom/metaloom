import React, { createContext, useContext, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { useUnsavedChanges } from "../../hooks/useUnsavedChanges";
import {
  BatchOutcome,
  UploadSummary,
  getSummary,
  setUploadToken,
  subscribe,
  subscribeBatch,
} from "./uploadQueue";

/**
 * Bridges the module-level upload queue into React.
 *
 * The provider holds no upload state of its own — it mirrors the store so components re-render, and
 * owns the two side effects that need a React tree: toasts when a batch settles, and the unload
 * warning while transfers are in flight.
 */

const UploadContext = createContext<UploadSummary>(getSummary());

export function useUploads(): UploadSummary {
  return useContext(UploadContext);
}

export function UploadProvider({ children }: { children: React.ReactNode }) {
  const [summary, setSummary] = useState<UploadSummary>(getSummary);
  const { token } = useAuth();
  const { showToast } = useToast();
  const { t } = useTranslation();

  useEffect(() => subscribe(setSummary), []);

  // The queue is outside React and has no access to the auth context, so the token is pushed in.
  useEffect(() => {
    setUploadToken(token ?? null);
  }, [token]);

  useEffect(() => {
    return subscribeBatch((outcome: BatchOutcome) => {
      const { uploaded, duplicates, failed, cancelled } = outcome;
      if (failed > 0) {
        showToast(t("uploads.toast.failed", { count: failed }), "error");
      }
      if (uploaded > 0 || duplicates > 0) {
        const message = duplicates > 0
          ? t("uploads.toast.completedWithDuplicates", { count: uploaded, duplicates })
          : t("uploads.toast.completed", { count: uploaded });
        showToast(message, failed > 0 ? "warning" : "success");
      }
      // Nothing but cancellations is the user's own doing — an info toast, not a failure.
      if (uploaded === 0 && duplicates === 0 && failed === 0 && cancelled > 0) {
        showToast(t("uploads.toast.cancelled", { count: cancelled }), "info");
      }
    });
  }, [showToast, t]);

  // Reloading drops the File handles and the endpoint cannot resume, so an in-flight batch is lost
  // for good. Warn before that happens. No nav guard goes with it: the queue is module-level and
  // outlives every route change, so leaving the upload screen costs nothing.
  useUnsavedChanges(summary.isActive, t("uploads.unloadWarning"));

  return <UploadContext.Provider value={summary}>{children}</UploadContext.Provider>;
}
