package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.node.sink.s3.S3SinkNode;
import io.metaloom.cortex.node.sink.s3.S3SinkNodeOptions;
import io.metaloom.cortex.s3.AwsS3ObjectStore;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.test.container.MinioContainer;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * End-to-end test for {@code s3-sink} against a real MinIO server and a real in-process Loom.
 *
 * <p>Proves the three claims the node makes that a fake store cannot: the bytes really land in a
 * bucket with the right content type, the artifact really becomes a retrievable Loom asset, and
 * two sink instances really coexist on one source asset - which is what the
 * {@code AbstractMediaNode.nodeId()} fix was for.</p>
 */
public class S3SinkNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String BUCKET = "s3-sink-it";
	private static final String MIME = "application/octet-stream";

	private static MinioContainer minio;
	private static S3Client adminClient;

	private S3ObjectStore store;
	private S3Support support;
	private Path metaPath;
	private Path thumb;
	private CortexOptions cortexOptions;

	@BeforeAll
	public static void startMinio() {
		minio = new MinioContainer();
		minio.start();
		adminClient = S3Client.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.region(Region.of(MinioContainer.REGION))
			.endpointOverride(URI.create(minio.endpoint()))
			.forcePathStyle(true)
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(MinioContainer.ACCESS_KEY, MinioContainer.SECRET_KEY)))
			.build();
		adminClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
	}

	@AfterAll
	public static void stopMinio() {
		if (adminClient != null) {
			adminClient.close();
		}
		if (minio != null) {
			minio.stop();
		}
	}

	@BeforeEach
	public void setupSink() throws Exception {
		metaPath = Files.createTempDirectory("s3-sink-it");
		cortexOptions = new CortexOptions().setMetaPath(metaPath);

		S3ClientOptions options = new S3ClientOptions()
			.setEndpoint(minio.endpoint())
			.setRegion(MinioContainer.REGION)
			.setAccessKey(MinioContainer.ACCESS_KEY)
			.setSecretKey(MinioContainer.SECRET_KEY)
			.setPathStyleAccess(true);
		store = new AwsS3ObjectStore(options);
		support = S3Support.active(store, new S3MediaMaterializer(store, metaPath.resolve("s3_bin"), 0, 0),
			metaPath.resolve("s3-index"));

		// A contact sheet exactly where ThumbnailNode would have written one. The .thumb suffix is
		// the interesting part: it is a JPEG, and the sink has to know that.
		thumb = metaPath.resolve("thumbnail_bin/ab/sheet-" + System.nanoTime() + ".thumb");
		Files.createDirectories(thumb.getParent());
		Files.writeString(thumb, "contact-sheet-bytes-" + System.nanoTime());
	}

	private S3SinkNode sink(LoomHttpClient client, String id, JsonObject extra) {
		S3SinkNode node = new S3SinkNode(client, cortexOptions, new S3SinkNodeOptions(), support);
		JsonObject def = new JsonObject().put("id", id).put("bucket", BUCKET);
		if (extra != null) {
			extra.forEach(e -> def.put(e.getKey(), e.getValue()));
		}
		node.configure(def);
		return node;
	}

	private NodeContext<LoomMedia> ctx(LoomMedia media) {
		return NodeContext.create(media, Map.of("thumbnail", Map.of("thumbnail_path", thumb.toString())));
	}

	private String onlyKeyIn(JsonObject payload) {
		return payload.getJsonArray("artifacts").getJsonObject(0).getString("key");
	}

	private JsonObject payloadOf(LoomHttpClient client, AssetResponse asset, String variant) throws Exception {
		List<JsonCompResponse> comps = client.listAssetJsonComps(asset.getUuid()).sync().body().getData();
		return comps.stream()
			.filter(c -> "s3-artifact".equals(c.getSchemaType()) && variant.equals(c.getVariant()))
			.map(JsonCompResponse::getData)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no s3-artifact component with variant " + variant));
	}

	@Test
	public void testUploadsTheArtifactAndRegistersItAsAnAsset() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "source-media".getBytes(StandardCharsets.UTF_8));

			NodeResult result = sink(client, "archive", null).process(ctx(source.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			JsonObject payload = payloadOf(client, source.asset(), "archive");
			String key = onlyKeyIn(payload);

			// 1. The bytes are really in the bucket, with the right content type.
			HeadObjectResponse head = adminClient.headObject(
				HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
			assertThat(head.contentLength()).isEqualTo(Files.size(thumb));
			assertThat(head.contentType())
				.as(".thumb is a JPEG; octet-stream here means S3ContentTypes regressed")
				.isEqualTo("image/jpeg");

			// 2. The artifact is a retrievable Loom asset in its own right.
			SHA512 artifactHash = HashUtils.computeSHA512(thumb.toFile());
			AssetResponse artifactAsset = client.loadAsset(artifactHash).sync().body();
			assertThat(artifactAsset).isNotNull();
			assertThat(artifactAsset.getFile().getOrigin()).isEqualTo("s3://" + BUCKET + "/" + key);
			assertThat(artifactAsset.getFile().getMimeType()).isEqualTo("image/jpeg");

			// 3. The ledger row is readable back through REST.
			boolean recorded = client.listAssetNodeResults(source.asset().getUuid()).sync().body().getData()
				.stream().anyMatch(r -> "s3-sink".equals(r.getNodeKind()));
			assertThat(recorded).isTrue();
		});
	}

	@Test
	public void testTheKeyIsContentAddressedAndSharded() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "src".getBytes(StandardCharsets.UTF_8));
			sink(client, "archive", null).process(ctx(source.media()));

			String key = onlyKeyIn(payloadOf(client, source.asset(), "archive"));
			SHA512 hash = HashUtils.computeSHA512(thumb.toFile());

			assertThat(key).isEqualTo("cortex/thumbnail/thumbnail_path/"
				+ hash.toString().substring(0, 4) + "/" + hash + ".thumb");
		});
	}

	@Test
	public void testARerunUploadsNothing() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "src".getBytes(StandardCharsets.UTF_8));
			sink(client, "archive", null).process(ctx(source.media()));

			String key = onlyKeyIn(payloadOf(client, source.asset(), "archive"));
			Instant first = adminClient.headObject(
				HeadObjectRequest.builder().bucket(BUCKET).key(key).build()).lastModified();

			Thread.sleep(1100);
			NodeResult second = sink(client, "archive", null).process(ctx(source.media()));

			assertThat(second.getState()).isEqualTo(ResultState.SUCCESS);
			// Unchanged last-modified is the proof the object was not rewritten.
			assertThat(adminClient.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build())
				.lastModified()).isEqualTo(first);

			JsonObject payload = payloadOf(client, source.asset(), "archive");
			assertThat(payload.getJsonArray("artifacts").getJsonObject(0).getString("state"))
				.isEqualTo("PRESENT");
		});
	}

	@Test
	public void testTwoSinkInstancesCoexistOnOneAsset() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "src".getBytes(StandardCharsets.UTF_8));

			sink(client, "archive", null).process(ctx(source.media()));
			sink(client, "backup", new JsonObject().put("keyTemplate", "backup/{nodeId}/{sha512}{ext}"))
				.process(ctx(source.media()));

			// Two components, discriminated by variant = node id.
			assertThat(payloadOf(client, source.asset(), "archive")).isNotNull();
			assertThat(payloadOf(client, source.asset(), "backup")).isNotNull();

			// And two ledger rows. Before AbstractMediaNode.nodeId() existed, node_id was
			// hard-coded to "" and asset_node_result is UNIQUE on (asset, kind, node_id), so the
			// second sink silently overwrote the first's row.
			List<NodeResultResponse> ledger = client.listAssetNodeResults(source.asset().getUuid())
				.sync().body().getData().stream()
				.filter(r -> "s3-sink".equals(r.getNodeKind()))
				.toList();
			assertThat(ledger).extracting(NodeResultResponse::getNodeId)
				.containsExactlyInAnyOrder("archive", "backup");
		});
	}

	@Test
	public void testIncludeSourceArchivesTheMediaItself() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "the-original".getBytes(StandardCharsets.UTF_8));

			sink(client, "archive", new JsonObject().put("includeSource", true)).process(ctx(source.media()));

			JsonObject payload = payloadOf(client, source.asset(), "archive");
			assertThat(payload.getInteger("count")).isEqualTo(2);
			assertThat(payload.getJsonArray("artifacts").stream()
				.map(o -> ((JsonObject) o).getString("sourceNode")))
					.containsExactlyInAnyOrder("thumbnail", "media");
		});
	}

	@Test
	public void testAMissingArtifactFailsWithTheAffinityHint() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "src".getBytes(StandardCharsets.UTF_8));
			Files.delete(thumb);

			NodeResult result = sink(client, "archive", null).process(ctx(source.media()));

			// A sink that quietly uploads nothing looks like success - this is the case that must
			// never be quiet.
			assertThat(result.getState()).isEqualTo(ResultState.FAILED);
			assertThat(result.getMessage()).contains("same worker");
		});
	}

	@Test
	public void testDeleteAfterUploadRemovesTheLocalFile() throws Exception {
		withLoom(client -> {
			UniqueAsset source = createUniqueAsset(client, MIME, "src".getBytes(StandardCharsets.UTF_8));

			sink(client, "archive", new JsonObject().put("deleteAfterUpload", true))
				.process(ctx(source.media()));

			assertThat(thumb).doesNotExist();
		});
	}
}
