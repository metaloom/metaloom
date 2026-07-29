import type { Cardinality, NodeDescriptor, PortSpec } from "../../types/nodeDescriptors";

/**
 * TypeScript mirrors of the three Java `NodePortResolver`s
 * (`ScriptPortResolver`, `LlmPortResolver`, `VlmPortResolver` in `loom-shared/node-model`).
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
const RESOLVER_KINDS = new Set(["script", "llm", "vlm"]);

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
