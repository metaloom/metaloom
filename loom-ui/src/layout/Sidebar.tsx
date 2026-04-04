import React, { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import {
  Box, List, ListItemButton, ListItemIcon, ListItemText, Typography,
  Avatar, Menu, MenuItem, Divider, Tooltip, IconButton, Badge, Collapse,
} from "@mui/material";
import {
  ChatBubbleOutline, PhotoLibraryOutlined, AccountTreeOutlined,
  TaskAltOutlined, CollectionsOutlined, BarChartOutlined,
  AdminPanelSettingsOutlined, ExpandMore, ExpandLess,
  Circle, ChevronLeft, ChevronRight, LibraryBooksOutlined,
  FolderOpenOutlined, KeyboardArrowDown,
} from "@mui/icons-material";
import { tokens } from "../theme";
import { useProject } from "../context/ProjectContext";
import { USERS } from "../mock/data";

const currentUser = USERS[0];

interface NavItem {
  label: string;
  path: string;
  icon: React.ReactNode;
  badge?: number;
  children?: NavItem[];
}

const NAV_ITEMS: NavItem[] = [
  { label: "Chat", path: "/", icon: <ChatBubbleOutline fontSize="small" /> },
  { label: "Library", path: "/library", icon: <LibraryBooksOutlined fontSize="small" /> },
  { label: "Assets", path: "/assets", icon: <PhotoLibraryOutlined fontSize="small" /> },
  { label: "Collections", path: "/collections", icon: <CollectionsOutlined fontSize="small" /> },
  { label: "Tasks", path: "/tasks", icon: <TaskAltOutlined fontSize="small" />, badge: 3 },
  { label: "Pipelines", path: "/pipelines", icon: <AccountTreeOutlined fontSize="small" /> },
  { label: "Monitoring", path: "/monitoring", icon: <BarChartOutlined fontSize="small" /> },
  {
    label: "Admin", path: "/admin", icon: <AdminPanelSettingsOutlined fontSize="small" />,
    children: [
      { label: "Users", path: "/admin/users", icon: <Box component="span" sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.text.tertiary, display: "inline-block", ml: "2px", mr: "4px" }} /> },
      { label: "Groups", path: "/admin/groups", icon: <Box component="span" sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.text.tertiary, display: "inline-block", ml: "2px", mr: "4px" }} /> },
      { label: "RBAC", path: "/admin/rbac", icon: <Box component="span" sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.text.tertiary, display: "inline-block", ml: "2px", mr: "4px" }} /> },
      { label: "API Keys", path: "/admin/api-keys", icon: <Box component="span" sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.text.tertiary, display: "inline-block", ml: "2px", mr: "4px" }} /> },
      { label: "Blacklist", path: "/admin/blacklist", icon: <Box component="span" sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.text.tertiary, display: "inline-block", ml: "2px", mr: "4px" }} /> },
    ],
  },
];

interface Props {
  collapsed: boolean;
  onCollapse: (v: boolean) => void;
}

export default function Sidebar({ collapsed, onCollapse }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const { projects, activeProject, setActiveProject } = useProject();
  const [projectMenuAnchor, setProjectMenuAnchor] = useState<null | HTMLElement>(null);
  const [expandedAdmin, setExpandedAdmin] = useState(location.pathname.startsWith("/admin"));

  const isActive = (path: string) =>
    path === "/" ? location.pathname === "/" : location.pathname.startsWith(path);

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
      <Box sx={{ px: collapsed ? 1 : 1.5, pt: 1.5, pb: 1 }}>
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
      </Box>

      <Divider />

      {/* Navigation */}
      <Box sx={{ flex: 1, overflow: "auto", px: collapsed ? 0.5 : 1, py: 1 }}>
        <List dense disablePadding sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
          {NAV_ITEMS.map((item) => {
            if (item.children) {
              return (
                <React.Fragment key={item.path}>
                  <Tooltip title={collapsed ? item.label : ""} placement="right">
                    <ListItemButton
                      selected={isActive(item.path)}
                      onClick={() => {
                        if (collapsed) { navigate(item.path); }
                        else setExpandedAdmin(!expandedAdmin);
                      }}
                      sx={{ borderRadius: tokens.radius.md, px: collapsed ? 1 : 1.5, minHeight: 36 }}
                    >
                      <ListItemIcon sx={{ minWidth: collapsed ? 0 : 30, mr: collapsed ? 0 : 0 }}>
                        {item.icon}
                      </ListItemIcon>
                      {!collapsed && (
                        <>
                          <ListItemText
                            primary={item.label}
                            primaryTypographyProps={{ fontSize: "0.8375rem", fontWeight: 500 }}
                          />
                          {expandedAdmin ? <ExpandLess sx={{ fontSize: 16 }} /> : <ExpandMore sx={{ fontSize: 16 }} />}
                        </>
                      )}
                    </ListItemButton>
                  </Tooltip>
                  {!collapsed && (
                    <Collapse in={expandedAdmin}>
                      <List dense disablePadding sx={{ pl: 1.5 }}>
                        {item.children.map((child) => (
                          <ListItemButton
                            key={child.path}
                            selected={location.pathname === child.path}
                            onClick={() => navigate(child.path)}
                            sx={{ borderRadius: tokens.radius.sm, px: 1.5, minHeight: 32, mb: 0.25 }}
                          >
                            {child.icon}
                            <ListItemText
                              primary={child.label}
                              primaryTypographyProps={{ fontSize: "0.8rem", color: tokens.text.secondary }}
                            />
                          </ListItemButton>
                        ))}
                      </List>
                    </Collapse>
                  )}
                </React.Fragment>
              );
            }

            return (
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
            );
          })}
        </List>
      </Box>

      <Divider />

      {/* User / Status Footer */}
      <Box sx={{ px: collapsed ? 0.75 : 1.5, py: 1.25, display: "flex", alignItems: "center", gap: 1.5 }}>
        <Box sx={{ position: "relative", flexShrink: 0 }}>
          <Avatar
            sx={{ width: 28, height: 28, fontSize: "0.75rem", bgcolor: tokens.primary.dark }}
          >
            {currentUser.name.split(" ").map(n => n[0]).join("")}
          </Avatar>
          <Circle sx={{ position: "absolute", bottom: -1, right: -1, fontSize: 10, color: tokens.accent.green }} />
        </Box>
        {!collapsed && (
          <Box sx={{ overflow: "hidden", flex: 1 }}>
            <Typography variant="caption" fontWeight={600} display="block" noWrap color="text.primary">
              {currentUser.name}
            </Typography>
            <Typography variant="caption" display="block" noWrap sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
              {currentUser.role}
            </Typography>
          </Box>
        )}
      </Box>

      {/* Collapse Toggle */}
      <Tooltip title={collapsed ? "Expand sidebar" : "Collapse sidebar"} placement="right">
        <IconButton
          onClick={() => onCollapse(!collapsed)}
          size="small"
          sx={{
            position: "absolute",
            bottom: 16,
            right: collapsed ? "50%" : 8,
            transform: collapsed ? "translateX(50%)" : "none",
            bgcolor: tokens.bg.elevated,
            border: `1px solid ${tokens.border.subtle}`,
            width: 22,
            height: 22,
            "&:hover": { bgcolor: tokens.bg.overlay },
            display: { xs: "none", md: "flex" },
          }}
        >
          {collapsed ? <ChevronRight sx={{ fontSize: 14 }} /> : <ChevronLeft sx={{ fontSize: 14 }} />}
        </IconButton>
      </Tooltip>
    </Box>
  );
}
