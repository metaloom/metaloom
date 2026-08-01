import { describe, expect, it } from "vitest";
import { layoutPipelineGraph } from "./pipelineGraphLayout";
import { PipelineGraphPayload } from "../../types";

/** source → filter → { whisper → sentiment } */
const LINEAR: PipelineGraphPayload = {
  nodes: [
    { id: "pn1", kind: "filesystem-source", label: "Media Source", category: "SOURCE" },
    { id: "pn2", kind: "filter-mimetype", label: "Audio/Video Filter", category: "FILTER" },
    { id: "pn3", kind: "whisper", label: "Transcribe", category: "ANALYSIS" },
    { id: "pn4", kind: "sentiment", label: "Sentiment", category: "ANALYSIS" },
  ],
  edges: [
    { source: "pn1", sourcePort: "media", target: "pn2", targetPort: "media" },
    { source: "pn2", sourcePort: "media", target: "pn3", targetPort: "video" },
    { source: "pn3", sourcePort: "transcript", target: "pn4", targetPort: "text" },
  ],
};

function columnOf(payload: PipelineGraphPayload, id: string): number {
  const node = layoutPipelineGraph(payload).nodes.find(n => n.id === id);
  if (!node) throw new Error(`node ${id} was not laid out`);
  return node.column;
}

describe("layoutPipelineGraph", () => {
  it("puts a linear pipeline into one column per step", () => {
    const layout = layoutPipelineGraph(LINEAR);
    expect(layout.nodes.map(n => n.column)).toEqual([0, 1, 2, 3]);
    expect(layout.edges).toHaveLength(3);
    // Every column is to the right of the previous one
    const xs = layout.nodes.map(n => n.x);
    expect([...xs].sort((a, b) => a - b)).toEqual(xs);
  });

  it("places a node right of its deepest predecessor, not its first", () => {
    // pn4 is fed both by the source (column 0) and by pn3 (column 2) — it belongs in column 3.
    const payload: PipelineGraphPayload = {
      nodes: ["pn1", "pn2", "pn3", "pn4"].map(id => ({ id, label: id })),
      edges: [
        { source: "pn1", target: "pn2" },
        { source: "pn2", target: "pn3" },
        { source: "pn3", target: "pn4" },
        { source: "pn1", target: "pn4" },
      ],
    };
    expect(columnOf(payload, "pn4")).toBe(3);
  });

  it("stacks parallel branches in the same column", () => {
    const payload: PipelineGraphPayload = {
      nodes: ["pn1", "pn2", "pn3"].map(id => ({ id, label: id })),
      edges: [
        { source: "pn1", target: "pn2" },
        { source: "pn1", target: "pn3" },
      ],
    };
    const layout = layoutPipelineGraph(payload);
    const [, second, third] = layout.nodes;
    expect(second.column).toBe(1);
    expect(third.column).toBe(1);
    expect(second.x).toBe(third.x);
    expect(second.y).not.toBe(third.y);
  });

  it("routes an edge from the source's right edge to the target's left edge", () => {
    const layout = layoutPipelineGraph(LINEAR);
    const first = layout.nodes[0];
    const second = layout.nodes[1];
    const edge = layout.edges[0];
    expect(edge.path.startsWith(`M ${first.x + first.width} ${first.y + first.height / 2}`)).toBe(true);
    expect(edge.path.endsWith(`${second.x} ${second.y + second.height / 2}`)).toBe(true);
    expect(edge.labelX).toBeGreaterThan(first.x);
  });

  it("keeps a cyclic definition drawable", () => {
    // The parser rejects cycles on save, but a row written before that check can still reach the
    // chat — it must render rather than hang or throw.
    const payload: PipelineGraphPayload = {
      nodes: ["pn1", "pn2"].map(id => ({ id, label: id })),
      edges: [
        { source: "pn1", target: "pn2" },
        { source: "pn2", target: "pn1" },
      ],
    };
    const layout = layoutPipelineGraph(payload);
    expect(layout.nodes).toHaveLength(2);
    expect(layout.edges).toHaveLength(2);
  });

  it("drops edges pointing at nodes clipped by the payload cap", () => {
    const layout = layoutPipelineGraph({
      nodes: [{ id: "pn1", label: "kept" }],
      edges: [{ source: "pn1", target: "pn-missing" }],
    });
    expect(layout.edges).toHaveLength(0);
    expect(layout.nodes).toHaveLength(1);
  });

  it("returns an empty layout for a graph without nodes", () => {
    expect(layoutPipelineGraph({ nodes: [], edges: [] })).toEqual({ nodes: [], edges: [], width: 0, height: 0 });
  });

  it("sizes the drawing area to the graph", () => {
    const layout = layoutPipelineGraph(LINEAR, { nodeWidth: 100, nodeHeight: 30, columnGap: 20, rowGap: 10, padding: 5 });
    // 4 columns of 100 with 3 gaps of 20 plus the padding on both sides
    expect(layout.width).toBe(5 * 2 + 4 * 120 - 20);
    expect(layout.height).toBe(5 * 2 + 30);
  });
});
