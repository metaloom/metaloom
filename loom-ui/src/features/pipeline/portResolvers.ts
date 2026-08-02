import type { Cardinality, NodeDescriptor, PortSpec } from "../../types/nodeDescriptors";

/**
 * TypeScript mirrors of the four Java `NodePortResolver`s
 * (`ScriptPortResolver`, `LlmPortResolver`, `VlmPortResolver`, `FilterPortResolver` in
 * `loom-shared/node-model`).
 *
 * Some kinds only know their ports once configured, so the descriptor cannot state them. The
 * server resolver is authoritative at save time; these mirrors exist so the canvas can render the
 * handles the instant a parameter is edited, without a round trip. `portResolvers.test.ts` pins
 * them against the same cases `NodePortResolverTest` asserts on the Java side.
 *
 * This generalises what the editor used to do for `script` alone through `SCRIPT_VALUE_CONTENT_TYPE`,
 * which additionally collapsed the list types onto their scalar counterparts — `TEXT_LIST` looked
 * exactly like `TEXT` — so "I emit N of these" was invisible on the canvas.
 */

/** Port ids share the shape of Java's `PortSpec.ID_PATTERN`. */
const PORT_ID_PATTERN = /^[a-z0-9][a-z0-9_]{0,62}$/;

/** The kinds that carry a resolver. Mirrors the `ServiceLoader` registrations. */
const RESOLVER_KINDS = new Set(["script", "llm", "vlm", "filter"]);

/**
 * The `ScriptValueType` vocabulary, mapped onto content type plus cardinality.
 * Mirrors `ScriptPortResolver.ScriptOutputType`.
 */
export const SCRIPT_OUTPUT_TYPES: Record<string, { contentType: string; cardinality: Cardinality }> = {
  STRING:     { contentType: "scalar/string",   cardinality: "ONE" },
  TEXT:       { contentType: "text/plain",      cardinality: "ONE" },
  INTEGER:    { contentType: "scalar/integer",  cardinality: "ONE" },
  NUMBER:     { contentType: "scalar/number",   cardinality: "ONE" },
  BOOLEAN:    { contentType: "scalar/boolean",  cardinality: "ONE" },
  JSON:       { contentType: "struct/json",     cardinality: "ONE" },
  TEXT_LIST:  { contentType: "text/plain",      cardinality: "MANY" },
  TIMEFRAMES: { contentType: "struct/segments", cardinality: "ONE" },
  IMAGE:      { contentType: "artifact/image",  cardinality: "ONE" },
  IMAGE_LIST: { contentType: "artifact/image",  cardinality: "MANY" },
  PATH:       { contentType: "artifact/file",   cardinality: "ONE" },
};

/** Prefix of a per-prompt port id, matching what the llm/vlm nodes write at runtime. */
const PROMPT_PORT_PREFIX = "result_";

/** Port id used when no prompt is configured yet, so the node stays connectable. */
const PROMPT_FALLBACK_PORT = "result";

/**
 * Read an option that may already be parsed or may still be the raw JSON text the parameter editor
 * holds. `outputs` and `prompts` are `JSON` parameters, so both shapes reach us in practice.
 * Nothing throws: a half-typed value simply resolves to no ports.
 */
