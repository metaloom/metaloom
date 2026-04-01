package io.metaloom.loom.core.grpc.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class AssetClientTest extends AbstractEndpointTest {

	@Test
	public void testAssetLoad() {
		try (LoomClient client = loom.httpClient()) {
			// client.setToken(generateJWT());
			AssetResponse response;
			response = client.loadAsset(SHA512SUM).sync();
			assertNotNull(response);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
