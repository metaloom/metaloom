import React, { createContext, useCallback, useContext, useEffect, useState } from "react";
import {
  NotificationResponse,
  clearNotifications as clearAll,
  deleteNotification,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "../api/notifications";
import { subscribeNotificationEvents } from "../api/pipelineEvents";
import { notificationSeverity } from "../features/notifications/notificationLink";
import { useAuth } from "./AuthContext";
import { useToast } from "./ToastContext";

interface NotificationContextValue {
  items: NotificationResponse[];
  unreadCount: number;
  loading: boolean;
  /** Refetch from the server. The popover calls this on open so the socket is an optimisation, not the only path. */
  refresh: () => Promise<void>;
  markRead: (uuid: string) => Promise<void>;
  markAllRead: () => Promise<void>;
  dismiss: (uuid: string) => Promise<void>;
  clear: () => Promise<void>;
}

const NotificationContext = createContext<NotificationContextValue>({
  items: [],
  unreadCount: 0,
  loading: false,
  refresh: async () => {},
  markRead: async () => {},
  markAllRead: async () => {},
  dismiss: async () => {},
  clear: async () => {},
});

export function useNotifications(): NotificationContextValue {
  return useContext(NotificationContext);
}

export function NotificationProvider({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  const { showToast } = useToast();
  const [items, setItems] = useState<NotificationResponse[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    if (!token) {
      setItems([]);
      setUnreadCount(0);
      return;
    }
    setLoading(true);
    try {
      const res = await listNotifications(token);
      setItems(res.data ?? []);
      setUnreadCount(res.unreadCount ?? 0);
    } catch {
      // A failing inbox must not blank the shell it is mounted in.
      setItems([]);
      setUnreadCount(0);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!token) return;
    // The same token the pipeline views pass — a different one would tear down and reopen
    // the shared socket on every mount.
    return subscribeNotificationEvents((event) => {
      setItems((prev) => {
        // The socket can redeliver after a reconnect; keying on uuid keeps the list honest.
        if (prev.some((n) => n.uuid === event.notification.uuid)) return prev;
        return [event.notification, ...prev];
      });
      // Server-supplied, because the badge counts the whole inbox rather than what is loaded.
      setUnreadCount(event.unreadCount);
      showToast(event.notification.title, notificationSeverity(event.notification));
    }, token);
  }, [token, showToast]);

  const markRead = useCallback(async (uuid: string) => {
    if (!token) return;
    // Optimistic: the badge should drop the instant the row is clicked.
    setItems((prev) => prev.map((n) => (n.uuid === uuid && !n.read ? { ...n, read: true } : n)));
    setUnreadCount((prev) => Math.max(0, prev - 1));
    try {
      await markNotificationRead(token, uuid);
    } catch {
      await refresh();
    }
  }, [token, refresh]);

  const markAllRead = useCallback(async () => {
    if (!token) return;
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
    setUnreadCount(0);
    try {
      await markAllNotificationsRead(token);
    } catch {
      await refresh();
    }
  }, [token, refresh]);

  const dismiss = useCallback(async (uuid: string) => {
    if (!token) return;
    const removed = items.find((n) => n.uuid === uuid);
    setItems((prev) => prev.filter((n) => n.uuid !== uuid));
    if (removed && !removed.read) {
      setUnreadCount((prev) => Math.max(0, prev - 1));
    }
    try {
      await deleteNotification(token, uuid);
    } catch {
      await refresh();
    }
  }, [token, items, refresh]);

  const clear = useCallback(async () => {
    if (!token) return;
    setItems([]);
    setUnreadCount(0);
    try {
      await clearAll(token);
    } catch {
      await refresh();
    }
  }, [token, refresh]);

  return (
    <NotificationContext.Provider
      value={{ items, unreadCount, loading, refresh, markRead, markAllRead, dismiss, clear }}
    >
      {children}
    </NotificationContext.Provider>
  );
}
