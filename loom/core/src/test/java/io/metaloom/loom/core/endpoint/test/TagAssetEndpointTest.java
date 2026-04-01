package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.vertx.core.json.JsonObject;

public class TagAssetEndpointTest extends AbstractEndpointTest {

	@Test
	public void testTagAsset() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("red2");
			request.setCollection("colors");
			request.setMeta(new JsonObject().put("hello", "world"));
			TagResponse tag = client.tagAsset(ASSET_UUID, request).sync();
			assertThat(tag).isValid();

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync();
			assertThat(asset).isValid();

			client.untagAsset(ASSET_UUID, tag.getUuid()).sync();

			AssetResponse asset2 = client.loadAsset(ASSET_UUID).sync();
			assertThat(asset2).isValid();

			TagResponse tag2 = client.loadTag(tag.getUuid()).sync();
			assertThat(tag2).isValid();
		}
	}

}
