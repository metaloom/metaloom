// ── Node Descriptor types matching the Loom REST API ──────────────────────────

export type NodeCategory = "SOURCE" | "FILTER" | "ANALYSIS" | "TRANSFORM" | "OUTPUT";
export type NodeMode = "SEQUENTIAL" | "PARALLEL";
export type ParameterType = "STRING" | "INTEGER" | "BOOLEAN" | "FLOAT" | "ENUM" | "STRING_LIST";

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
  allowedValues?: string[];
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
