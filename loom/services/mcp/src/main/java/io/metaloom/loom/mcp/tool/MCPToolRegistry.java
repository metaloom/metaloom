package io.metaloom.loom.mcp.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.mcp.MCPConstants;
import io.metaloom.loom.mcp.dagger.MCPTools;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;

/**
 * Central registry for all MCP tools.
 *
 * <p>On startup each {@link MCPTool} is registered both in a local map (for fast
 * descriptor listing) and on the <strong>Vert.x EventBus</strong> at address
 * {@code mcp.tool.<toolName>}. This allows the JSON-RPC handler to dispatch
 * tool calls via the EventBus, which provides:</p>
 * <ul>
 *   <li>Decoupled invocation — tools can live in different verticles or even
 *       clustered nodes.</li>
 *   <li>Back-pressure via Vert.x message passing.</li>
 *   <li>Future extensibility for WebSocket-bridged tool calls from external
 *       processes.</li>
 * </ul>
 */
@Singleton
public class MCPToolRegistry {

	private static final Logger log = LoggerFactory.getLogger(MCPToolRegistry.class);

	private final Vertx vertx;
	private final Map<String, MCPTool> tools = new ConcurrentHashMap<>();
	private final Map<String, MessageConsumer<JsonObject>> consumers = new ConcurrentHashMap<>();

	@Inject
	public MCPToolRegistry(Vertx vertx, @MCPTools Set<MCPTool> injectedTools) {
		this.vertx = vertx;
		for (MCPTool tool : injectedTools) {
			register(tool);
		}
	}

	/**
	 * Register a tool and bind it to the EventBus.
	 */
	public void register(MCPTool tool) {
		String name = tool.descriptor().name();
		if (tools.containsKey(name)) {
			log.warn("Replacing existing MCP tool registration: {}", name);
			unregister(name);
		}

		tools.put(name, tool);

		// Register on EventBus so tool calls can be dispatched internally
		String address = MCPConstants.EVENTBUS_TOOL_PREFIX + name;
		MessageConsumer<JsonObject> consumer = vertx.eventBus().<JsonObject>consumer(address, msg -> {
			JsonObject arguments = msg.body();
			tool.execute(arguments)
				.onSuccess(result -> msg.reply(result))
				.onFailure(err -> {
					log.error("Tool {} execution failed", name, err);
					msg.fail(MCPConstants.ERR_INTERNAL, err.getMessage());
				});
		});
		consumers.put(name, consumer);
		log.info("Registered MCP tool: {} at EventBus address: {}", name, address);
	}

	/**
	 * Unregister a tool and remove its EventBus consumer.
	 */
	public void unregister(String name) {
		tools.remove(name);
		MessageConsumer<JsonObject> consumer = consumers.remove(name);
		if (consumer != null) {
			consumer.unregister();
		}
	}

	/**
	 * Dispatch a tool call via the Vert.x EventBus.
	 *
	 * @param toolName  The tool to invoke.
	 * @param arguments The arguments JSON.
	 * @return A future with the tool result.
	 */
	public Future<JsonObject> dispatch(String toolName, JsonObject arguments) {
		if (!tools.containsKey(toolName)) {
			return Future.failedFuture("Unknown tool: " + toolName);
		}
		String address = MCPConstants.EVENTBUS_TOOL_PREFIX + toolName;
		return vertx.eventBus().<JsonObject>request(address, arguments)
			.map(msg -> msg.body());
	}

	/**
	 * Get the tool descriptor by name.
	 */
	public MCPToolDescriptor getDescriptor(String name) {
		MCPTool tool = tools.get(name);
		return tool != null ? tool.descriptor() : null;
	}

	/**
	 * Return all registered tool descriptors.
	 */
	public Collection<MCPToolDescriptor> listDescriptors() {
		return tools.values().stream()
			.map(MCPTool::descriptor)
			.toList();
	}

	/**
	 * Return all registered tool names.
	 */
	public Collection<String> listToolNames() {
		return Collections.unmodifiableSet(tools.keySet());
	}

	/**
	 * Unregister all tools and their EventBus consumers.
	 */
	public void unregisterAll() {
		for (String name : tools.keySet()) {
			unregister(name);
		}
	}

}
