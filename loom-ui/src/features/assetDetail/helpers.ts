import { Asset, AssetType, AssetStatus } from "../../types";
import { AssetResponse } from "../../api/assets";

/** Map a Loom REST AssetResponse to the local Asset type used by the UI. */
export function apiToAsset(r: AssetResponse): Asset {
  const mime = r.file?.mimeType ?? "";
  let type: AssetType = "unknown";
  if (mime.startsWith("image/")) type = "image";
  else if (mime.startsWith("video/")) type = "video";
  else if (mime.startsWith("audio/")) type = "audio";
  else if (mime.startsWith("application/") || mime.startsWith("text/")) type = "document";

  const video = r.videoComponents?.[0];
  const image = r.imageComponents?.[0];

  return {
    id: r.uuid,
    spaceId: "",
    libraryId: "",
    name: r.file?.filename ?? r.uuid,
    type,
    status: "ready" as AssetStatus,
    tags: (r.tags ?? []).map(t => t.name),
    description: "",
    duration: video?.duration,
    width: video?.width ?? image?.width,
    height: video?.height ?? image?.height,
    fileSize: r.file?.size ?? 0,
    mimeType: mime,
    thumbnailUrl: "",
    url: "",
    ownerId: r.status?.creator?.uuid ?? "",
    collectionIds: (r.collections ?? []).map(c => c.uuid),
    taskIds: [],
    createdAt: r.status?.created ?? "",
    updatedAt: r.status?.edited ?? "",
    metadata: {},
  };
}

export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}:${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

export function userName(userId: string) {
  return userId;
}

// Tag parent hierarchy (mock) — tag → parent chain from root to immediate parent
const TAG_HIERARCHY: Record<string, string[]> = {
  suv: ["vehicles", "cars"],
  sedan: ["vehicles", "cars"],
  supercars: ["vehicles", "cars"],
  cars: ["vehicles"],
  trucks: ["vehicles"],
  vehicles: [],
  nature: ["outdoor"],
  wildlife: ["outdoor", "nature"],
  landscape: ["outdoor"],
  aerial: ["outdoor"],
  drone: ["outdoor", "aerial"],
  portrait: ["photography"],
  macro: ["photography"],
  street: ["photography", "outdoor"],
  fashion: ["photography"],
  studio: ["photography", "indoor"],
  indoor: [],
  outdoor: [],
  urban: ["outdoor"],
  architecture: ["outdoor", "urban"],
  food: [],
  travel: [],
  sports: [],
  music: [],
  hero: ["editorial"],
  archive: ["editorial"],
  "b-roll": ["editorial"],
  timelapse: ["editorial", "technique"],
  interview: ["editorial"],
};

export function tagBreadcrumb(tag: string): string {
  const parents = TAG_HIERARCHY[tag.toLowerCase()];
  if (!parents || parents.length === 0) return "";
  return [...parents, tag.toLowerCase()].join(" → ");
}
