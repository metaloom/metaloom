package io.metaloom.loom.mcp.model;

import io.vertx.core.json.JsonObject;

/**
 * Represents a JSON-RPC 2.0 response message.
 */
public class JsonRpcResponse {

	private final String jsonrpc = "2.0";
	private Object id;
	private Object result;
	private JsonRpcError error;

	public JsonRpcResponse() {
	}

	public static JsonRpcResponse success(Object id, Object result) {
		JsonRpcResponse resp = new JsonRpcResponse();
		resp.id = id;
		resp.result = result;
		return resp;
	}

	public static JsonRpcResponse error(Object id, int code, String message) {
		return error(id, code, message, null);
	}

	public static JsonRpcResponse error(Object id, int code, String message, Object data) {
		JsonRpcResponse resp = new JsonRpcResponse();
		resp.id = id;
		resp.error = new JsonRpcError(code, message, data);
		return resp;
	}

	public String getJsonrpc() {
		return jsonrpc;
	}

	public Object getId() {
		return id;
	}

	public Object getResult() {
		return result;
	}

	public JsonRpcError getError() {
		return error;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.put("jsonrpc", jsonrpc);
		json.put("id", id);
		if (error != null) {
			JsonObject errObj = new JsonObject()
				.put("code", error.code())
				.put("message", error.message());
			if (error.data() != null) {
				errObj.put("data", error.data());
			}
			json.put("error", errObj);
		} else {
			if (result instanceof JsonObject resultObj) {
				json.put("result", resultObj);
			} else {
				json.put("result", JsonObject.mapFrom(result));
			}
		}
		return json;
	}

	public record JsonRpcError(int code, String message, Object data) {
	}

}
