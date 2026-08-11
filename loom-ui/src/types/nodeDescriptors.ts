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
  /**
   * A list of rows whose `id`s become the node's output ports, edited as repeatable rows with an
   * add button rather than as raw JSON.
   *
   * The distinction from `JSON` is behavioural, not cosmetic: a `JSON` parameter commits its raw
   * text on every keystroke, so a half-typed value parses to nothing and every derived handle
   * momentarily disappears. A parameter that *defines ports* cannot behave that way, so its editor
   * always emits a structurally valid array.
   */
  | "PORT_LIST"
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
  /**
   * Output side only: this port carries data for some items and not others, so the engine skips a
   * node wired to it for the items where nothing was emitted. Drawn as a dashed handle — the edge
   * exists, but it does not carry every item.
   */
  selective?: boolean;
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
  /**
   * Bounds and granularity for an `INTEGER`/`NUMBER` field, from the parameter's `@ParamDoc`.
   *
   * The backend has always emitted these — `facedetect`'s cluster radius declares `0 … 2` in steps
   * of `0.05` — but the editor ignored them, so a field whose valid range the node knows exactly
   * still accepted any number and failed at the worker instead. All three are optional: most
   * parameters declare none, and a bound of `0` is meaningful, so read them with `!== undefined`
   * rather than as truthy.
   */
  min?: number;
  max?: number;
  step?: number;
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
  /**
   * The node **type** id — `whisper`, `acme-nsfw`.
   *
   * Not the graph-instance id (`PipelineNode.id`, and `nodeId` on the run events), and not the
   * worker id (`Processor.nodeId`). Three different things wear this name; check which one you have.
   *
   * Optional because it genuinely can be absent: the checked-in `node-descriptors.json` snapshot the
   * offline website editor reads predates the rename and carries only `kind`. Read it through
   * `nodeIdOf()` rather than directly, and this stays a non-issue.
   */
  nodeId?: string;
  /** @deprecated legacy alias of {@link nodeId}; the backend emits both for one release */
  kind: string;
  /**
   * Contract version as announced by the worker offering this node, or absent for a built-in.
   *
   * When several workers offer one node on different versions the backend serves the **lowest**, so
   * a port that only exists on newer workers is not offered until the last old one is gone.
   */
  version?: string;
  name: string;
  description: string;
  icon: string;
  category: NodeCategory;
  /**
   * Card colour authored on the node's `@NodeSpec`, as a `#rgb`/`#rrggbb` literal, or absent — which
   * is the normal case, and means the node takes its {@link NodeCategory} default.
   *
   * The backend rejects anything that is not a hex literal, so this can be written straight into a
   * style without escaping.
   */
  color?: string;
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

/**
 * Runtime fleet state for one node type, served **beside** its contract rather than inside it.
 *
 * The separation is deliberate: `NodeDescriptor` is the one contract type — the object a worker
 * announces, the backend serves, the checked-in snapshot holds and the graph analyzer validates
 * against. Availability is a fact about the fleet at this instant, and belongs next to it.
 */
export interface NodeAvailability {
  /** `BUILTIN` (compiled into Loom) or `ANNOUNCED` (contributed by a worker). */
  source?: "BUILTIN" | "ANNOUNCED";
  /**
   * Whether a worker offering this node is currently online.
   *
   * A state query on the backend, not a timestamp comparison — a worker announces once and then
   * stays connected for days, so anything derived from "was this seen recently" would grey out the
   * whole palette one heartbeat after a healthy fleet connected.
   */
  available: boolean;
  /** When a worker offering this node was last seen alive (heartbeat-driven). */
  lastSeen?: string;
  /** When the contract last arrived over the socket. Diagnostic only — see {@link lastSeen}. */
  lastAnnounced?: string;
  /** Worker ids currently offering it. Only served to a caller with `READ_CORTEX_INSTANCE`. */
  providedBy?: string[];
  /** Whether the workers offering this node disagree about its contract or version. */
  versionSkew?: boolean;
}

/** Fleet state keyed by node id. */
export type NodeAvailabilityMap = Record<string, NodeAvailability>;

export interface NodeDescriptorsResponse {
  nodeDescriptors: NodeDescriptor[];
  contentTypes: ContentType[];
  /**
   * Absent from the checked-in `node-descriptors.json` the offline website editor reads.
   *
   * A missing block — and a missing entry within it — must read as **available**: that editor has no
   * fleet, and every node in it is authorable.
   */
  availability?: NodeAvailabilityMap;
}
