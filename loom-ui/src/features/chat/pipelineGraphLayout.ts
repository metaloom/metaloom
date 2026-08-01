import { PipelineGraphEdge, PipelineGraphNode, PipelineGraphPayload } from "../../types";

/**
 * Layered layout for the compact pipeline graph shown inline in the chat (CHAT.md §6.1).
 *
 * The stored definition carries `x`/`y` from the pipeline editor, but those coordinates are laid out
 * for a full-screen canvas — dropped into a chat bubble they would leave the graph mostly empty and
 * mostly off-screen. The layout is therefore recomputed here from the edges alone: every node sits
 * one column right of its deepest predecessor, which is exactly the reading order of a pipeline
 * (source → analysis → output).
 */

export interface LayoutOptions {
  nodeWidth?: number;
  nodeHeight?: number;
  /** Horizontal gap between two columns. */
  columnGap?: number;
  /** Vertical gap between two nodes of the same column. */
  rowGap?: number;
  padding?: number;
}

export interface LaidOutNode extends PipelineGraphNode {
  x: number;
  y: number;
  width: number;
  height: number;
  column: number;
}

export interface LaidOutEdge extends PipelineGraphEdge {
  /** Cubic bezier path connecting the source node's right edge to the target node's left edge. */
  path: string;
  /** Midpoint of the path, where a branch label is placed. */
  labelX: number;
  labelY: number;
}

export interface PipelineGraphLayout {
  nodes: LaidOutNode[];
  edges: LaidOutEdge[];
  width: number;
  height: number;
}

const DEFAULTS: Required<LayoutOptions> = {
  nodeWidth: 132,
  nodeHeight: 36,
  columnGap: 44,
  rowGap: 12,
  padding: 8,
};

/**
 * Assign every node to a column: `column(n) = 1 + max(column(predecessors))`, sources start at 0.
 *
 * Implemented as a relaxation over the edges rather than a topological sort so that a cyclic
 * definition — which the parser rejects but which can still sit in the database, since the graph is
 * only validated on save — still produces a drawable layout instead of an exception. The iteration
 * count bounds the relaxation; past it the remaining nodes keep the column they reached.
 */
function assignColumns(nodes: PipelineGraphNode[], edges: PipelineGraphEdge[]): Map<string, number> {
  const columns = new Map<string, number>(nodes.map(n => [n.id, 0]));
  const known = new Set(nodes.map(n => n.id));
  const relevant = edges.filter(e => known.has(e.source) && known.has(e.target) && e.source !== e.target);

  for (let pass = 0; pass < nodes.length; pass++) {
    let changed = false;
    for (const edge of relevant) {
      const next = (columns.get(edge.source) ?? 0) + 1;
      if (next > (columns.get(edge.target) ?? 0)) {
        columns.set(edge.target, next);
        changed = true;
      }
    }
    if (!changed) break;
  }
  return columns;
}

/**
 * Lay the graph out left to right. Returns the positioned nodes, the routed edges and the size of
 * the drawing area (the caller scrolls it horizontally when it exceeds the chat column).
 */
export function layoutPipelineGraph(payload: PipelineGraphPayload, options: LayoutOptions = {}): PipelineGraphLayout {
  const opts = { ...DEFAULTS, ...options };
  const nodes = payload?.nodes ?? [];
  const edges = payload?.edges ?? [];

  if (nodes.length === 0) {
    return { nodes: [], edges: [], width: 0, height: 0 };
  }

  const columns = assignColumns(nodes, edges);

  // Nodes keep their definition order inside a column, so the diagram is stable across renders
  // and matches the order the editor lists them in.
  const rowOf = new Map<string, number>();
  const perColumn = new Map<number, number>();
  for (const node of nodes) {
    const column = columns.get(node.id) ?? 0;
    const row = perColumn.get(column) ?? 0;
    rowOf.set(node.id, row);
    perColumn.set(column, row + 1);
  }

  const tallestColumn = Math.max(...perColumn.values());
  const columnCount = Math.max(...columns.values()) + 1;
  const rowPitch = opts.nodeHeight + opts.rowGap;
  const columnPitch = opts.nodeWidth + opts.columnGap;
  const contentHeight = tallestColumn * rowPitch - opts.rowGap;

  const laidOut: LaidOutNode[] = nodes.map(node => {
    const column = columns.get(node.id) ?? 0;
    const row = rowOf.get(node.id) ?? 0;
    const rows = perColumn.get(column) ?? 1;
    // Short columns are centred against the tallest one so the graph reads as a flow rather than
    // as rows stuck to the top edge.
    const columnHeight = rows * rowPitch - opts.rowGap;
    return {
      ...node,
      column,
      width: opts.nodeWidth,
      height: opts.nodeHeight,
      x: opts.padding + column * columnPitch,
      y: opts.padding + (contentHeight - columnHeight) / 2 + row * rowPitch,
    };
  });

  const byId = new Map(laidOut.map(n => [n.id, n]));
  const routed: LaidOutEdge[] = [];
  for (const edge of edges) {
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    // An edge pointing at a node that was clipped by the payload cap has nothing to connect.
    if (!source || !target) continue;

    const x1 = source.x + source.width;
    const y1 = source.y + source.height / 2;
    const x2 = target.x;
    const y2 = target.y + target.height / 2;
    const curve = Math.max(16, Math.abs(x2 - x1) / 2);
    routed.push({
      ...edge,
      path: `M ${x1} ${y1} C ${x1 + curve} ${y1}, ${x2 - curve} ${y2}, ${x2} ${y2}`,
      labelX: (x1 + x2) / 2,
      labelY: (y1 + y2) / 2,
    });
  }

  return {
    nodes: laidOut,
    edges: routed,
    width: opts.padding * 2 + columnCount * columnPitch - opts.columnGap,
    height: opts.padding * 2 + contentHeight,
  };
}
