// ── Node Descriptor types matching the Loom REST API ──────────────────────────

export type NodeCategory = "SOURCE" | "FILTER" | "ANALYSIS" | "TRANSFORM" | "OUTPUT";
export type NodeMode = "SEQUENTIAL" | "PARALLEL";
/**
 * Mirrors the Java `ParameterType` enum in loom-shared/node-model.
 *
 * `FLOAT` and `STRING_LIST` are legacy aliases: the backend has always emitted `NUMBER` and
 * `ENUM_SET`, so nothing ever sent them. They are kept so any hand-written descriptor or test
 * fixture using them keeps rendering, and should be removed once none remain.
 */
export type ParameterType =
  | "STRING"
  | "INTEGER"
  | "NUMBER"
  | "BOOLEAN"
  | "ENUM"
  | "ENUM_SET"
  | "CODE"
  | "JSON"
  /** @deprecated use NUMBER */
  | "FLOAT"
  /** @deprecated use ENUM_SET */
  | "STRING_LIST";

/** Whether a port carries exactly one element or a sequence of them. Mirrors Java `Cardinality`. */
export type Cardinality = "ONE" | "MANY";

/** How the members of a {@link PortGroup} relate. Mirrors Java `PortGroupMode`. */
export type PortGroupMode = "XOR" | "EXCLUSIVE";

/**
 * A typed connector on a node — one input or one output. Mirrors Java `PortSpec`.
 *
 * The `id` is the stable identity: edges reference it as `sourcePort`/`targetPort` and the editor
 * uses it verbatim as the React Flow handle id, so reordering a node's ports never re-points an
 * existing edge.
 */
export interface PortSpec {
  id: string;
  label?: string;
  contentType: string;
  cardinality: Cardinality;
  required: boolean;
  /** Id of the {@link PortGroup} this port belongs to, if any. The group then owns `required`. */
  group?: string;
  description?: string;
}

/**
 * A set of ports that are alternatives (`XOR`, inputs) or mutually exclusive (`EXCLUSIVE`,
 * outputs). Mirrors Java `PortGroup`.
 */
export interface PortGroup {
  id: string;
  mode: PortGroupMode;
  required: boolean;
  label?: string;
}

export interface NodeParameter {
  key: string;
  type: ParameterType;
  defaultValue?: unknown;
  label: string;
  description: string;
  /** The field the backend actually emits for enum choices. */
  values?: string[];
  /** @deprecated legacy alias for `values` */
  allowedValues?: string[];
  /** Syntax hint for CODE parameters, e.g. "javascript". */
  language?: string;
  /** Preferred editor height in rows, for CODE/JSON parameters. */
  rows?: number;
}

export interface NodeDescriptor {
  kind: string;
  name: string;
  description: string;
  icon: string;
  category: NodeCategory;
  inputPorts: PortSpec[];
  outputPorts: PortSpec[];
  inputGroups: PortGroup[];
  outputGroups: PortGroup[];
  /**
   * Whether this kind's ports depend on its configuration. `script`, `llm` and `vlm` set it; the
   * editor then derives the handles through the resolver mirrors in
   * `features/pipeline/portResolvers.ts` instead of using `outputPorts`.
   */
  dynamicPorts: boolean;
  parameters: NodeParameter[];
  defaultConcurrency: number;
  defaultMode: NodeMode;
  defaultBlocking: boolean;
  events: string[];
}

/**
 * One entry of the served content-type vocabulary. Ids are always `family/subtype`.
 *
 * This is the UI's **only** source of labels and descriptions — the vocabulary is never hardcoded
 * in TypeScript. Only the assignability rule and the per-family colours live locally, in
 * `features/pipeline/contentTypes.ts`.
 */
export interface ContentType {
  id: string;
  label: string;
  /** The family part of the id — the editor's colour key (e.g. `media`, `detection`). */
  family: string;
  description?: string;
  /** Whether this is the family wildcard, e.g. `media/*`. */
  wildcard: boolean;
}

export interface NodeDescriptorsResponse {
  nodeDescriptors: NodeDescriptor[];
  contentTypes: ContentType[];
}
