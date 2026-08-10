// Formatting shared by the operator screens.
//
// It lives here rather than beside any one feature because two unrelated admin screens
// need the identical rendering: a size on the search-index screen and a size on the
// storage screen have to agree, or the same volume reads differently depending on which
// tab you opened.
//
// Note that this is deliberately NOT the only formatBytes in the tree. The upload and
// asset screens use decimal units (MB, GB) because that is what a file manager shows a
// user about their own file; this one uses binary units because that is what a
// filesystem reports about a volume. Unifying them would silently restate every
// existing number.

/**
 * Human-readable byte size, in binary units.
 *
 * Binary units because that is what a filesystem reports, and one decimal place above
 * KiB because "1.4 GiB" and "1 GiB" are meaningfully different numbers to an operator
 * watching a volume fill.
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  const exponent = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  const value = bytes / Math.pow(1024, exponent);
  return `${exponent === 0 ? value : value.toFixed(1)} ${units[exponent]}`;
}

/**
 * A byte size that may be unknown.
 *
 * An object store reports no capacity at all, and the difference between "0 bytes free"
 * and "cannot say" is the difference between an emergency and a non-question. Rendering
 * null as "0 B" would report the second as the first.
 */
export function formatBytesOrUnknown(bytes: number | null | undefined, unknownLabel: string): string {
  return bytes === null || bytes === undefined ? unknownLabel : formatBytes(bytes);
}
