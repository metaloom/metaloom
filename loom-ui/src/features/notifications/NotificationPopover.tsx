import React from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Badge, Box, Button, CircularProgress, Divider, IconButton, List, ListItemButton,
  Popover, Tooltip, Typography,
} from "@mui/material";
import {
  NotificationsNoneOutlined, CloseOutlined, AssignmentIndOutlined,
  ChatBubbleOutlineOutlined, ReplyOutlined, ErrorOutlineOutlined, SwapHorizOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import EmptyState from "../../components/EmptyState";
import { useNotifications } from "../../context/NotificationContext";
import { useLayout } from "../../context/LayoutContext";
import { NotificationResponse, NotificationType } from "../../api/notifications";
import { notificationLink } from "./notificationLink";

const typeIcon: Record<NotificationType, React.ElementType> = {
  TASK_ASSIGNED: AssignmentIndOutlined,
  TASK_UNASSIGNED: AssignmentIndOutlined,
  TASK_STATUS_CHANGED: SwapHorizOutlined,
  TASK_COMMENT: ChatBubbleOutlineOutlined,
  COMMENT_REPLY: ReplyOutlined,
  PIPELINE_RUN_FAILED: ErrorOutlineOutlined,
};

function relativeTime(iso?: string): string {
  if (!iso) return "";
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

function NotificationRow({
  notification, onOpen, onDismiss,
}: {
  notification: NotificationResponse;
  onOpen: (n: NotificationResponse) => void;
  onDismiss: (uuid: string) => void;
}) {
  const { t } = useTranslation();
  const Icon = typeIcon[notification.type] ?? NotificationsNoneOutlined;
  const clickable = notificationLink(notification) !== null;

  return (
    <ListItemButton
      data-testid="notification-row"
      data-read={notification.read ? "true" : "false"}
      onClick={() => onOpen(notification)}
      // A row with no subject still marks read on click; it just does not navigate.
      sx={{
        alignItems: "flex-start",
        gap: 1,
        py: 1,
        px: 1.5,
        cursor: clickable ? "pointer" : "default",
        bgcolor: notification.read ? "transparent" : tokens.primary.subtle,
        "&:hover .notification-dismiss": { opacity: 1 },
      }}
    >
      <Icon sx={{ fontSize: 16, mt: 0.25, color: tokens.text.tertiary, flexShrink: 0 }} />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography
          variant="body2"
          sx={{ fontSize: "0.8rem", fontWeight: notification.read ? 400 : 600, color: tokens.text.primary }}
        >
          {notification.title}
        </Typography>
        {notification.body && (
          <Typography variant="caption" noWrap sx={{ display: "block", color: tokens.text.tertiary, fontSize: "0.7rem" }}>
            {notification.body}
          </Typography>
        )}
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.65rem" }}>
          {relativeTime(notification.status?.created)}
        </Typography>
      </Box>
      {!notification.read && (
        <Box data-testid="notification-unread-dot" sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: tokens.primary.main, mt: 0.75, flexShrink: 0 }} />
      )}
      <Tooltip title={t("notifications.dismiss")}>
        <IconButton
          size="small"
          className="notification-dismiss"
          data-testid="notification-dismiss"
          aria-label={t("notifications.dismiss")}
          onClick={(e) => { e.stopPropagation(); onDismiss(notification.uuid); }}
          sx={{ p: 0.25, opacity: 0, transition: "opacity 120ms ease" }}
        >
          <CloseOutlined sx={{ fontSize: 14 }} />
        </IconButton>
      </Tooltip>
    </ListItemButton>
  );
}

/**
 * The bell and its popover.
 *
 * A Popover rather than a Drawer: the sidebar is 220px wide and a Drawer fights its
 * collapse animation.
 */
export default function NotificationPopover() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { items, unreadCount, loading, refresh, markRead, markAllRead, dismiss, clear } = useNotifications();
  const { requestNavigation } = useLayout();
  const [anchor, setAnchor] = React.useState<HTMLElement | null>(null);

  const open = (e: React.MouseEvent<HTMLElement>) => {
    setAnchor(e.currentTarget);
    // Refetch on open. The socket only reaches an authenticated connection, and lenient
    // auth is the server default — so the stream is an optimisation, never the only path.
    void refresh();
  };

  const handleOpen = (notification: NotificationResponse) => {
    if (!notification.read) void markRead(notification.uuid);
    const link = notificationLink(notification);
    if (link) {
      setAnchor(null);
      // A deep link leaves the current screen like any sidebar entry does, so it asks the
      // guarding screen first rather than discarding its unsaved work.
      requestNavigation(() => navigate(link));
    }
  };

  return (
    <>
      <Tooltip title={t("notifications.title")}>
        <IconButton size="small" onClick={open} data-testid="notification-bell" aria-label={t("notifications.title")} sx={{ flexShrink: 0 }}>
          <Badge
            badgeContent={unreadCount}
            color="error"
            data-testid="notification-badge"
            sx={{ "& .MuiBadge-badge": { fontSize: "0.6rem", height: 15, minWidth: 15 } }}
          >
            <NotificationsNoneOutlined sx={{ fontSize: 18, color: tokens.text.secondary }} />
          </Badge>
        </IconButton>
      </Tooltip>

      <Popover
        open={Boolean(anchor)}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        PaperProps={{ sx: { width: 360, maxHeight: 480, bgcolor: tokens.bg.surface, backgroundImage: "none" } }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, px: 1.5, py: 1 }}>
          <Typography variant="subtitle2" sx={{ flex: 1, fontSize: "0.85rem", fontWeight: 700 }}>
            {t("notifications.title")}
          </Typography>
          <Button size="small" data-testid="notification-mark-all" disabled={unreadCount === 0} onClick={() => void markAllRead()}>
            {t("notifications.markAllRead")}
          </Button>
          <Button size="small" data-testid="notification-clear-all" disabled={items.length === 0} onClick={() => void clear()}>
            {t("notifications.clearAll")}
          </Button>
        </Box>
        <Divider />

        {loading && items.length === 0 && (
          <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
            <CircularProgress size={20} />
          </Box>
        )}

        {!loading && items.length === 0 && (
          <Box sx={{ py: 1 }} data-testid="notifications-empty">
            <EmptyState
              compact
              icon={NotificationsNoneOutlined}
              title={t("notifications.emptyState.title")}
              description={t("notifications.emptyState.description")}
              testId="notifications-empty-state"
            />
          </Box>
        )}

        {items.length > 0 && (
          <List dense disablePadding>
            {items.map((n) => (
              <NotificationRow key={n.uuid} notification={n} onOpen={handleOpen} onDismiss={(uuid) => void dismiss(uuid)} />
            ))}
          </List>
        )}
      </Popover>
    </>
  );
}
