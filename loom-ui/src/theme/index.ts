import { createTheme, alpha } from "@mui/material/styles";

// ── Design Tokens ──────────────────────────────────────────────────────────
export const tokens = {
  bg: {
    base: "#0d0f11",
    surface: "#141719",
    panel: "#1a1d22",
    elevated: "#20252c",
    overlay: "#353b43",
    hover: "rgba(87,203,204,0.06)",
    active: "rgba(87,203,204,0.10)",
  },
  border: {
    subtle: "rgba(255,255,255,0.07)",
    default: "rgba(255,255,255,0.10)",
    strong: "rgba(255,255,255,0.16)",
  },
  text: {
    primary: "#e8e9eb",
    secondary: "#737f8a",
    tertiary: "#4f5a63",
    disabled: "#353b43",
    inverse: "#0d0f11",
  },
  primary: {
    main: "#57cbcc",
    light: "#88dcdd",
    dark: "#3ba8a9",
    glow: "rgba(87,203,204,0.25)",
    subtle: "rgba(87,203,204,0.10)",
  },
  accent: {
    blue: "#2ea8ff",
    green: "#34d58a",
    amber: "#f5a623",
    red: "#f0546e",
    teal: "#00c9b1",
  },
  radius: {
    sm: "6px",
    md: "10px",
    lg: "16px",
    xl: "24px",
    full: "9999px",
  },
} as const;

