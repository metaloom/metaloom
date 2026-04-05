import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import svgr from "vite-plugin-svgr";

export default defineConfig({
  plugins: [react(), svgr()],
  base: './',
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
