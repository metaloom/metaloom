import React, { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import {
  Box, List, ListItemButton, ListItemIcon, ListItemText, Typography,
  Avatar, Menu, MenuItem, Divider, Tooltip, IconButton, Badge,
} from "@mui/material";
import {
  ChatBubbleOutline, PhotoLibraryOutlined, AccountTreeOutlined,
  TaskAltOutlined, CollectionsOutlined, BarChartOutlined,
  Circle, ChevronLeft, ChevronRight, LibraryBooksOutlined,
  FolderOpenOutlined, KeyboardArrowDown, FaceOutlined,
  PersonOutlined, LogoutOutlined,
  LocalOfferOutlined, DnsOutlined, GroupsOutlined,
  SecurityOutlined, VpnKeyOutlined, BlockOutlined,
  SpeedOutlined,
} from "@mui/icons-material";
import { tokens } from "../theme";
import { useProject } from "../context/ProjectContext";
import { useAuth } from "../context/AuthContext";
import { USERS } from "../mock/data";

const currentUser = USERS[0];

interface NavItem {
  label: string;
  path: string;
  icon: React.ReactNode;
  badge?: number;
  children?: NavItem[];
}

const USER_NAV_ITEMS: NavItem[] = [
  { label: "Chat", path: "/", icon: <ChatBubbleOutline fontSize="small" /> },
  { label: "Library", path: "/library", icon: <LibraryBooksOutlined fontSize="small" /> },
  { label: "Assets", path: "/assets", icon: <PhotoLibraryOutlined fontSize="small" /> },
  { label: "Collections", path: "/collections", icon: <CollectionsOutlined fontSize="small" /> },
  { label: "Tasks", path: "/tasks", icon: <TaskAltOutlined fontSize="small" />, badge: 3 },
  { label: "Faces", path: "/faces", icon: <FaceOutlined fontSize="small" /> },
  { label: "Tags", path: "/tags", icon: <LocalOfferOutlined fontSize="small" /> },
  { label: "Workflow", path: "/workflow", icon: <SpeedOutlined fontSize="small" /> },
];

const ADMIN_NAV_ITEMS: NavItem[] = [
  { label: "Pipelines", path: "/pipelines", icon: <AccountTreeOutlined fontSize="small" /> },
  { label: "Cortex", path: "/cortex", icon: <DnsOutlined fontSize="small" /> },
  { label: "Monitoring", path: "/monitoring", icon: <BarChartOutlined fontSize="small" /> },
  { label: "Users", path: "/admin/users", icon: <PersonOutlined fontSize="small" /> },
  { label: "Groups", path: "/admin/groups", icon: <GroupsOutlined fontSize="small" /> },
  { label: "Permissions", path: "/admin/permissions", icon: <SecurityOutlined fontSize="small" /> },
  { label: "API Keys", path: "/admin/api-keys", icon: <VpnKeyOutlined fontSize="small" /> },
  { label: "Blacklist", path: "/admin/blacklist", icon: <BlockOutlined fontSize="small" /> },
];

interface Props {
  collapsed: boolean;
  onCollapse: (v: boolean) => void;
}

export default function Sidebar({ collapsed, onCollapse }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const { projects, activeProject, setActiveProject } = useProject();
  const { logout } = useAuth();
  const [projectMenuAnchor, setProjectMenuAnchor] = useState<null | HTMLElement>(null);
  const [userMenuAnchor, setUserMenuAnchor] = useState<null | HTMLElement>(null);

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
      {/* Project Switcher */}
      <Box sx={{ px: collapsed ? 1 : 1.5, pt: 1.5, pb: 1, display: "flex", alignItems: "center", gap: 1 }}>
        <Tooltip title={collapsed ? activeProject?.name ?? "Project" : ""} placement="right">
          <Box
            onClick={(e) => setProjectMenuAnchor(e.currentTarget)}
            sx={{
              display: "flex",
              alignItems: "center",
              gap: 1,
              px: 1,
              py: 0.75,
              borderRadius: tokens.radius.md,
              cursor: "pointer",
              border: `1px solid ${tokens.border.subtle}`,
              bgcolor: tokens.bg.elevated,
              "&:hover": { borderColor: tokens.border.default, bgcolor: tokens.bg.overlay },
              transition: "all 140ms ease",
              overflow: "hidden",
              flex: 1,
              minWidth: 0,
            }}
          >
            <Box
              sx={{
                width: 24, height: 24, borderRadius: tokens.radius.sm,
                bgcolor: activeProject?.color ?? tokens.primary.main,
                flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "center",
              }}
            >
              <FolderOpenOutlined sx={{ fontSize: 13, color: "#fff" }} />
            </Box>
            {!collapsed && (
              <>
                <Typography variant="caption" fontWeight={600} color="text.primary" noWrap sx={{ flex: 1, fontSize: "0.8rem" }}>
                  {activeProject?.name ?? "Loading…"}
                </Typography>
                <KeyboardArrowDown sx={{ fontSize: 14, color: tokens.text.tertiary, flexShrink: 0 }} />
              </>
            )}
          </Box>
        </Tooltip>
        <Menu
          anchorEl={projectMenuAnchor}
          open={Boolean(projectMenuAnchor)}
          onClose={() => setProjectMenuAnchor(null)}
          sx={{ mt: 0.5 }}
        >
          {projects.map((p) => (
            <MenuItem
              key={p.id}
              selected={p.id === activeProject?.id}
              onClick={() => { setActiveProject(p); setProjectMenuAnchor(null); }}
              sx={{ gap: 1.5 }}
            >
              <Box sx={{ width: 10, height: 10, borderRadius: "50%", bgcolor: p.color, flexShrink: 0 }} />
              <Typography variant="body2" fontWeight={500}>{p.name}</Typography>
            </MenuItem>
          ))}
        </Menu>

        {/* User avatar — top right */}
        <Tooltip title={collapsed ? currentUser.name : ""} placement="right">
          <IconButton
            size="small"
            onClick={(e) => setUserMenuAnchor(e.currentTarget)}
            sx={{ flexShrink: 0, p: 0 }}
          >
            <Box sx={{ position: "relative" }}>
              <Avatar sx={{ width: 28, height: 28, fontSize: "0.75rem", bgcolor: tokens.primary.dark }}>
                {currentUser.name.split(" ").map(n => n[0]).join("")}
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
            <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{currentUser.name}</Typography>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>{currentUser.role}</Typography>
          </Box>
          <Divider />
          <MenuItem onClick={() => { setUserMenuAnchor(null); navigate("/profile"); }} sx={{ gap: 1.5, fontSize: "0.85rem" }}>
            <PersonOutlined sx={{ fontSize: 18 }} /> Profile
          </MenuItem>
          <MenuItem onClick={() => { setUserMenuAnchor(null); logout(); }} sx={{ gap: 1.5, fontSize: "0.85rem", color: tokens.accent.red }}>
            <LogoutOutlined sx={{ fontSize: 18 }} /> Logout
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
                Admin
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
        <Tooltip title={collapsed ? "Expand sidebar" : "Collapse sidebar"} placement="right">
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