// ── MUI Theme ──────────────────────────────────────────────────────────────
const loomTheme = createTheme({
  palette: {
    mode: "dark",
    background: {
      default: tokens.bg.base,
      paper: tokens.bg.panel,
    },
    primary: {
      main: tokens.primary.main,
      light: tokens.primary.light,
      dark: tokens.primary.dark,
    },
    secondary: {
      main: tokens.accent.blue,
    },
    success: {
      main: tokens.accent.green,
    },
    warning: {
      main: tokens.accent.amber,
    },
    error: {
      main: tokens.accent.red,
    },
    text: {
      primary: tokens.text.primary,
      secondary: tokens.text.secondary,
      disabled: tokens.text.disabled,
    },
    divider: tokens.border.subtle,
  },
  typography: {
    fontFamily: "'Inter', 'Roboto', sans-serif",
    h1: { fontWeight: 700, letterSpacing: "-0.02em" },
    h2: { fontWeight: 700, letterSpacing: "-0.015em" },
    h3: { fontWeight: 600, letterSpacing: "-0.01em" },
    h4: { fontWeight: 600, letterSpacing: "-0.01em" },
    h5: { fontWeight: 600 },
    h6: { fontWeight: 600 },
    subtitle1: { fontWeight: 500 },
    subtitle2: { fontWeight: 500, color: tokens.text.secondary },
    body1: { lineHeight: 1.6 },
    body2: { lineHeight: 1.5, color: tokens.text.secondary },
    caption: { color: tokens.text.tertiary },
    button: { fontWeight: 600, textTransform: "none", letterSpacing: "0.01em" },
  },
  shape: {
    borderRadius: 10,
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        "*, *::before, *::after": { boxSizing: "border-box" },
        "::-webkit-scrollbar": { width: "6px", height: "6px" },
        "::-webkit-scrollbar-track": { background: "transparent" },
        "::-webkit-scrollbar-thumb": {
          background: tokens.border.strong,
          borderRadius: "3px",
        },
        "::-webkit-scrollbar-thumb:hover": { background: tokens.text.tertiary },
        "::selection": { background: tokens.primary.subtle, color: tokens.text.primary },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
          backgroundColor: tokens.bg.panel,
          border: `1px solid ${tokens.border.subtle}`,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
          backgroundColor: tokens.bg.elevated,
          border: `1px solid ${tokens.border.subtle}`,
          borderRadius: tokens.radius.lg,
          "&:hover": {
            borderColor: tokens.border.default,
            backgroundColor: tokens.bg.overlay,
          },
          transition: "border-color 150ms ease, background-color 150ms ease",
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: tokens.radius.md,
          fontWeight: 600,
        },
        contained: {
          background: `linear-gradient(135deg, ${tokens.primary.main} 0%, ${tokens.primary.dark} 100%)`,
          boxShadow: "none",
          "&:hover": {
            boxShadow: `0 0 18px ${tokens.primary.glow}`,
          },
        },
        outlined: {
          borderColor: tokens.border.default,
          "&:hover": {
            borderColor: tokens.primary.main,
            backgroundColor: tokens.primary.subtle,
          },
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: tokens.radius.full,
          fontWeight: 500,
        },
      },
    },
    MuiTextField: {
      styleOverrides: {
        root: {
          "& .MuiOutlinedInput-root": {
            borderRadius: tokens.radius.md,
            backgroundColor: tokens.bg.surface,
            "& fieldset": { borderColor: tokens.border.default },
            "&:hover fieldset": { borderColor: tokens.border.strong },
            "&.Mui-focused fieldset": { borderColor: tokens.primary.main },
          },
        },
      },
    },
    MuiInputBase: {
      styleOverrides: {
        root: {
          borderRadius: `${tokens.radius.md} !important`,
        },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: {
          borderRadius: tokens.radius.md,
          "&:hover": { backgroundColor: tokens.bg.hover },
          "&.Mui-selected": {
            backgroundColor: tokens.primary.subtle,
            borderLeft: `2px solid ${tokens.primary.main}`,
            "&:hover": { backgroundColor: alpha(tokens.primary.main, 0.15) },
            "& .MuiListItemText-primary": { color: tokens.primary.light },
            "& .MuiListItemIcon-root": { color: tokens.primary.main },
          },
        },
      },
    },
    MuiListItemIcon: {
      styleOverrides: { root: { minWidth: 36, color: tokens.text.secondary } },
    },
    MuiDivider: {
      styleOverrides: { root: { borderColor: tokens.border.subtle } },
    },
    MuiTooltip: {
      styleOverrides: {
        tooltip: {
          backgroundColor: tokens.bg.overlay,
          border: `1px solid ${tokens.border.default}`,
          borderRadius: tokens.radius.sm,
          fontSize: "0.75rem",
        },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: { minHeight: 38 },
        indicator: {
          backgroundColor: tokens.primary.main,
          height: 2,
          borderRadius: "1px",
        },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 38,
          fontWeight: 500,
          fontSize: "0.8125rem",
          textTransform: "none",
          color: tokens.text.secondary,
          "&.Mui-selected": { color: tokens.text.primary, fontWeight: 600 },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        head: {
          fontWeight: 600,
          fontSize: "0.75rem",
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          color: tokens.text.secondary,
          backgroundColor: tokens.bg.surface,
          borderBottom: `1px solid ${tokens.border.subtle}`,
        },
        body: {
          borderBottom: `1px solid ${tokens.border.subtle}`,
          fontSize: "0.85rem",
        },
      },
    },
    MuiBadge: {
      styleOverrides: {
        badge: { fontWeight: 700, fontSize: "0.65rem" },
      },
    },
    MuiMenu: {
      styleOverrides: {
        paper: {
          backgroundColor: tokens.bg.overlay,
          border: `1px solid ${tokens.border.default}`,
          borderRadius: tokens.radius.md,
          boxShadow: "0 8px 32px rgba(0,0,0,0.5)",
        },
      },
    },
    MuiMenuItem: {
      styleOverrides: {
        root: {
          borderRadius: tokens.radius.sm,
          margin: "2px 6px",
          fontSize: "0.875rem",
          "&:hover": { backgroundColor: tokens.bg.hover },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          borderRadius: tokens.radius.md,
          "&:hover": { backgroundColor: tokens.bg.hover },
        },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          backgroundColor: tokens.bg.surface,
          border: `1px solid ${tokens.border.subtle}`,
          backgroundImage: "none",
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: tokens.radius.md },
      },
    },
    MuiLinearProgress: {
      styleOverrides: {
        root: { borderRadius: 4 },
      },
    },
  },
});

export default loomTheme;
