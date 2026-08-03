package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.metadata.MetadataNode;
import io.metaloom.cortex.node.metadata.MetadataNodeOptions;
import io.metaloom.cortex.node.metadata.fixture.ExifJpegFixture;
import io.metaloom.cortex.node.metadata.fixture.XmpFixture;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.vertx.core.json.JsonObject;

/**
 * End to end against a real Loom and the pooled database: the node reads a real file, and the
 * envelope and the position are read back through the REST API.
 *
 * <p>
 * The assertions deliberately look <em>inside</em> the component. Asserting only that the payload is
 * non-null passes vacuously - the {@code tika} integration test does exactly that, and it kept
 * passing for as long as that node stored {@code {"content": ""}}.
 * </p>
 */
public class MetadataNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static byte[] geotaggedJpeg() throws Exception {
		return ExifJpegFixture.builder()
			.make("SONY")
			.model("ILCE-7M3")
			.imageDescription("a caption the camera wrote")
			.dateTimeOriginal("2019:04:03 05:12:44")
			.offsetTimeOriginal("+09:00")
			.gps(35.360833, 138.727500)
			.altitude(2305)
			.xmp(XmpFixture.titled("Sunrise over Fuji", "Taken from the fifth station.", "Jane Doe"))
			.build();
	}

	@Test
	public void testMetadataPersistsTheEnvelopeAndThePosition() throws Exception {
		withLoom(client -> {
			UniqueAsset asset = createUniqueAsset(client, "image/jpeg", geotaggedJpeg(), ".jpg");

			MetadataNode node = new MetadataNode(client, cortexOptions(), new MetadataNodeOptions());
			NodeResult result = node.process(NodeContext.create(asset.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			JsonCompResponse comp = metadataComp(client, asset);
			assertThat(comp).as("the metadata component must be readable via REST").isNotNull();
			assertThat(comp.getNodeKind()).isEqualTo("metadata");

			JsonObject envelope = comp.getData();
			assertThat(envelope).as("the stored envelope must not be empty").isNotNull();
			assertThat(envelope.getInteger("v")).isEqualTo(1);
			assertThat(envelope.getJsonObject("dc").getString("title"))
				.as("the caption written into the file must survive the round trip")
				.isEqualTo("Sunrise over Fuji");
			assertThat(envelope.getJsonObject("dc").getString("date")).isEqualTo("2019-04-03T05:12:44+09:00");
			assertThat(envelope.getJsonObject("capture").getString("make")).isEqualTo("SONY");
			assertThat(envelope.getJsonObject("geo").getDouble("lat")).isCloseTo(35.360833d, within(1e-4));

			AssetComponentResponse geo = geoComp(client, asset);
			assertThat(geo).as("the position must be stored as a geo component").isNotNull();
			// The method is the source the coordinate came from, which is part of the row's identity.
			assertThat(geo.getMethod()).isEqualTo("exif");
			assertThat(geo.getGeo().getLat()).isCloseTo(35.360833d, within(1e-4));
		});
	}

	@Test
	public void testARerunReplacesItsOwnRowsRatherThanFailing() throws Exception {
		withLoom(client -> {
			UniqueAsset asset = createUniqueAsset(client, "image/jpeg", geotaggedJpeg(), ".jpg");

			// Two node instances rather than one, so the second run really goes through the write
			// path instead of being served from the first node's in-heap cache.
			new MetadataNode(client, cortexOptions(), new MetadataNodeOptions())
				.process(NodeContext.create(asset.media()));
			NodeResult second = new MetadataNode(client, cortexOptions(), new MetadataNodeOptions())
				.process(NodeContext.create(asset.media()));

			assertThat(second.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(client.listAssetJsonComps(asset.asset().getUuid()).sync().body().getData().stream()
				.filter(c -> "metadata".equals(c.getSchemaType())).count()).isEqualTo(1);
			assertThat(client.listAssetComponents(asset.asset().getUuid()).sync().body().getData().stream()
				.filter(c -> c.getType() == AssetComponentType.GEO).count()).isEqualTo(1);
		});
	}

	@Test
	public void testAFileWithoutMetadataStillRecordsAnEnvelope() throws Exception {
		withLoom(client -> {
			UniqueAsset asset = createUniqueAsset(client, "image/jpeg", ExifJpegFixture.builder().build(), ".jpg");

			NodeResult result = new MetadataNode(client, cortexOptions(), new MetadataNodeOptions())
				.process(NodeContext.create(asset.media()));

			// "This file says nothing about itself" is a result, not a skip and not a failure.
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			JsonCompResponse comp = metadataComp(client, asset);
			assertThat(comp).isNotNull();
			assertThat(comp.getData().getInteger("v")).isEqualTo(1);

			assertThat(geoComp(client, asset)).as("no coordinate means no geo component").isNull();
		});
	}

	private static JsonCompResponse metadataComp(io.metaloom.loom.client.http.LoomHttpClient client, UniqueAsset asset)
		throws Exception {
		return client.listAssetJsonComps(asset.asset().getUuid()).sync().body().getData().stream()
			.filter(c -> "metadata".equals(c.getSchemaType()))
			.findFirst().orElse(null);
	}

	private static AssetComponentResponse geoComp(io.metaloom.loom.client.http.LoomHttpClient client, UniqueAsset asset)
		throws Exception {
		return client.listAssetComponents(asset.asset().getUuid()).sync().body().getData().stream()
			.filter(c -> c.getType() == AssetComponentType.GEO)
			.findFirst().orElse(null);
	}
}
