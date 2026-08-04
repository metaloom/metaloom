package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.utils.hash.SHA512;
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

	/**
	 * Tags are global: <code>tag</code> is <code>UNIQUE (name, collection)</code>. Tagging a second asset with a name that is already in use must
	 * therefore attach the tag that exists rather than insert a second row - without this, no caller can tag two assets alike, which is the ordinary
	 * case for a human tagging a selection and the entire premise of an auto-tagging node.
	 */
	@Test
	public void testTagTwoAssetsWithOneTagName() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("shared-tag");
			request.setCollection("quality");

			TagResponse first = client.tagAsset(ASSET_UUID, request).sync().body();
			assertThat(first).isValid();

			UUID secondAsset = createAsset(client, "b1", "tag-b1.png");
			TagResponse second = client.tagAsset(secondAsset, request).sync().body();
			assertThat(second).isValid();

			org.assertj.core.api.Assertions.assertThat(second.getUuid())
				.as("Both assets must carry the same tag row").isEqualTo(first.getUuid());

			// Both assets list it, and the tag itself is still a single object.
			assertTagged(client, ASSET_UUID, first.getUuid());
			assertTagged(client, secondAsset, first.getUuid());
			org.assertj.core.api.Assertions.assertThat(client.listTags().sync().body().getData().stream()
				.filter(t -> "shared-tag".equals(t.getName()) && "quality".equals(t.getCollection()))
				.count())
				.as("Only one tag may exist for one (name, collection)").isEqualTo(1);
		}
	}

	/**
	 * The join is keyed <code>(tag_uuid, asset_uuid)</code>, so re-tagging an asset with the same tag - a pipeline running a second time - must be a
	 * no-op rather than a constraint violation.
	 */
	@Test
	public void testTagSameAssetTwice() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("repeat-tag");
			request.setCollection("quality");

			TagResponse first = client.tagAsset(ASSET_UUID, request).sync().body();
			TagResponse again = client.tagAsset(ASSET_UUID, request).sync().body();
			org.assertj.core.api.Assertions.assertThat(again.getUuid()).isEqualTo(first.getUuid());

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(asset.getTags().stream()
				.filter(t -> t.getUuid().equals(first.getUuid()))
				.count())
				.as("The tag must be attached exactly once").isEqualTo(1);
		}
	}

	/**
	 * Resolving an existing tag must not rewrite it. A bare tagging call - a name and a collection, which is all a worker sends - keeps the meta a
	 * human curated on the shared tag.
	 */
	@Test
	public void testTaggingDoesNotOverwriteAnExistingTag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest curated = new TagCreateRequest();
			curated.setName("curated-tag");
			curated.setCollection("quality");
			curated.setMeta(new JsonObject().put("note", "hand written"));
			TagResponse created = client.tagAsset(ASSET_UUID, curated).sync().body();

			TagCreateRequest bare = new TagCreateRequest();
			bare.setName("curated-tag");
			bare.setCollection("quality");
			UUID secondAsset = createAsset(client, "b3", "tag-b3.png");
			TagResponse resolved = client.tagAsset(secondAsset, bare).sync().body();

			org.assertj.core.api.Assertions.assertThat(resolved.getUuid()).isEqualTo(created.getUuid());
			org.assertj.core.api.Assertions.assertThat(resolved.getMeta())
				.as("The response must report the persisted tag").isNotNull();
			org.assertj.core.api.Assertions.assertThat(resolved.getMeta().getString("note")).isEqualTo("hand written");

			TagResponse reloaded = client.loadTag(created.getUuid()).sync().body();
			org.assertj.core.api.Assertions.assertThat(reloaded.getMeta().getString("note"))
				.as("The curated meta must survive a bare tagging call").isEqualTo("hand written");
		}
	}

	private void assertTagged(LoomHttpClient client, UUID assetUuid, UUID tagUuid) throws LoomClientException {
		AssetResponse asset = client.loadAsset(assetUuid).sync().body();
		org.assertj.core.api.Assertions.assertThat(asset.getTags().stream().anyMatch(t -> t.getUuid().equals(tagUuid)))
			.as("Asset " + assetUuid + " must carry the tag").isTrue();
	}

	/** A second asset to tag. The fixture ships one, and one asset cannot show that a tag is shared. */
	private UUID createAsset(LoomHttpClient client, String seed, String filename) throws LoomClientException {
		SHA512 sha = SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%4s", seed).replace(' ', '0'));
		AssetCreateRequest request = new AssetCreateRequest();
		request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename(filename).setSize(512L).setOrigin(INITIAL_ORIGIN));
		request.setHashes(new HashInfo().setSHA512(sha));
		return client.createAsset(request).sync().body().getUuid();
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
