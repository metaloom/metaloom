/** Byte and progress formatting for the upload screen. Pure functions, so they are unit-testable. */

/** Human-readable size. Decimal units, matching what the pool screen shows for free space. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} MB`;
  if (bytes >= 1e3) return `${(bytes / 1e3).toFixed(0)} KB`;
  return `${bytes} B`;
}

/** Per-item percentage, clamped so a rounding overshoot never renders a bar past its track. */
export function percentOf(loaded: number, size: number): number {
  if (size <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((loaded / size) * 100)));
}

/**
 * The label under a file row: "1.2 MB of 4.0 MB" while sending, plain size once finished.
 */
export function progressLabel(loaded: number, size: number, settled: boolean): string {
  if (settled) return formatBytes(size);
  return `${formatBytes(loaded)} / ${formatBytes(size)}`;
}
