package io.metaloom.loom.core.endpoint;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public abstract class AbstractEndpointTest implements EndpointTest {

	@RegisterExtension
	protected LoomCoreTestExtension loom = new LoomCoreTestExtension();

	@Override
	public LoomHttpClient httpClient() {
		return loom.httpClient();
	}

	public void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync();
		client.setToken(loginResponse.getToken());
	}

	public String generateJWT() {
		JsonArray claims = new JsonArray();
		claims.add(Permission.CREATE_ASSET.name());
		JsonObject userAttr = new JsonObject().put("claim", claims);
		return loom.internal().authService().generate(userAttr);
	}

	protected void expect(int statusCode, String statusMsg, LoomClientRequest<?> request) {
		try {
			request.sync();
			fail("The request should have failed with code " + statusCode + " and msg " + statusMsg + " but it succeeded.");
		} catch (LoomClientException e) {
			assertEquals(statusCode, e.getStatusCode(), "The status code did not match");
			assertEquals(statusMsg, e.getStatusMsg(), "The status message did not match up");
			// TODO assert body
		}

	}
}
