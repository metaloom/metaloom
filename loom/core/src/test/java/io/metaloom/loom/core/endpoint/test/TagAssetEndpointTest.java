package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagReference;
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
			TagResponse tag = client.tagAsset(ASSET_UUID, request).sync().body();
			assertThat(tag).isValid();

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset).isValid();

			client.untagAsset(ASSET_UUID, tag.getUuid()).sync().body();

			AssetResponse asset2 = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset2).isValid();

			TagResponse tag2 = client.loadTag(tag.getUuid()).sync().body();
			assertThat(tag2).isValid();
		}
	}

	@Test
	public void testTagAssetWithRegion() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Region tag carrying both a spatial box and a temporal range.
			AreaInfo area = new AreaInfo()
				.setStartX(10)
				.setStartY(20)
				.setWidth(100)
				.setHeight(50)
				.setFrom(1500L)
				.setTo(4200L);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("region");
			request.setCollection("regions");
			request.setArea(area);
			TagResponse tag = client.tagAsset(ASSET_UUID, request).sync().body();
			assertThat(tag).isValid();

			// The region must round-trip through the response of the create call...
			assertRegion(tag.getArea());

			// ...and be persisted on the tag<->asset relationship, so it comes back on the asset's tag list.
			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset).isValid();
			TagReference reference = asset.getTags().stream()
				.filter(t -> t.getUuid().equals(tag.getUuid()))
				.findFirst()
				.orElse(null);
			org.assertj.core.api.Assertions.assertThat(reference).as("Region tag must be listed on the asset").isNotNull();
			assertRegion(reference.getArea());

			client.untagAsset(ASSET_UUID, tag.getUuid()).sync().body();

			// After untagging the region tag must no longer be present on the asset.
			AssetResponse untagged = client.loadAsset(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(untagged.getTags().stream().anyMatch(t -> t.getUuid().equals(tag.getUuid())))
				.as("Region tag must be removed after untagging").isFalse();
		}
	}

	private void assertRegion(AreaInfo area) {
		org.assertj.core.api.Assertions.assertThat(area).as("Tag area must be present").isNotNull();
		org.assertj.core.api.Assertions.assertThat(area.getStartX()).as("startX").isEqualTo(10);
		org.assertj.core.api.Assertions.assertThat(area.getStartY()).as("startY").isEqualTo(20);
		org.assertj.core.api.Assertions.assertThat(area.getWidth()).as("width").isEqualTo(100);
		org.assertj.core.api.Assertions.assertThat(area.getHeight()).as("height").isEqualTo(50);
		org.assertj.core.api.Assertions.assertThat(area.getFrom()).as("from").isEqualTo(1500L);
		org.assertj.core.api.Assertions.assertThat(area.getTo()).as("to").isEqualTo(4200L);
	}

}
