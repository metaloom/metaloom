package io.metaloom.loom.mcp;

/**
 * Constants for the MCP (Model Context Protocol) server.
 */
public final class MCPConstants {

	private MCPConstants() {
	}

	/** JSON-RPC version used by MCP. */
	public static final String JSONRPC_VERSION = "2.0";

	/** MCP protocol version supported by this server. */
	public static final String MCP_PROTOCOL_VERSION = "2025-03-26";

	/** EventBus address prefix for MCP tool dispatch. */
	public static final String EVENTBUS_TOOL_PREFIX = "mcp.tool.";

	/** EventBus address for tool registration notifications. */
	public static final String EVENTBUS_TOOL_REGISTRY = "mcp.registry";

	/** SSE event type for JSON-RPC messages. */
	public static final String SSE_EVENT_MESSAGE = "message";

	/** SSE endpoint path. */
	public static final String SSE_PATH = "/mcp/sse";

	/** JSON-RPC message endpoint path. */
	public static final String MESSAGE_PATH = "/mcp/message";

	// JSON-RPC method names defined by MCP
	public static final String METHOD_INITIALIZE = "initialize";
	public static final String METHOD_INITIALIZED = "notifications/initialized";
	public static final String METHOD_TOOLS_LIST = "tools/list";
	public static final String METHOD_TOOLS_CALL = "tools/call";
	public static final String METHOD_RESOURCES_LIST = "resources/list";
	public static final String METHOD_RESOURCES_READ = "resources/read";
	public static final String METHOD_PING = "ping";

	// JSON-RPC error codes
	public static final int ERR_PARSE_ERROR = -32700;
	public static final int ERR_INVALID_REQUEST = -32600;
	public static final int ERR_METHOD_NOT_FOUND = -32601;
	public static final int ERR_INVALID_PARAMS = -32602;
	public static final int ERR_INTERNAL = -32603;

}
