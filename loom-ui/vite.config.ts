import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import svgr from "vite-plugin-svgr";

export default defineConfig({
  plugins: [react(), svgr()],
  // The backend serves the SPA under /ui/, so bundle URLs must be absolute below
  // that prefix. A relative base ('./') resolves against the *current* route, which
  // breaks the moment a deep link like /ui/chat/sessions/<id> is reloaded.
  // The dev server picks this up too and serves at http://localhost:3000/ui/.
  base: "/ui/",
  server: {
    port: 3000,
    open: true,
    proxy: process.env.VITE_PROXY_TARGET
      ? {
          "/api": {
            target: process.env.VITE_PROXY_TARGET,
            changeOrigin: true,
          },
        }
      : undefined,
  },
  build: {
    outDir: "build",
  },
});
