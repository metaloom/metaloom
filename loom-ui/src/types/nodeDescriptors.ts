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

export interface NodeInput {
  name: string;
  contentType: string;
  required?: boolean;
}

export interface NodeOutput {
  name: string;
  contentType: string;
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
  inputs: NodeInput[];
  outputs: NodeOutput[];
  parameters: NodeParameter[];
  defaultConcurrency: number;
  defaultMode: NodeMode;
  defaultBlocking: boolean;
  events: string[];
}

export interface ContentType {
  id: string;
  label: string;
  superType: string | null;
}

export interface NodeDescriptorsResponse {
  nodeDescriptors: NodeDescriptor[];
  contentTypes: ContentType[];
}
