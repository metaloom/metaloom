package io.metaloom.loom.agent.sandbox.tool;

import java.util.List;
import java.util.Set;

import io.metaloom.loom.agent.sandbox.SandboxClient;
import io.metaloom.loom.agent.sandbox.error.SandboxException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The coding tool catalogue plus the dispatch that maps a tool call onto a {@link SandboxClient} route.
 *
 * <p>The four tools mirror the apa-agent / pi coding tools: {@code run_shell}, {@code write_file},
 * {@code read_file} and {@code list_files}. Everything runs inside the per-session Session Runner,
 * confined to its {@code /workspace}.</p>
 */
public final class CodingTools {

	private CodingTools() {
	}

	public static final String RUN_SHELL = "run_shell";
	public static final String WRITE_FILE = "write_file";
	public static final String READ_FILE = "read_file";
	public static final String LIST_FILES = "list_files";

	public static final Set<String> NAMES = Set.of(RUN_SHELL, WRITE_FILE, READ_FILE, LIST_FILES);

	/**
	 * The tool definitions to advertise to the model. Only offered when the sandbox is enabled.
	 */
	public static List<CodingTool> definitions() {
		return List.of(
			new CodingTool(RUN_SHELL,
				"Run a shell command (bash) inside the isolated coding sandbox and return its combined "
					+ "stdout/stderr and exit code. Use for running python, installing packages, running tests, etc. "
					+ "The working directory is the persistent /workspace.",
				object(
					prop("command", "string", "The shell command to run.", true),
					prop("timeout", "integer", "Optional wall-clock timeout in seconds.", false))),
			new CodingTool(WRITE_FILE,
				"Create or overwrite a file in the sandbox workspace with the given text content.",
				object(
					prop("path", "string", "Workspace-relative file path.", true),
					prop("content", "string", "Full text content to write.", true))),
			new CodingTool(READ_FILE,
				"Read a text file from the sandbox workspace (optionally a line window).",
				object(
					prop("path", "string", "Workspace-relative file path.", true),
					prop("offset", "integer", "Optional 0-based start line.", false),
					prop("limit", "integer", "Optional maximum number of lines to return.", false))),
			new CodingTool(LIST_FILES,
				"List entries of a directory in the sandbox workspace.",
				object(prop("path", "string", "Workspace-relative directory (defaults to '.').", false))));
	}

	/**
	 * Execute one coding tool call against the given runner and return a JSON-serialisable result.
	 *
	 * @param client
	 *            the runner client for this session
	 * @param name
	 *            the tool name (must be one of {@link #NAMES})
	 * @param args
	 *            the tool arguments
	 * @param defaultExecTimeoutSeconds
	 *            fallback timeout for {@code run_shell} when the model does not specify one
	 */
	public static JsonObject dispatch(SandboxClient client, String name, JsonObject args, int defaultExecTimeoutSeconds) {
		JsonObject a = args != null ? args : new JsonObject();
		switch (name) {
			case RUN_SHELL: {
				String command = a.getString("command", "").strip();
				int timeout = a.getInteger("timeout", defaultExecTimeoutSeconds);
				return client.exec(command, timeout);
			}
			case WRITE_FILE:
				return client.writeFile(a.getString("path", ""), a.getString("content", ""));
			case READ_FILE:
				return client.readFile(a.getString("path", ""), a.getInteger("offset", 0), a.getInteger("limit"));
			case LIST_FILES:
				return client.listFiles(a.getString("path", "."));
			default:
				throw new SandboxException("unknown coding tool: " + name);
		}
	}

	// -- tiny JSON Schema builders ------------------------------------------
	private static JsonObject object(JsonObject... props) {
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		for (JsonObject p : props) {
			String pname = p.getString("__name");
			boolean req = p.getBoolean("__required", false);
			JsonObject copy = p.copy();
			copy.remove("__name");
			copy.remove("__required");
			properties.put(pname, copy);
			if (req) {
				required.add(pname);
			}
		}
		JsonObject schema = new JsonObject().put("type", "object").put("properties", properties);
		if (!required.isEmpty()) {
			schema.put("required", required);
		}
		return schema;
	}

	private static JsonObject prop(String name, String type, String description, boolean required) {
		return new JsonObject().put("__name", name).put("__required", required).put("type", type).put("description", description);
	}
}
