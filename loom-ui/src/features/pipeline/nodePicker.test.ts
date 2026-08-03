import { describe, expect, it } from "vitest";

import type { NodeAvailabilityMap, NodeDescriptor } from "../../types/nodeDescriptors";
import {
  hiddenOfflineCount,
  isNodeAvailable,
  nodeIdOf,
  offlineReason,
  selectPickerNodes,
} from "./nodePicker";

function descriptor(nodeId: string, name: string, category = "ANALYSIS"): NodeDescriptor {
  return {
    nodeId,
    kind: nodeId,
    name,
    description: "",
    icon: "",
    category: category as NodeDescriptor["category"],
    inputPorts: [],
    outputPorts: [],
    inputGroups: [],
    outputGroups: [],
    dynamicPorts: false,
    parameters: [],
    defaultConcurrency: 1,
    defaultMode: "PARALLEL",
    defaultBlocking: true,
    events: [],
  };
}

const whisper = descriptor("whisper", "Whisper");
const dedup = descriptor("dedup", "Dedup");
const acme = descriptor("acme-nsfw", "NSFW Classifier");

const descriptors = [whisper, dedup, acme];

const fleet: NodeAvailabilityMap = {
  whisper: { available: true, source: "BUILTIN" },
  dedup: { available: true, source: "BUILTIN" },
  "acme-nsfw": { available: false, source: "ANNOUNCED", providedBy: ["cortex-gpu-01"] },
};

describe("selectPickerNodes", () => {
  it("keeps everything when there is no query", () => {
    const ids = selectPickerNodes(descriptors).map((e) => nodeIdOf(e.descriptor));
    expect(ids).toEqual(["whisper", "dedup", "acme-nsfw"]);
  });

  it("filters by name, node id and category", () => {
    expect(selectPickerNodes(descriptors, { query: "whis" }).map((e) => e.descriptor.name)).toEqual(["Whisper"]);
    expect(selectPickerNodes(descriptors, { query: "acme-nsfw" }).map((e) => e.descriptor.name)).toEqual([
      "NSFW Classifier",
    ]);
    expect(selectPickerNodes(descriptors, { query: "analysis" })).toHaveLength(3);
  });

  it("is case insensitive", () => {
    expect(selectPickerNodes(descriptors, { query: "WHISPER" })).toHaveLength(1);
  });

  it("sorts offline nodes last without removing them", () => {
    const ids = selectPickerNodes(descriptors, { availability: fleet }).map((e) => nodeIdOf(e.descriptor));
    expect(ids).toEqual(["whisper", "dedup", "acme-nsfw"]);

    const offlineWhisper: NodeAvailabilityMap = { ...fleet, whisper: { available: false } };
    const reordered = selectPickerNodes(descriptors, { availability: offlineWhisper }).map((e) =>
      nodeIdOf(e.descriptor),
    );
    expect(reordered).toEqual(["dedup", "whisper", "acme-nsfw"]);
  });

  it("preserves the server's ordering within each availability group", () => {
    const offlineFirstTwo: NodeAvailabilityMap = {
      whisper: { available: false },
      dedup: { available: false },
      "acme-nsfw": { available: true },
    };
    const ids = selectPickerNodes(descriptors, { availability: offlineFirstTwo }).map((e) => nodeIdOf(e.descriptor));
    // acme is promoted, but whisper still precedes dedup as the server sent them.
    expect(ids).toEqual(["acme-nsfw", "whisper", "dedup"]);
  });

  it("hides offline nodes only when asked", () => {
    const ids = selectPickerNodes(descriptors, { availability: fleet, showOffline: false }).map((e) =>
      nodeIdOf(e.descriptor),
    );
    expect(ids).toEqual(["whisper", "dedup"]);
  });

  it("still applies the query when hiding offline nodes", () => {
    expect(selectPickerNodes(descriptors, { availability: fleet, showOffline: false, query: "nsfw" })).toHaveLength(0);
  });

  it("treats a missing availability block as everything being available", () => {
    // The checked-in node-descriptors.json the offline website editor reads has no such block. If
    // that read as "unavailable", every node in that editor would sort last and vanish behind the
    // toggle - an empty palette with no explanation.
    const ids = selectPickerNodes(descriptors, { showOffline: false }).map((e) => nodeIdOf(e.descriptor));
    expect(ids).toEqual(["whisper", "dedup", "acme-nsfw"]);
  });

  it("treats a node missing from a present availability block as available", () => {
    const partial: NodeAvailabilityMap = { whisper: { available: true } };
    expect(selectPickerNodes(descriptors, { availability: partial, showOffline: false })).toHaveLength(3);
  });

  it("survives an empty descriptor list", () => {
    expect(selectPickerNodes([], { query: "x" })).toEqual([]);
  });

  it("carries the fleet state through for the row to render", () => {
    const entry = selectPickerNodes(descriptors, { availability: fleet }).find(
      (e) => nodeIdOf(e.descriptor) === "acme-nsfw",
    );
    expect(entry?.available).toBe(false);
    expect(entry?.state?.providedBy).toEqual(["cortex-gpu-01"]);
  });
});

describe("nodeIdOf", () => {
  it("falls back to the deprecated kind when a server has not been upgraded", () => {
    const legacy = { ...whisper, nodeId: undefined } as unknown as NodeDescriptor;
    expect(nodeIdOf(legacy)).toBe("whisper");
  });
});

describe("isNodeAvailable", () => {
  it("defaults to available for anything it has no information about", () => {
    expect(isNodeAvailable("whisper", undefined)).toBe(true);
    expect(isNodeAvailable("unknown", fleet)).toBe(true);
    expect(isNodeAvailable("acme-nsfw", fleet)).toBe(false);
  });
});

describe("hiddenOfflineCount", () => {
  it("counts nothing while offline nodes are shown", () => {
    expect(hiddenOfflineCount(descriptors, { availability: fleet })).toBe(0);
  });

  it("counts what the toggle is hiding", () => {
    expect(hiddenOfflineCount(descriptors, { availability: fleet, showOffline: false })).toBe(1);
  });

  it("counts only within the current query", () => {
    expect(hiddenOfflineCount(descriptors, { availability: fleet, showOffline: false, query: "whisper" })).toBe(0);
  });
});

describe("offlineReason", () => {
  it("says nothing for an available node", () => {
    expect(offlineReason({ available: true })).toBeUndefined();
    expect(offlineReason(undefined)).toBeUndefined();
  });

  it("names the last provider when it is allowed to", () => {
    expect(offlineReason({ available: false, providedBy: ["cortex-gpu-01"] })).toContain("cortex-gpu-01");
  });

  it("still explains itself without provider names", () => {
    // providedBy needs READ_CORTEX_INSTANCE, and the palette loads before anyone has logged in.
    expect(offlineReason({ available: false })).toBe("no worker currently offers this node");
    expect(offlineReason({ available: false, providedBy: [] })).toBe("no worker currently offers this node");
  });
});
