package io.metaloom.loom.core.mcp;

import java.util.UUID;

import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.json.JsonObject;

/**
 * Shared helpers for the MCP authentication tests: minting JWTs, creating API-key tokens and building
 * the JSON-RPC payloads exercised across the different auth modes.
 */
public final class MCPAuthTestSupport {

	/** Tool that requires the {@code READ_ASSET} permission (see {@code SearchAssetsTool}). */
	public static final String READ_ASSET_TOOL = "search_assets";

	private MCPAuthTestSupport() {
	}

	/**
	 * Mint a JWT for the bootstrap admin user. The admin role is granted every permission during
	 * database initialization, so this token passes all tool permission checks.
	 */
	public static String adminJwt(LoomCoreComponent internal) {
		return internal.authService().generate(new JsonObject().put("uuid", TestValues.ADMIN_UUID.toString()));
	}

	/**
	 * Mint a valid (correctly signed) JWT for a random user uuid that has no permissions in the
	 * database. Authentication succeeds but every permission check fails.
	 */
	public static String unprivilegedJwt(LoomCoreComponent internal) {
		return internal.authService().generate(new JsonObject().put("uuid", UUID.randomUUID().toString()));
	}

	/**
	 * Create and persist an API-key token owned by the admin user and return its raw value (usable as
	 * the {@code X-API-Key} header).
	 */
	public static String createAdminApiKey(LoomCoreComponent internal, String value) {
		TokenDao tokenDao = internal.daos().tokenDao();
		Token token = tokenDao.createToken(TestValues.ADMIN_UUID, "mcp-auth-test", value);
		token.setUuid(UUID.randomUUID());
		tokenDao.store(token);
		return value;
	}

	/** Build a {@code tools/call} request for the READ_ASSET-guarded search tool. */
	public static JsonObject searchAssetsCall(int id) {
		return MCPTestClient.jsonRpc("tools/call", id, MCPTestClient.toolCall(READ_ASSET_TOOL, new JsonObject()));
	}

	/** Build a {@code tools/list} request. */
	public static JsonObject toolsListRequest(int id) {
		return MCPTestClient.jsonRpc("tools/list", id, new JsonObject());
	}
}
