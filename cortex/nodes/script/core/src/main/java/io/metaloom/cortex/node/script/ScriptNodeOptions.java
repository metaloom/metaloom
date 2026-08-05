package io.metaloom.cortex.node.script;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.loom.nodes.spec.ParameterType;
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

	@ParamDoc(label = "Engine", description = "Script engine id. 'js' runs JavaScript on GraalJS", order = 40)
	private String engine = DEFAULT_ENGINE;

	// The field stays null on purpose: a node with no script must fail validation. The advertised
	// default is a runnable example, so the editor's form opens with something that works rather than
	// an empty box - which is exactly what @ParamDoc#defaultValue exists for.
	@ParamDoc(label = "Script", description = "The script body. Runs once per media item",
		type = ParameterType.CODE, language = "javascript", rows = 16, order = 50,
		defaultValue = "// Runs once per media item.\n//   media    - { path, absolutePath, size, sha512, isVideo, isImage, isAudio, isDocument }\n//   data     - the wired 'data' payload; wired 'text' arrives as data.text\n//   params   - the Parameters bag below\n//   out      - out.text/number/integer/bool/json/list/timeframes/image/path(key, value)\n//   log      - log.info/warn/error(msg)\n//   ctx      - ctx.skip(reason) / ctx.fail(reason)\nout.text('result', 'hello from ' + media.path);\n")
	private String script;

	/** Declared outputs as {@code [{"key": ..., "type": ...}]}; parsed by {@link ScriptOutputSpec#parse(JsonArray)}. */
	@ParamDoc(label = "Outputs", type = ParameterType.JSON, rows = 6, order = 60,
		description = "Declared outputs as [{\"key\": ..., \"type\": ...}]. Types: STRING, TEXT, "
			+ "INTEGER, NUMBER, BOOLEAN, JSON, TEXT_LIST, TIMEFRAMES, IMAGE, IMAGE_LIST, PATH. A "
			+ "TIMEFRAMES output may also set \"segmentType\": one of SCENE, SILENCE, SHOT or "
			+ "CHAPTER (default CHAPTER) - the database accepts no others",
		defaultValue = "[{\"key\": \"result\", \"type\": \"TEXT\"}]")
	private JsonArray outputs = new JsonArray();

	/** Free-form constants handed to the script as {@code params}. */
	@ParamDoc(label = "Parameters", description = "Constants handed to the script as 'params'",
		type = ParameterType.JSON, rows = 4, order = 70, defaultValue = "{}")
	private JsonObject params = new JsonObject();

	/**
	 * Default true. Scripts are authored by whoever may edit a pipeline, which is already
	 * permission to run code on a worker; pretending otherwise by defaulting to a sandbox would
	 * add friction without adding a trust boundary. Set false for defence in depth.
	 */
	@ParamDoc(label = "Trusted", order = 80,
		description = "Run with full worker privileges. Turn off to deny host access, class lookup, threads and IO")
	private boolean trusted = true;

	@ParamDoc(label = "Allow Network", description = "Expose the 'http' binding to the script", order = 90)
	private boolean allowNetwork = false;

	@ParamDoc(label = "Allow Filesystem", description = "Expose the read-only 'fs' binding to the script", order = 100)
	private boolean allowFilesystem = false;

	@ParamDoc(label = "Statement Limit", description = "Guest-statement budget; guards against tight loops",
		min = "1", order = 120)
	private long statementLimit = DEFAULT_STATEMENT_LIMIT;

	@ParamDoc(label = "Max Output Bytes", description = "Cap on the encoded output bag", min = "1", order = 130)
	private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;

	@ParamDoc(label = "Max Log Lines", description = "Cap on log.* calls per media item", min = "0", order = 140)
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
