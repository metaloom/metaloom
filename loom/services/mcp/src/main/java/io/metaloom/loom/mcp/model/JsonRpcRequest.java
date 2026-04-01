package io.metaloom.loom.mcp.model;

import io.vertx.core.json.JsonObject;

/**
 * Represents a JSON-RPC 2.0 request message used by MCP.
 */
public class JsonRpcRequest {

	private String jsonrpc;
	private String method;
	private Object id;
	private JsonObject params;

	public JsonRpcRequest() {
	}

	public JsonRpcRequest(String method, Object id, JsonObject params) {
		this.jsonrpc = "2.0";
		this.method = method;
		this.id = id;
		this.params = params;
	}

	public String getJsonrpc() {
		return jsonrpc;
	}

	public JsonRpcRequest setJsonrpc(String jsonrpc) {
		this.jsonrpc = jsonrpc;
		return this;
	}

	public String getMethod() {
		return method;
	}

	public JsonRpcRequest setMethod(String method) {
		this.method = method;
		return this;
	}

	public Object getId() {
		return id;
	}

	public JsonRpcRequest setId(Object id) {
		this.id = id;
		return this;
	}

	public JsonObject getParams() {
		return params;
	}

	public JsonRpcRequest setParams(JsonObject params) {
		this.params = params;
		return this;
	}

	/**
	 * @return true if this is a notification (no id field).
	 */
	public boolean isNotification() {
		return id == null;
	}

	public static JsonRpcRequest fromJson(JsonObject json) {
		JsonRpcRequest req = new JsonRpcRequest();
		req.setJsonrpc(json.getString("jsonrpc"));
		req.setMethod(json.getString("method"));
		req.setId(json.getValue("id"));
		req.setParams(json.getJsonObject("params"));
		return req;
	}

}