function parseOption(value: unknown): unknown {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function trimmedString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

/** One port per declared script output. Mirrors `ScriptPortResolver.resolveOutputPorts`. */
function resolveScriptOutputPorts(options: Record<string, unknown>): PortSpec[] {
  const declared = parseOption(options.outputs);
  if (!Array.isArray(declared)) return [];

  const ports: PortSpec[] = [];
  const seen = new Set<string>();
  for (const entry of declared) {
    if (!entry || typeof entry !== "object") continue;
    const key = trimmedString((entry as { key?: unknown }).key);
    const typeName = trimmedString((entry as { type?: unknown }).type).toUpperCase();
    if (!key || !PORT_ID_PATTERN.test(key) || seen.has(key)) continue;
    const mapped = SCRIPT_OUTPUT_TYPES[typeName];
    if (!mapped) continue;
    seen.add(key);
    ports.push({
      id: key,
      label: key,
      contentType: mapped.contentType,
      cardinality: mapped.cardinality,
      required: true,
      description: `Declared script output of type ${typeName}`,
    });
  }
  return ports;
}

/**
 * One `result_<promptId>` port per configured prompt, falling back to a single `result`.
 * Mirrors `PromptPortResolver.resolveOutputPorts`, shared by `llm` and `vlm`.
 */
function resolvePromptOutputPorts(options: Record<string, unknown>, modelLabel: string): PortSpec[] {
  const prompts = parseOption(options.prompts);
  const ports: PortSpec[] = [];

  if (prompts && typeof prompts === "object" && !Array.isArray(prompts)) {
    for (const raw of Object.keys(prompts as Record<string, unknown>)) {
      const promptId = raw.trim();
      // A blank id would produce the port "result_", which is well-formed but means nothing.
      if (!promptId) continue;
      const id = PROMPT_PORT_PREFIX + promptId;
      if (!PORT_ID_PATTERN.test(id)) continue;
      ports.push({
        id,
        label: promptId,
        contentType: "text/plain",
        cardinality: "ONE",
        required: true,
        description: `What ${modelLabel} answered for the '${promptId}' prompt`,
      });
    }
  }

  if (ports.length === 0) {
    // No prompts configured (or the option was malformed): still offer one handle, otherwise the
    // node could not be wired up at all and the author would have no way back.
    ports.push({
      id: PROMPT_FALLBACK_PORT,
      label: "Result",
      contentType: "text/plain",
      cardinality: "ONE",
      required: true,
      description: `What ${modelLabel} answered. Configure prompts to get one port per prompt`,
    });
  }
  return ports;
}

/** The filter node's fixed ports, and the ids a bucket may therefore not claim. */
export const FILTER_PORT_OTHER = "other";
export const FILTER_PORT_PASSED = "passed";
export const FILTER_PORT_BUCKET = "bucket";
export const FILTER_RESERVED_BUCKET_IDS = new Set([
  FILTER_PORT_OTHER,
  FILTER_PORT_PASSED,
  FILTER_PORT_BUCKET,
  "media",
  "text",
]);

/**
 * One selective port per configured bucket, then the three fixed ports.
 * Mirrors `FilterPortResolver.resolveOutputPorts`.
 *
 * The bucket ports and `other` are `selective`: the item goes down exactly one of them, and the
 * engine skips whatever is wired to the rest. `passed` and `bucket` carry a value for *every* item,
 * so a node wired to those runs regardless of which branch was taken — that is the escape hatch for
 * "I want the decision, not the item".
 */
function resolveFilterOutputPorts(options: Record<string, unknown>): PortSpec[] {
  const declared = parseOption(options.buckets);
  const ports: PortSpec[] = [];
  const seen = new Set<string>();

  if (Array.isArray(declared)) {
    for (const entry of declared) {
      if (!entry || typeof entry !== "object") continue;
      const id = trimmedString((entry as { id?: unknown }).id);
      // A blank id is a row someone has just added and not finished; skipping it rather than
      // failing is what lets handles follow typing without the others flickering away.
      if (!id || !PORT_ID_PATTERN.test(id) || FILTER_RESERVED_BUCKET_IDS.has(id) || seen.has(id)) continue;
      seen.add(id);
      const label = trimmedString((entry as { label?: unknown }).label) || id;
      ports.push({
        id,
        label,
        contentType: "media/*",
        cardinality: "ONE",
        required: true,
        selective: true,
        description: `Items classified as '${label}'`,
      });
    }
  }

  ports.push({
    id: FILTER_PORT_OTHER,
    label: "Other",
    contentType: "media/*",
    cardinality: "ONE",
    required: true,
    selective: true,
    description: "Items no configured bucket matched",
  });
  ports.push({
    id: FILTER_PORT_PASSED,
    label: "Passed",
    contentType: "control/filter",
    cardinality: "ONE",
    required: true,
    description: "True when a bucket other than 'other' matched",
  });
  ports.push({
    id: FILTER_PORT_BUCKET,
    label: "Bucket",
    contentType: "scalar/string",
    cardinality: "ONE",
    required: true,
    description: "The id of the bucket this item landed in. Carries a value for every item",
  });
  return ports;
}

/** Whether a dynamic-port mirror exists for this kind. */
export function hasPortResolver(kind: string | undefined): boolean {
  return !!kind && RESOLVER_KINDS.has(kind);
}

/**
 * The output ports a node actually has: the resolver's for a dynamic kind, the descriptor's
 * otherwise.
 */
export function resolveOutputPorts(desc: NodeDescriptor | undefined, options: Record<string, unknown>): PortSpec[] {
  if (!desc) return [];
  // `dynamicPorts !== false` rather than `=== true` so a descriptor fixture that predates the flag
  // still gets its handles resolved instead of silently rendering none.
  if (hasPortResolver(desc.kind) && desc.dynamicPorts !== false) {
    switch (desc.kind) {
      case "script": return resolveScriptOutputPorts(options);
      case "llm":    return resolvePromptOutputPorts(options, "the language model");
      case "vlm":    return resolvePromptOutputPorts(options, "the vision-language model");
      case "filter": return resolveFilterOutputPorts(options);
    }
  }
  return desc.outputPorts ?? [];
}

/**
 * The input ports a node actually has. No resolver overrides inputs today — this mirrors the
 * default `NodePortResolver.resolveInputPorts`, and exists so the call site reads symmetrically.
 */
export function resolveInputPorts(desc: NodeDescriptor | undefined, _options: Record<string, unknown>): PortSpec[] {
  return desc?.inputPorts ?? [];
}
