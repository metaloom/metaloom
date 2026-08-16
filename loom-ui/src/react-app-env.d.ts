/// <reference types="vite/client" />
/// <reference types="vite-plugin-svgr/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /** Parallel uploads; 1..8, anything else falls back to 3. See features/uploads/uploadQueue.ts. */
  readonly VITE_UPLOAD_CONCURRENCY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
