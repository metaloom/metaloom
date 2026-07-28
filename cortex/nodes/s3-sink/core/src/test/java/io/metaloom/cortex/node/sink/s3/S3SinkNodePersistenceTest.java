package io.metaloom.cortex.node.sink.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.cortex.s3.FakeS3ObjectStore;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * The Loom side of the sink: an asset per uploaded artifact, an {@code s3-artifact} component
 * indexing them on the source asset, and the {@code asset_node_result} ledger.
 */
class S3SinkNodePersistenceTest {

	private static final SHA512 SOURCE_HASH = SHA512.fromString("a".repeat(128));
	private static final String BUCKET = "media";

	@TempDir
	File tempDir;

	private final UUID sourceAssetUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private CortexOptions cortexOptions;
	private FakeS3ObjectStore store;
	private StubLoomMedia media;
	private Path thumb;

	/** Assets the node created, in order. */
	private final List<AssetCreateRequest> created = new ArrayList<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		store = new FakeS3ObjectStore();
		thumb = write("thumbnail_bin/ab/sheet.thumb", "contact-sheet-bytes");

		client = mock(LoomHttpClient.class);

		// The source asset resolves; artifact hashes do not (they are new).
		AssetResponse sourceAsset = new AssetResponse().setUuid(sourceAssetUuid);
		LoomClientRequest<AssetResponse> sourceReq = mock(LoomClientRequest.class);
		when(sourceReq.sync()).thenReturn(new LoomClientResponseImpl<>(sourceAsset, 200, "OK", Map.of()));
		LoomClientRequest<AssetResponse> missingReq = mock(LoomClientRequest.class);
		when(missingReq.sync()).thenThrow(new RuntimeException("404 not found"));
		when(client.loadAsset(nullable(SHA512.class))).thenAnswer(inv -> {
			SHA512 asked = inv.getArgument(0);
			return SOURCE_HASH.equals(asked) ? sourceReq : missingReq;
		});

