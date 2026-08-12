import { assetBinaryUrl, type AssetResponse } from "../../api/assets";
import type { SearchHitResponse } from "../../api/search";
import type { Asset, AssetType } from "../../types";

/** Classify an asset by its mime type. The browser only ever sees these five buckets. */
export function assetTypeFromMime(mime: string | undefined): AssetType {
  if (!mime) return "unknown";
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  if (mime.startsWith("application/") || mime.startsWith("text/")) return "document";
  return "unknown";
}

/**
 * The `?mime=` prefix that narrows a search to one asset type, or `undefined` for "all".
 *
 * `document` covers two prefixes and cannot be expressed as one, so it stays a local concern —
 * callers must not silently send half of it.
 */
export function mimeFilterFor(type: AssetType | "all"): string | undefined {
  switch (type) {
    case "image": return "image/";
    case "video": return "video/";
    case "audio": return "audio/";
    default: return undefined;
  }
}

/**
 * Convert a component's duration to the unit the rest of the UI counts in.
 *
 * `asset_video_comp.media_duration` is **milliseconds** and the REST layer passes it through
 * unchanged, while every consumer here — `formatDuration`, `VideoTimeline`, and an
 * `HTMLMediaElement.currentTime` — works in seconds. Converting at the boundary keeps the two from
 * meeting: a 28 second clip read raw renders as "7:51:07".
 */
export function durationSeconds(millis: number | undefined): number | undefined {
  return millis === undefined || millis === null ? undefined : millis / 1000;
}

/** Map a Loom REST AssetResponse to the local Asset type used by the UI. */
export function toAsset(r: AssetResponse): Asset {
  const mime = r.file?.mimeType ?? "";
  const type = assetTypeFromMime(mime);

  const video = r.videoComponents?.[0];
  const image = r.imageComponents?.[0];

  return {
    id: r.uuid,
    spaceId: "",
    libraryId: "",
    name: r.file?.filename ?? r.uuid,
    type,
    status: "ready" as Asset["status"],
    tags: (r.tags ?? []).map(t => t.name),
    description: "",
    duration: durationSeconds(video?.duration),
    width: video?.width ?? image?.width,
    height: video?.height ?? image?.height,
    fileSize: r.file?.size ?? 0,
    mimeType: mime,
    sha512: r.hashes?.sha512,
    // Images and videos are both previewed from the stored binary — an <img> for one, a muted
    // <video> seeking one frame for the other (see components/AssetThumbnail). Audio and PDFs have
    // no browser-renderable preview and fall back to the type placeholder.
    thumbnailUrl: type === "image" || type === "video" ? assetBinaryUrl(r.uuid) : "",
    url: "",
    ownerId: r.status?.creator?.uuid ?? "",
    collectionIds: (r.collections ?? []).map(c => c.uuid),
    createdAt: r.status?.created ?? "",
    updatedAt: r.status?.edited ?? "",
    metadata: {},
  };
}

/**
 * Map a `/search/assets` hit onto the card shape.
 *
 * A hit is not an asset: the search index carries what it needs to rank and label a result, so
 * tags, dimensions, duration, library and collection membership simply are not in the response.
 * They come back as empty rather than invented, and the browser hides those affordances while a
 * query is active — see LOOM_UI.md §7.5.
 */
export function hitToCard(hit: SearchHitResponse): Asset {
  const mime = hit.mimeType ?? "";
  const type = assetTypeFromMime(mime);

  return {
    id: hit.uuid,
    spaceId: "",
    libraryId: "",
    name: hit.title,
    type,
    status: "ready" as Asset["status"],
    tags: [],
    description: hit.subtitle ?? "",
    fileSize: hit.size ?? 0,
    mimeType: mime,
    thumbnailUrl: type === "image" || type === "video" ? assetBinaryUrl(hit.uuid) : "",
    url: "",
    ownerId: "",
    collectionIds: [],
    // `sortDate` is epoch **seconds** with a fractional part — the server serializes Instant as a
    // number, not an ISO string. Multiply before handing it to Date.
    createdAt: hit.sortDate !== undefined ? new Date(hit.sortDate * 1000).toISOString() : "",
    updatedAt: "",
    metadata: {},
  };
}
