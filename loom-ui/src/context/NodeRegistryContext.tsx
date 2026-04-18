import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import type { NodeDescriptor, ContentType, NodeCategory } from "../types/nodeDescriptors";
import { fetchNodeDescriptors } from "../api/nodeDescriptors";

interface NodeRegistryContextValue {
  /** All loaded node descriptors, empty while loading. */
  descriptors: NodeDescriptor[];
  /** All content types, empty while loading. */
  contentTypes: ContentType[];
  /** Whether the initial load is still in progress. */
  loading: boolean;
  /** Error from the initial load, if any. */
  error: string | null;
  /** Look up a single descriptor by its kind. */
  getDescriptor: (kind: string) => NodeDescriptor | undefined;
  /** Return descriptors filtered by category. */
  getByCategory: (category: NodeCategory) => NodeDescriptor[];
  /** Force a refresh from the API. */
  refresh: () => void;
}

const NodeRegistryContext = createContext<NodeRegistryContextValue>({
  descriptors: [],
  contentTypes: [],
  loading: true,
  error: null,
  getDescriptor: () => undefined,
  getByCategory: () => [],
  refresh: () => {},
});

export function useNodeRegistry() {
  return useContext(NodeRegistryContext);
}

export function NodeRegistryProvider({ children }: { children: React.ReactNode }) {
  const [descriptors, setDescriptors] = useState<NodeDescriptor[]>([]);
  const [contentTypes, setContentTypes] = useState<ContentType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchNodeDescriptors()
      .then((data) => {
        setDescriptors(data.nodeDescriptors);
        setContentTypes(data.contentTypes);
      })
      .catch((err) => {
        console.error("Failed to load node descriptors", err);
        setError(err.message ?? "Unknown error");
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const getDescriptor = useCallback(
    (kind: string) => descriptors.find((d) => d.kind === kind),
    [descriptors],
  );

  const getByCategory = useCallback(
    (category: NodeCategory) => descriptors.filter((d) => d.category === category),
    [descriptors],
  );

  return (
    <NodeRegistryContext.Provider
      value={{ descriptors, contentTypes, loading, error, getDescriptor, getByCategory, refresh: load }}
    >
      {children}
    </NodeRegistryContext.Provider>
  );
}
