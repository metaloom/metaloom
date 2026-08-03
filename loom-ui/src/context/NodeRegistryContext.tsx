import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from "react";
import type {
  NodeDescriptor,
  ContentType,
  NodeCategory,
  NodeAvailability,
  NodeAvailabilityMap,
} from "../types/nodeDescriptors";
import { fetchNodeAvailability, fetchNodeDescriptors } from "../api/nodeDescriptors";
import { subscribeNodeRegistryEvents } from "../api/pipelineEvents";
import { useAuth } from "./AuthContext";
import { isNodeAvailable, nodeIdOf } from "../features/pipeline/nodePicker";

/**
 * How long to wait before re-fetching after a descriptor change.
 *
 * A fleet restart emits one frame per worker. Without this, ten workers coming back would trigger ten
 * separate ~115 KB downloads in every open tab.
 */
const REFRESH_DEBOUNCE_MS = 500;

interface NodeRegistryContextValue {
  /** All loaded node descriptors, empty while loading. */
  descriptors: NodeDescriptor[];
  /** All content types, empty while loading. */
  contentTypes: ContentType[];
  /**
   * Fleet state keyed by node id, or undefined when the server sent none.
   *
   * Undefined is meaningful: the checked-in descriptor snapshot the offline website editor reads has
   * no availability block, and everything in it is authorable.
   */
  availability?: NodeAvailabilityMap;
  /** Whether the initial load is still in progress. */
  loading: boolean;
  /** Error from the initial load, if any. */
  error: string | null;
  /** Look up a single descriptor by its node id (the deprecated `kind` still resolves). */
  getDescriptor: (nodeId: string) => NodeDescriptor | undefined;
  /** Return descriptors filtered by category. */
  getByCategory: (category: NodeCategory) => NodeDescriptor[];
  /** Whether a worker offering this node is online. Unknown reads as available. */
  isAvailable: (nodeId: string) => boolean;
  /** Fleet state for one node, when the server sent any. */
  getAvailability: (nodeId: string) => NodeAvailability | undefined;
  /** Force a refresh from the API. */
  refresh: () => void;
}

const NodeRegistryContext = createContext<NodeRegistryContextValue>({
  descriptors: [],
  contentTypes: [],
  availability: undefined,
  loading: true,
  error: null,
  getDescriptor: () => undefined,
  getByCategory: () => [],
  isAvailable: () => true,
  getAvailability: () => undefined,
  refresh: () => {},
});

export function useNodeRegistry() {
  return useContext(NodeRegistryContext);
}

export function NodeRegistryProvider({ children }: { children: React.ReactNode }) {
  const [descriptors, setDescriptors] = useState<NodeDescriptor[]>([]);
  const [contentTypes, setContentTypes] = useState<ContentType[]>([]);
  const [availability, setAvailability] = useState<NodeAvailabilityMap | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The UI events socket is shared and single: subscribing with a different token than the rest of
  // the app tears the connection down and rebuilds it, which would drop live pipeline events on the
  // floor every time this provider mounted.
  const { token } = useAuth();

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchNodeDescriptors()
      .then((data) => {
        setDescriptors(data.nodeDescriptors);
        setContentTypes(data.contentTypes);
        setAvailability(data.availability);
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

  // Enrich with worker names once a token is in hand.
  //
  // `providedBy` is only served from the authenticated availability route: the main descriptor
  // response loads before login, so it cannot resolve a caller to check a permission against and
  // never names a worker. Without this second call the palette can say a node is offline but not
  // which worker to go and start.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    fetchNodeAvailability(token)
      .then((enriched) => {
        if (!cancelled) setAvailability((previous) => ({ ...(previous ?? {}), ...enriched }));
      })
      .catch((err) => {
        // A caller without READ_CORTEX_INSTANCE simply keeps the unnamed view - the picker is built
        // to read sensibly without provider names, so this is not worth surfacing.
        console.debug("Node availability could not be enriched with worker names", err);
      });
    return () => {
      cancelled = true;
    };
  }, [token, descriptors]);

  // Live registry updates. Before this, the registry was fetched once at mount and never again, so a
  // worker that connected after the tab opened was invisible until a reload - and `refresh` below
  // existed but nothing ever called it.
  useEffect(() => {
    const unsubscribe = subscribeNodeRegistryEvents((event) => {
      if (event.type === "NODE_AVAILABILITY_CHANGED") {
        // Presence only: patch in place and fetch nothing.
        setAvailability((previous) => ({ ...(previous ?? {}), ...(event.availability ?? {}) }));
        return;
      }
      if (event.type === "NODE_DESCRIPTORS_CHANGED") {
        if (refreshTimer.current) clearTimeout(refreshTimer.current);
        refreshTimer.current = setTimeout(() => {
          refreshTimer.current = null;
          load();
        }, REFRESH_DEBOUNCE_MS);
      }
    }, token);
    return () => {
      if (refreshTimer.current) clearTimeout(refreshTimer.current);
      unsubscribe();
    };
  }, [load, token]);

  const getDescriptor = useCallback(
    (nodeId: string) => descriptors.find((d) => nodeIdOf(d) === nodeId),
    [descriptors],
  );

  const getByCategory = useCallback(
    (category: NodeCategory) => descriptors.filter((d) => d.category === category),
    [descriptors],
  );

  const isAvailable = useCallback(
    (nodeId: string) => isNodeAvailable(nodeId, availability),
    [availability],
  );

  const getAvailability = useCallback(
    (nodeId: string) => availability?.[nodeId],
    [availability],
  );

  return (
    <NodeRegistryContext.Provider
      value={{
        descriptors,
        contentTypes,
        availability,
        loading,
        error,
        getDescriptor,
        getByCategory,
        isAvailable,
        getAvailability,
        refresh: load,
      }}
    >
      {children}
    </NodeRegistryContext.Provider>
  );
}
