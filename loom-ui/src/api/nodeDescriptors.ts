import { API_BASE_URL } from "./config";
import type { NodeDescriptor, ContentType, NodeDescriptorsResponse } from "../types/nodeDescriptors";

/** Fetch all node descriptors and content types in a single call. */
export async function fetchNodeDescriptors(): Promise<NodeDescriptorsResponse> {
  const res = await fetch(`${API_BASE_URL}/pipeline/node-descriptors`);
  if (!res.ok) throw new Error(`Failed to fetch node descriptors: ${res.status}`);
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
