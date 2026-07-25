import React, { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import {
  Box, List, ListItemButton, ListItemIcon, ListItemText, Typography,
  Avatar, Menu, MenuItem, Divider, Tooltip, IconButton, Badge,
} from "@mui/material";
import {
  PsychologyOutlined,
  ChatBubbleOutline, PhotoLibraryOutlined, AccountTreeOutlined,
  TaskAltOutlined, CollectionsOutlined, BarChartOutlined,
  Circle, ChevronLeft, ChevronRight, LibraryBooksOutlined,
  FolderOpenOutlined, FaceOutlined,
  PersonOutlined, LogoutOutlined,
  LocalOfferOutlined, DnsOutlined, GroupsOutlined,
  SecurityOutlined, VpnKeyOutlined, BlockOutlined,
  SpeedOutlined, VisibilityOutlined, StorageOutlined, AutoFixHighOutlined,
  HistoryOutlined,
} from "@mui/icons-material";
import { tokens } from "../theme";
import { useAuth } from "../context/AuthContext";
import { useTranslation } from "react-i18next";

interface NavItem {
  label: string;
  path: string;
  icon: React.ReactNode;
  badge?: number;
  children?: NavItem[];
}

function userNavItems(t: (k: string) => string): NavItem[] {
  return [
    { label: t("sidebar.nav.chat"), path: "/", icon: <ChatBubbleOutline fontSize="small" /> },
    { label: t("sidebar.nav.library"), path: "/library", icon: <LibraryBooksOutlined fontSize="small" /> },
    { label: t("sidebar.nav.assets"), path: "/assets", icon: <PhotoLibraryOutlined fontSize="small" /> },
    { label: t("sidebar.nav.collections"), path: "/collections", icon: <CollectionsOutlined fontSize="small" /> },
    { label: t("sidebar.nav.tasks"), path: "/tasks", icon: <TaskAltOutlined fontSize="small" />, badge: 3 },
    { label: t("sidebar.nav.detection"), path: "/detection", icon: <VisibilityOutlined fontSize="small" /> },
    { label: t("sidebar.nav.tags"), path: "/tags", icon: <LocalOfferOutlined fontSize="small" /> },
    { label: t("sidebar.nav.workflow"), path: "/workflow", icon: <SpeedOutlined fontSize="small" /> },
    { label: t("sidebar.nav.skills"), path: "/skills", icon: <AutoFixHighOutlined fontSize="small" /> },
    { label: t("sidebar.nav.memory"), path: "/memory", icon: <PsychologyOutlined fontSize="small" /> },
    { label: t("sidebar.nav.chatSessions"), path: "/chat/sessions", icon: <HistoryOutlined fontSize="small" /> },
  ];
}

function adminNavItems(t: (k: string) => string): NavItem[] {
  return [
    { label: t("sidebar.admin.assetPools"), path: "/asset-pools", icon: <StorageOutlined fontSize="small" /> },
    { label: t("sidebar.admin.pipelines"), path: "/pipelines", icon: <AccountTreeOutlined fontSize="small" /> },
    { label: t("sidebar.admin.cortex"), path: "/cortex", icon: <DnsOutlined fontSize="small" /> },
    { label: t("sidebar.admin.monitoring"), path: "/monitoring", icon: <BarChartOutlined fontSize="small" /> },
    { label: t("sidebar.admin.spaces"), path: "/admin/spaces", icon: <FolderOpenOutlined fontSize="small" /> },
    { label: t("sidebar.admin.users"), path: "/admin/users", icon: <PersonOutlined fontSize="small" /> },
    { label: t("sidebar.admin.groups"), path: "/admin/groups", icon: <GroupsOutlined fontSize="small" /> },
    { label: t("sidebar.admin.permissions"), path: "/admin/permissions", icon: <SecurityOutlined fontSize="small" /> },
    { label: t("sidebar.admin.apiKeys"), path: "/admin/api-keys", icon: <VpnKeyOutlined fontSize="small" /> },
    { label: t("sidebar.admin.blacklist"), path: "/admin/blacklist", icon: <BlockOutlined fontSize="small" /> },
  ];
}

interface Props {
  collapsed: boolean;
  onCollapse: (v: boolean) => void;
}

export default function Sidebar({ collapsed, onCollapse }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const { username, logout } = useAuth();
  const { t } = useTranslation();
  const [userMenuAnchor, setUserMenuAnchor] = useState<null | HTMLElement>(null);

  const USER_NAV_ITEMS = userNavItems(t);
  const ADMIN_NAV_ITEMS = adminNavItems(t);

  const isActive = (path: string) =>
    path === "/" ? location.pathname === "/" : location.pathname.startsWith(path);

  const renderNavItems = (items: NavItem[]) =>
    items.map((item) => (
      <Tooltip key={item.path} title={collapsed ? item.label : ""} placement="right">
        <ListItemButton
          selected={isActive(item.path)}
          onClick={() => navigate(item.path)}
          sx={{ borderRadius: tokens.radius.md, px: collapsed ? 1 : 1.5, minHeight: 36 }}
        >
          <ListItemIcon sx={{ minWidth: collapsed ? 0 : 30 }}>
            {item.badge ? (
              <Badge badgeContent={item.badge} color="primary" sx={{ "& .MuiBadge-badge": { fontSize: "0.6rem", height: 14, minWidth: 14 } }}>
                {item.icon}
              </Badge>
            ) : item.icon}
          </ListItemIcon>
          {!collapsed && (
            <ListItemText
              primary={item.label}
              primaryTypographyProps={{ fontSize: "0.8375rem", fontWeight: 500 }}
            />
          )}
        </ListItemButton>
      </Tooltip>
    ));

  return (
    <Box
      sx={{
        width: collapsed ? 56 : 220,
        minWidth: collapsed ? 56 : 220,
        height: "100vh",
        display: "flex",
        flexDirection: "column",
        borderRight: `1px solid ${tokens.border.subtle}`,
        backgroundColor: tokens.bg.surface,
        transition: "width 220ms ease, min-width 220ms ease",
        overflow: "hidden",
        position: "relative",
        flexShrink: 0,
      }}
    >
      {/* Header */}
      <Box sx={{ px: collapsed ? 1 : 1.5, pt: 1.5, pb: 1, display: "flex", alignItems: "center", gap: 1 }}>
        {!collapsed && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flex: 1, minWidth: 0 }}>
            <Box
              sx={{
                width: 24, height: 24, borderRadius: tokens.radius.sm,
                bgcolor: tokens.primary.main,
                flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "center",
              }}
            >
              <FolderOpenOutlined sx={{ fontSize: 13, color: "#fff" }} />
            </Box>
            <Typography variant="caption" fontWeight={600} color="text.primary" noWrap sx={{ flex: 1, fontSize: "0.8rem" }}>
              {t("sidebar.brand")}
            </Typography>
          </Box>
        )}

        {/* User avatar — top right */}
        <Tooltip title={collapsed ? (username ?? "") : ""} placement="right">
          <IconButton
            size="small"
            onClick={(e) => setUserMenuAnchor(e.currentTarget)}
            sx={{ flexShrink: 0, p: 0 }}
          >
            <Box sx={{ position: "relative" }}>
              <Avatar sx={{ width: 28, height: 28, fontSize: "0.75rem", bgcolor: tokens.primary.dark }}>
                {(username ?? "?").charAt(0).toUpperCase()}
              </Avatar>
              <Circle sx={{ position: "absolute", bottom: -1, right: -1, fontSize: 10, color: tokens.accent.green }} />
            </Box>
          </IconButton>
        </Tooltip>
        <Menu
          anchorEl={userMenuAnchor}
          open={Boolean(userMenuAnchor)}
          onClose={() => setUserMenuAnchor(null)}
          anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
          transformOrigin={{ vertical: "top", horizontal: "right" }}
        >
          <Box sx={{ px: 2, py: 1, minWidth: 160 }}>
            <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{username ?? t("sidebar.fallback.unknown")}</Typography>
          </Box>
          <Divider />
          <MenuItem onClick={() => { setUserMenuAnchor(null); navigate("/profile"); }} sx={{ gap: 1.5, fontSize: "0.85rem" }}>
            <PersonOutlined sx={{ fontSize: 18 }} /> {t("sidebar.menu.profile")}
          </MenuItem>
          <MenuItem onClick={() => { setUserMenuAnchor(null); logout(); }} sx={{ gap: 1.5, fontSize: "0.85rem", color: tokens.accent.red }}>
            <LogoutOutlined sx={{ fontSize: 18 }} /> {t("sidebar.menu.logout")}
          </MenuItem>
        </Menu>
      </Box>

      <Divider />

      {/* Navigation */}
      <Box sx={{ flex: 1, overflow: "auto", px: collapsed ? 0.5 : 1, py: 1 }}>
        <List dense disablePadding sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
          {renderNavItems(USER_NAV_ITEMS)}
        </List>

        {/* Admin divider */}
        <Box sx={{ px: collapsed ? 0.5 : 1, py: 1.25 }}>
          {collapsed ? (
            <Divider sx={{ borderColor: tokens.border.subtle }} />
          ) : (
            <Divider sx={{ borderColor: tokens.border.subtle, "&::before": { width: 0 }, "&::after": { flex: 1 } }} textAlign="left">
              <Typography variant="caption" sx={{ fontSize: "0.62rem", color: tokens.text.tertiary, letterSpacing: "0.08em", textTransform: "uppercase", px: 0.5 }}>
                {t("sidebar.divider.admin")}
              </Typography>
            </Divider>
          )}
        </Box>

        <List dense disablePadding sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
          {renderNavItems(ADMIN_NAV_ITEMS)}
        </List>
      </Box>

      <Divider />

      {/* Collapse Toggle */}
      <Box sx={{ px: collapsed ? 0.75 : 1.5, py: 1, display: "flex", justifyContent: collapsed ? "center" : "flex-end" }}>
        <Tooltip title={collapsed ? t("sidebar.tooltip.expand") : t("sidebar.tooltip.collapse")} placement="right">
          <IconButton
            onClick={() => onCollapse(!collapsed)}
            size="small"
            sx={{
              bgcolor: tokens.bg.elevated,
              border: `1px solid ${tokens.border.subtle}`,
              width: 22,
              height: 22,
              "&:hover": { bgcolor: tokens.bg.overlay },
            }}
          >
            {collapsed ? <ChevronRight sx={{ fontSize: 14 }} /> : <ChevronLeft sx={{ fontSize: 14 }} />}
          </IconButton>
        </Tooltip>
      </Box>
    </Box>
  );
}
