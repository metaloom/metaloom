import { API_BASE_URL } from "./config";
import type {
  NodeDescriptor,
  ContentType,
  NodeAvailabilityMap,
  NodeDescriptorsResponse,
} from "../types/nodeDescriptors";

/** Fetch all node descriptors, content types and fleet availability in a single call. */
export async function fetchNodeDescriptors(): Promise<NodeDescriptorsResponse> {
  const res = await fetch(`${API_BASE_URL}/pipeline/node-descriptors`);
  if (!res.ok) throw new Error(`Failed to fetch node descriptors: ${res.status}`);
  return res.json();
}

/**
 * Fetch only which nodes can currently run.
 *
 * Presence flips on every worker connect, disconnect and restart, while the full descriptor response
 * is ~115 KB for 34 nodes. Re-downloading all of that in every open tab to learn that one boolean
 * changed is what this route exists to avoid.
 *
 * This route is **authenticated**, and it is the only one that returns `providedBy`. The main
 * descriptor response has to load before login, so it cannot resolve a caller to check a permission
 * against and therefore never names a worker at all.
 */
export async function fetchNodeAvailability(token?: string | null): Promise<NodeAvailabilityMap> {
  const res = await fetch(`${API_BASE_URL}/pipeline/node-descriptors/availability`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Failed to fetch node availability: ${res.status}`);
  return res.json();
}

/** Fetch a single node descriptor by kind. */
export async function fetchNodeDescriptor(kind: string): Promise<NodeDescriptor> {
  const res = await fetch(`${API_BASE_URL}/pipeline/node-descriptors/${encodeURIComponent(kind)}`);
  if (!res.ok) throw new Error(`Failed to fetch node descriptor '${kind}': ${res.status}`);
  return res.json();
}

/** Fetch all known content types. */
export async function fetchContentTypes(): Promise<ContentType[]> {
  const res = await fetch(`${API_BASE_URL}/pipeline/content-types`);
  if (!res.ok) throw new Error(`Failed to fetch content types: ${res.status}`);
  return res.json();
}