		LoomClientRequest<AssetResponse> createReq = mock(LoomClientRequest.class);
		when(createReq.sync()).thenAnswer(inv -> new LoomClientResponseImpl<>(
			new AssetResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAsset(any())).thenAnswer(inv -> {
			created.add(inv.getArgument(0));
			return createReq;
		});

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(
			new JsonCompResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync()).thenReturn(new LoomClientResponseImpl<>(
			new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
		media.setSHA512(SOURCE_HASH);
	}

	private Path write(String relative, String content) throws IOException {
		Path file = tempDir.toPath().resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file.toAbsolutePath().normalize();
	}

	private S3SinkNode node(String id) {
		S3Support support = S3Support.active(store,
			new S3MediaMaterializer(store, tempDir.toPath().resolve("s3_bin"), 0, 0), null);
		S3SinkNode node = new S3SinkNode(client, cortexOptions, new S3SinkNodeOptions(), support);
		node.configure(new JsonObject().put("id", id).put("bucket", BUCKET));
		return node;
	}

	private NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx() {
		return NodeContext.create(media, Map.of("thumbnail", Map.of("thumbnail_path", thumb.toString())));
	}

	// --- asset creation -------------------------------------------------------------------

	@Test
	void testCreatesAnAssetForTheUploadedArtifact() {
		node("archive").process(ctx());

		assertThat(created).hasSize(1);
		AssetCreateRequest request = created.get(0);
		assertThat(request.getFile().getFilename()).isEqualTo("sheet.thumb");
		assertThat(request.getFile().getMimeType()).isEqualTo("image/jpeg");
		assertThat(request.getFile().getSize()).isEqualTo("contact-sheet-bytes".length());
		// The asset.initial_origin column comment sanctions "first s3 path" as a value.
		assertThat(request.getFile().getOrigin()).startsWith("s3://" + BUCKET + "/");
		assertThat(request.getHashes().getSHA512()).isNotNull();
	}

	@Test
	void testTheArtifactAssetCarriesItsProvenance() {
		node("archive").process(ctx());

		JsonObject meta = created.get(0).getMeta();
		assertThat(meta.getString("producedBy")).isEqualTo("s3-sink");
		assertThat(meta.getString("sinkNodeId")).isEqualTo("archive");
		assertThat(meta.getString("sourceNode")).isEqualTo("thumbnail");
		assertThat(meta.getString("sourceAssetUuid")).isEqualTo(sourceAssetUuid.toString());
	}

	@Test
	void testTheArtifactHashIsItsOwnNotTheSourceMedia() {
		node("archive").process(ctx());

		// asset.sha512sum is the content identity, so the artifact must be hashed, not the video.
		assertThat(created.get(0).getHashes().getSHA512()).isNotEqualTo(SOURCE_HASH);
	}

	@Test
	void testCreateAssetsCanBeDisabled() {
		S3Support support = S3Support.active(store,
			new S3MediaMaterializer(store, tempDir.toPath().resolve("s3_bin"), 0, 0), null);
		S3SinkNode node = new S3SinkNode(client, cortexOptions, new S3SinkNodeOptions(), support);
		node.configure(new JsonObject().put("id", "archive").put("bucket", BUCKET).put("createAssets", false));

		node.process(ctx());

		assertThat(created).isEmpty();
		assertThat(store.uploadCalls).hasValue(1);
	}

	// --- the component index --------------------------------------------------------------

	@Test
	void testWritesTheArtifactIndexComponent() {
		node("archive").process(ctx());

		ArgumentCaptor<JsonCompCreateRequest> captor = ArgumentCaptor.forClass(JsonCompCreateRequest.class);
		verify(client).createAssetJsonComp(eq(sourceAssetUuid), captor.capture());

		JsonCompCreateRequest request = captor.getValue();
		assertThat(request.getNodeKind()).isEqualTo("s3-sink");
		assertThat(request.getSchemaType()).isEqualTo("s3-artifact");
		// variant = node id, so two sinks coexist on one asset instead of overwriting each other.
		assertThat(request.getVariant()).isEqualTo("archive");
		assertThat(request.getProducerVersion()).isEqualTo(BUCKET);

		JsonObject data = request.getData();
		assertThat(data.getString("bucket")).isEqualTo(BUCKET);
		assertThat(data.getInteger("count")).isEqualTo(1);
		assertThat(data.getInteger("uploaded")).isEqualTo(1);
		JsonObject artifact = data.getJsonArray("artifacts").getJsonObject(0);
		assertThat(artifact.getString("state")).isEqualTo("UPLOADED");
		assertThat(artifact.getString("uri")).startsWith("s3://" + BUCKET + "/");
		assertThat(artifact.getString("sourceNode")).isEqualTo("thumbnail");
		assertThat(artifact.getString("contentType")).isEqualTo("image/jpeg");
		assertThat(artifact.getString("assetUuid")).isNotBlank();
	}

	@Test
	void testAFailedArtifactIsRecordedRatherThanLost() {
		store.failUploadWith(new IOException("bucket is full"));

		node("archive").process(ctx());

		ArgumentCaptor<JsonCompCreateRequest> captor = ArgumentCaptor.forClass(JsonCompCreateRequest.class);
		verify(client).createAssetJsonComp(eq(sourceAssetUuid), captor.capture());

		// The component is written even on failure, which is what makes a partial run diagnosable
		// from Loom rather than from a log grep.
		JsonObject artifact = captor.getValue().getData().getJsonArray("artifacts").getJsonObject(0);
		assertThat(artifact.getString("state")).isEqualTo("FAILED");
		assertThat(artifact.getString("error")).contains("bucket is full");
	}

	// --- the ledger -----------------------------------------------------------------------

	@Test
	void testRecordsTheLedgerWithItsNodeId() {
		node("archive").process(ctx());

		verify(client).createAssetNodeResult(eq(sourceAssetUuid), argThat((NodeResultCreateRequest r) -> "s3-sink"
			.equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			// The fix that lets two sinks coexist: asset_node_result is UNIQUE on
			// (asset_uuid, node_kind, node_id), and this used to be hard-coded to "".
			&& "archive".equals(r.getNodeId())
			&& BUCKET.equals(r.getProducerVersion())
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))));
	}

	@Test
	void testTwoSinkInstancesWriteDistinctLedgerRows() {
		node("archive").process(ctx());
		node("backup").process(ctx());

		verify(client).createAssetNodeResult(eq(sourceAssetUuid),
			argThat((NodeResultCreateRequest r) -> "archive".equals(r.getNodeId())));
		verify(client).createAssetNodeResult(eq(sourceAssetUuid),
			argThat((NodeResultCreateRequest r) -> "backup".equals(r.getNodeId())));
	}

	@Test
	void testTwoSinkInstancesWriteDistinctComponents() {
		node("archive").process(ctx());
		node("backup").process(ctx());

		verify(client).createAssetJsonComp(eq(sourceAssetUuid),
			argThat((JsonCompCreateRequest r) -> "archive".equals(r.getVariant())));
		verify(client).createAssetJsonComp(eq(sourceAssetUuid),
			argThat((JsonCompCreateRequest r) -> "backup".equals(r.getVariant())));
	}

	@Test
	void testFailedUploadRecordsAFailedLedger() {
		store.failUploadWith(new IOException("bucket is full"));

		node("archive").process(ctx());

		verify(client).createAssetNodeResult(eq(sourceAssetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())
				&& r.getReason() != null && r.getReason().contains("uploaded 0 of 1")));
	}

	@Test
	void testNothingIsPersistedWhenThereAreNoArtifacts() {
		node("archive").process(NodeContext.create(media, Map.of()));

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client, never()).createAssetNodeResult(any(), any());
		verify(client, never()).createAsset(any());
	}
}
