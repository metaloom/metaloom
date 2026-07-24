package io.metaloom.loom.agent.sandbox.tool;

import io.vertx.core.json.JsonObject;

/**
 * Plain description of a coding tool the LLM can call. Kept free of any LLM/genai dependency so the
 * sandbox module stays self-contained; the chat module wraps these into its provider's tool type.
 *
 * @param name
 *            the tool name exposed to the model (e.g. {@code run_shell})
 * @param description
 *            human/LLM-facing description
 * @param inputSchema
 *            JSON Schema object describing the tool arguments
 */
public record CodingTool(String name, String description, JsonObject inputSchema) {
}
