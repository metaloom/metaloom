/** Base URL for the Loom REST API, configurable via VITE_API_BASE_URL env var. */
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/+$/, "") ??
  "http://localhost:8092/api/v1";
