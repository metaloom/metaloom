package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.core.mcp.MCPTestClient.HttpResult;
import io.vertx.core.json.JsonObject;

/**
 * MCP authentication behavior when auth is <strong>disabled</strong> (the default,
 * {@code LOOM_MCP_AUTH_ENABLED=false}).
 *
 * <p>In this mode the transports never invoke the authentication handler, no {@code User} is
 * associated with a request, and {@code MCPToolRegistry.dispatch} therefore skips all permission
 * checks - every tool is reachable without credentials.</p>
 */
public class MCPAuthDisabledTest {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	@Test
	public void testUnauthenticatedToolCallSucceeds() throws Exception {
		MCPTestClient client = new MCPTestClient(loom.internal());

		// A permissioned tool (search_assets requires READ_ASSET) succeeds without any credentials
		// because auth is disabled and no permission check is performed.
		HttpResult result = client.postMessage(MCPAuthTestSupport.searchAssetsCall(1));

		assertEquals(200, result.statusCode(), "Message endpoint must accept unauthenticated requests when auth is disabled");
		JsonObject response = result.json();
		assertEquals("2.0", response.getString("jsonrpc"));
		assertNull(response.getJsonObject("error"), "No permission error is expected when auth is disabled");
		assertNotNull(response.getJsonObject("result"), "Tool call should return a result");
	}
}
