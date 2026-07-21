package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.core.mcp.MCPTestClient.HttpResult;
import io.metaloom.loom.core.mcp.MCPTestClient.SseResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP authentication behavior with auth <strong>enabled in lenient mode</strong>
 * ({@code mcpAuthEnabled=true}, {@code mcpAuthStrictMode=false}).
 *
 * <p>Lenient mode still validates supplied credentials and enforces per-tool permissions for
 * authenticated users, but accepts requests that carry no credentials at all (logging a warning). The
 * allowed-origins default ({@code *}) is left in place.</p>
 */
public class MCPAuthLenientTest {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension()
		.withOptions(o -> o.getAuth().setMcpAuthEnabled(true));

	private LoomCoreComponent internal() {
		return loom.internal();
	}

	@Test
	public void testValidJwtToolCallSucceeds() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		HttpResult result = client.postMessageWithBearer(MCPAuthTestSupport.searchAssetsCall(1), jwt);

		assertEquals(200, result.statusCode());
		JsonObject response = result.json();
		assertNull(response.getJsonObject("error"), "Admin holds READ_ASSET, so no permission error is expected");
		assertNotNull(response.getJsonObject("result"), "Tool call should return a result for an authorized user");
	}

	@Test
	public void testUnprivilegedJwtDeniedWithStructuredError() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.unprivilegedJwt(internal());

		HttpResult result = client.postMessageWithBearer(MCPAuthTestSupport.searchAssetsCall(2), jwt);

		// The request is authenticated (200) but the user lacks READ_ASSET, so the tool call is
		// rejected with a structured JSON-RPC error rather than a generic transport error.
		assertEquals(200, result.statusCode());
		JsonObject response = result.json();
		JsonObject error = response.getJsonObject("error");
		assertNotNull(error, "Expected a JSON-RPC error for a permission denial");
		assertNotNull(error.getInteger("code"), "JSON-RPC error must carry a numeric code");
		assertTrue(error.getString("message", "").contains("Missing required permissions"),
			"Error message should describe the missing permission, was: " + error.getString("message"));
	}

	@Test
	public void testMissingCredentialsAcceptedInLenientMode() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());

		// No credentials -> lenient mode accepts the request. Because no User is resolved,
		// MCPToolRegistry.dispatch skips the permission check entirely and the tool runs. This test
		// pins that current lenient-mode behavior (an uncredentialed caller bypasses requiredPermissions).
		HttpResult result = client.postMessage(MCPAuthTestSupport.searchAssetsCall(3));

		assertEquals(200, result.statusCode());
		JsonObject response = result.json();
		assertNull(response.getJsonObject("error"), "Lenient mode without credentials should not raise a permission error");
		assertNotNull(response.getJsonObject("result"));
	}

	@Test
	public void testApiKeyToolCallSucceeds() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String apiKey = MCPAuthTestSupport.createAdminApiKey(internal(), "mcp-lenient-api-key");

		HttpResult result = client.postMessageWithApiKey(MCPAuthTestSupport.searchAssetsCall(4), apiKey);

		// The X-API-Key resolves (via TokenDao.findByToken) to its owning user - the admin, who holds
		// READ_ASSET - so the permissioned tool call succeeds.
		assertEquals(200, result.statusCode(), "A known API key must be accepted at the transport layer");
		JsonObject response = result.json();
		assertNull(response.getJsonObject("error"), "The admin-owned API key holds READ_ASSET, so no permission error is expected");
		assertNotNull(response.getJsonObject("result"), "Tool call should return a result for an API-key authenticated owner");
	}

	@Test
	public void testToolsListExposesRequiredPermissions() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		HttpResult result = client.postMessageWithBearer(MCPAuthTestSupport.toolsListRequest(5), jwt);

		assertEquals(200, result.statusCode());
		JsonArray tools = result.json().getJsonObject("result").getJsonArray("tools");
		assertNotNull(tools);
		JsonObject searchTool = null;
		for (int i = 0; i < tools.size(); i++) {
			JsonObject tool = tools.getJsonObject(i);
			if (MCPAuthTestSupport.READ_ASSET_TOOL.equals(tool.getString("name"))) {
				searchTool = tool;
				break;
			}
		}
		assertNotNull(searchTool, "Expected the " + MCPAuthTestSupport.READ_ASSET_TOOL + " tool to be listed");
		JsonArray perms = searchTool.getJsonArray("requiredPermissions");
		assertNotNull(perms, "Tool descriptor must expose requiredPermissions");
		assertTrue(perms.contains("READ_ASSET"), "search_assets must declare the READ_ASSET permission");
	}

	@Test
	public void testSseAcceptsValidTokenViaQueryParam() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		SseResult result = client.openSseWithToken(jwt);

		assertEquals(200, result.statusCode(), "SSE must accept a valid ?token= credential");
		assertNotNull(result.firstEvent());
		assertTrue(result.firstEvent().contains("endpoint"), "First SSE event should be the endpoint event");
	}

	@Test
	public void testSseCorsEchoesOriginUnderWildcard() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		// With the default allowed-origins of "*", any Origin is considered allowed and echoed back
		// specifically alongside Access-Control-Allow-Credentials.
		SseResult result = client.openSseWithOrigin(jwt, "https://client.example.com");

		assertEquals(200, result.statusCode());
		assertEquals("https://client.example.com", result.allowOrigin());
		assertEquals("true", result.allowCredentials());
	}
}
