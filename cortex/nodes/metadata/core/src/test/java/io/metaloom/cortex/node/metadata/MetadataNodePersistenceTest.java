package io.metaloom.cortex.node.metadata;

import static io.metaloom.cortex.node.metadata.assertj.MetadataNodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.metadata.fixture.ExifJpegFixture;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * The persistence contract: one {@code asset_json_comp} carrying the envelope, one
 * {@code asset_geo_comp} per position reading, and exactly one ledger row pointing at the envelope.
 * Loom is mocked, so this asserts what the node <em>asks</em> Loom to store.
 */
class MetadataNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private CortexOptions cortexOptions;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(
			new LoomClientResponseImpl<>(new AssetResponse().setUuid(assetUuid), 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<JsonCompResponse> jsonCompReq = mock(LoomClientRequest.class);
		when(jsonCompReq.sync()).thenReturn(
			new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(compUuid), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(jsonCompReq);

		LoomClientRequest<AssetComponentResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(
			new LoomClientResponseImpl<>(new AssetComponentResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetComponent(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync()).thenReturn(
			new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);
	}

	private MetadataNode node() {
		return node(new MetadataNodeOptions());
	}

	private MetadataNode node(MetadataNodeOptions options) {
		return new MetadataNode(client, cortexOptions, options);
	}

	private StubLoomMedia image(String name, byte[] content) {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, name, content);
		StubLoomMedia media = new StubLoomMedia(backing.file().getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
		return media;
	}

	private static byte[] geotagged() throws Exception {
		return ExifJpegFixture.builder()
			.make("SONY")
			.imageDescription("a caption")
			.dateTimeOriginal("2019:04:03 05:12:44")
			.gps(35.360833, 138.727500)
			.positioningError(12)
			.build();
	}

	private static byte[] plain() throws Exception {
		return ExifJpegFixture.builder().make("SONY").imageDescription("a caption").build();
	}

	@Test
	void testWritesTheEnvelopeAsAJsonComponent() throws Exception {
		assertThat(node().process(NodeContext.create(image("fuji.jpg", geotagged())))).isSuccess();

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "metadata".equals(r.getNodeKind())
			&& "metadata".equals(r.getSchemaType())
			// One envelope per asset per node kind, so a re-run replaces it in place.
			&& "".equals(r.getVariant())
			&& MetadataNode.PRODUCER_VERSION.equals(r.getProducerVersion())
			&& r.getData().getInteger("v") == AssetMetadata.VERSION));
	}

	@Test
	void testWritesOneGeoComponentPerReadingWithTheSourceAsItsMethod() throws Exception {
		node().process(NodeContext.create(image("fuji.jpg", geotagged())));

		verify(client, times(1)).createAssetComponent(eq(assetUuid), argThat((AssetComponentCreateRequest r) -> {
			// 'method' is the source the coordinate came from, never the file format: it is part of
			// the row's identity, so 'jpeg' there would make an EXIF and an XMP reading collide.
			return r.getType() == AssetComponentType.GEO
				&& "metadata".equals(r.getSource())
				&& RawMetadata.EXIF.equals(r.getMethod())
				&& Long.valueOf(0L).equals(r.getTimeFrom())
				&& MetadataNode.PRODUCER_VERSION.equals(r.getProducerVersion())
				&& r.getGeo() != null
				&& Math.abs(r.getGeo().getLat() - 35.360833d) < 1e-4
				&& r.getGeo().getAccuracyM() != null
				// A coordinate read out of a file is a recorded value, not a probabilistic estimate.
				&& r.getConfidence() == null;
		}));
	}

	@Test
	void testWritesNoGeoComponentWhenTheFileCarriesNoCoordinate() throws Exception {
		node().process(NodeContext.create(image("plain.jpg", plain())));

		verify(client, never()).createAssetComponent(any(), any());
		verify(client).createAssetJsonComp(any(), any());
	}

	@Test
	void testGeoComponentCanBeTurnedOffWithoutLosingTheEnvelope() throws Exception {
		node(new MetadataNodeOptions().setWriteGeoComponent(false))
			.process(NodeContext.create(image("fuji.jpg", geotagged())));

		verify(client, never()).createAssetComponent(any(), any());
		verify(client).createAssetJsonComp(any(), any());
	}

	@Test
	void testGpsPolicyDropWritesNoGeoComponent() throws Exception {
		node(new MetadataNodeOptions().setGpsPolicy(GpsPolicy.DROP))
			.process(NodeContext.create(image("fuji.jpg", geotagged())));

		verify(client, never()).createAssetComponent(any(), any());
	}

	@Test
	void testRecordsOneSuccessfulLedgerRowPointingAtTheEnvelope() throws Exception {
		node().process(NodeContext.create(image("fuji.jpg", geotagged())));

		verify(client, times(1)).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "metadata".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& MetadataNode.PRODUCER_VERSION.equals(r.getProducerVersion())
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void testRecordsAFailedLedgerRowWhenThePersistenceWriteThrows() throws Exception {
		when(client.createAssetJsonComp(any(), any())).thenThrow(new RuntimeException("loom unreachable"));

		// The component write is best-effort: the node still succeeds, and the ledger records why.
		NodeResult result = node().process(NodeContext.create(image("fuji.jpg", geotagged())));
		assertThat(result).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "loom unreachable".equals(r.getReason())));
	}

	@Test
	void testRecordsAFailedLedgerRowAndNoComponentWhenTheFileCannotBeParsed() throws Exception {
		NodeResult result = node().process(NodeContext.create(image("broken.jpg", "not a jpeg".getBytes())));

		assertThat(result).isFailed();
		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())));
	}

	@Test
	void testASecondRunIsServedFromTheCacheAndPersistsNothingAgain() throws Exception {
		MetadataNode node = node();
		StubLoomMedia media = image("fuji.jpg", geotagged());

		node.process(NodeContext.create(media));
		node.process(NodeContext.create(media));

		// The durable copy already exists in Loom, so a cache hit skips the recompute and the
		// re-write alike.
		verify(client, times(1)).createAssetJsonComp(any(), any());
		verify(client, times(1)).createAssetComponent(any(), any());
		verify(client, times(1)).createAssetNodeResult(any(), any());
	}

	@Test
	void testTheNodeIdDiscriminatesTwoConfiguredInstances() throws Exception {
		MetadataNode node = node();
		node.configure(new JsonObject().put("id", "public-branch").put("gpsPolicy", "ROUND"));

		node.process(NodeContext.create(image("fuji.jpg", geotagged())));

		// asset_node_result is UNIQUE (asset, node_kind, node_id): without the override both
		// instances would write to the same row and one would silently overwrite the other.
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "public-branch".equals(r.getNodeId())));
		verify(client).createAssetComponent(eq(assetUuid),
			argThat((AssetComponentCreateRequest r) -> "public-branch".equals(r.getNodeId())));
	}
}
