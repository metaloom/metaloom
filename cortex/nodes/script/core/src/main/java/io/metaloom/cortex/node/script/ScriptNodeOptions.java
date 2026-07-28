package io.metaloom.cortex.node.script;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Options for the {@link ScriptNode}.
 *
 * <p>
 * Unlike every other node's options, these are meant to be set <em>per pipeline node instance</em>
 * rather than in the worker's YAML: two script nodes in one graph run two different scripts. The
 * YAML block under the {@code script} key still supplies defaults (useful for fleet-wide limits),
 * and {@link ScriptNode#configure(JsonObject)} layers the pipeline definition over them.
 * </p>
 */
public class ScriptNodeOptions extends AbstractNodeOptions<ScriptNodeOptions> {

	public static final String KEY = "script";

	public static final String DEFAULT_ENGINE = "js";
	public static final long DEFAULT_TIMEOUT_MS = 10_000L;
	public static final long DEFAULT_STATEMENT_LIMIT = 10_000_000L;
	public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;
	public static final int DEFAULT_MAX_LOG_LINES = 200;

	private String engine = DEFAULT_ENGINE;

	private String script;

	/** Declared outputs as {@code [{"key": ..., "type": ...}]}; parsed by {@link ScriptOutputSpec#parse(JsonArray)}. */
	private JsonArray outputs = new JsonArray();

	/** Free-form constants handed to the script as {@code params}. */
	private JsonObject params = new JsonObject();

	/** Ordered {@code nodeId:outputKey} entries that must all be present, or the node skips. */
	private List<String> requiredInputs = new ArrayList<>();

	/**
	 * Default true. Scripts are authored by whoever may edit a pipeline, which is already
	 * permission to run code on a worker; pretending otherwise by defaulting to a sandbox would
	 * add friction without adding a trust boundary. Set false for defence in depth.
	 */
	private boolean trusted = true;

	private boolean allowNetwork = false;

	private boolean allowFilesystem = false;

	private long statementLimit = DEFAULT_STATEMENT_LIMIT;

	private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;

	private int maxLogLines = DEFAULT_MAX_LOG_LINES;

	public ScriptNodeOptions() {
		// AbstractNodeOptions defaults timeoutMs to 0, which the rest of the system reads as
		// "no timeout". A script node always has one - an unbounded script holds a worker slot
		// forever - so it starts from a real budget instead.
		setTimeoutMs(DEFAULT_TIMEOUT_MS);
	}

	@Override
	protected ScriptNodeOptions self() {
		return this;
	}

	public String getEngine() {
		return engine;
	}

	public ScriptNodeOptions setEngine(String engine) {
		this.engine = engine;
		return this;
	}

	public String getScript() {
		return script;
	}

	public ScriptNodeOptions setScript(String script) {
		this.script = script;
		return this;
	}

	public JsonArray getOutputs() {
		return outputs;
	}

	public ScriptNodeOptions setOutputs(JsonArray outputs) {
		this.outputs = outputs;
		return this;
	}

	public JsonObject getParams() {
		return params;
	}

	public ScriptNodeOptions setParams(JsonObject params) {
		this.params = params;
		return this;
	}

	public List<String> getRequiredInputs() {
		return requiredInputs;
	}

	public ScriptNodeOptions setRequiredInputs(List<String> requiredInputs) {
		this.requiredInputs = requiredInputs;
		return this;
	}

	public boolean isTrusted() {
		return trusted;
	}

	public ScriptNodeOptions setTrusted(boolean trusted) {
		this.trusted = trusted;
		return this;
	}

	public boolean isAllowNetwork() {
		return allowNetwork;
	}

	public ScriptNodeOptions setAllowNetwork(boolean allowNetwork) {
		this.allowNetwork = allowNetwork;
		return this;
	}

	public boolean isAllowFilesystem() {
		return allowFilesystem;
	}

	public ScriptNodeOptions setAllowFilesystem(boolean allowFilesystem) {
		this.allowFilesystem = allowFilesystem;
		return this;
	}

	public long getStatementLimit() {
		return statementLimit;
	}

	public ScriptNodeOptions setStatementLimit(long statementLimit) {
		this.statementLimit = statementLimit;
		return this;
	}

	public int getMaxOutputBytes() {
		return maxOutputBytes;
	}

	public ScriptNodeOptions setMaxOutputBytes(int maxOutputBytes) {
		this.maxOutputBytes = maxOutputBytes;
		return this;
	}

	public int getMaxLogLines() {
		return maxLogLines;
	}

	public ScriptNodeOptions setMaxLogLines(int maxLogLines) {
		this.maxLogLines = maxLogLines;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (engine == null || engine.isBlank()) {
			errors.add("engine must not be empty");
		}
		if (script == null || script.isBlank()) {
			errors.add("script must not be empty");
		}
		if (outputs == null || outputs.isEmpty()) {
			errors.add("outputs must declare at least one entry of the form {\"key\": ..., \"type\": ...}");
		} else {
			try {
				ScriptOutputSpec.parse(outputs);
			} catch (IllegalArgumentException e) {
				errors.add("outputs: " + e.getMessage());
			}
		}
		if (requiredInputs != null) {
			for (String input : requiredInputs) {
				if (input == null || !input.contains(":") || input.startsWith(":") || input.endsWith(":")) {
					errors.add("requiredInputs entry must have the form 'nodeId:outputKey', got '" + input + "'");
				}
			}
		}
		// Stricter than the inherited non-negative rule: 0 means "no timeout" elsewhere, and this
		// node must never run without one.
		if (getTimeoutMs() <= 0) {
			errors.add("timeoutMs must be positive, got " + getTimeoutMs());
		}
		if (statementLimit <= 0) {
			errors.add("statementLimit must be positive, got " + statementLimit);
		}
		if (maxOutputBytes <= 0) {
			errors.add("maxOutputBytes must be positive, got " + maxOutputBytes);
		}
		if (maxLogLines < 0) {
			errors.add("maxLogLines must not be negative, got " + maxLogLines);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
