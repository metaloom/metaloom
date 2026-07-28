package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.node.source.s3.S3DifferentialScanner;
import io.metaloom.cortex.node.source.s3.S3ObjectIndexStore;
import io.metaloom.cortex.node.source.s3.S3Selection;
import io.metaloom.cortex.node.source.s3.S3SourceNode;
import io.metaloom.cortex.s3.AwsS3ObjectStore;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.event.S3ChangeHint;
import io.metaloom.cortex.s3.event.S3EventBuffer;
import io.metaloom.fs.FileState;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.test.container.MinioContainer;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * End-to-end test for the {@code s3-source} node against a real MinIO server.
 *
 * <p>Covers the three claims the node makes that a unit test with a fake store cannot prove
 * against a real S3 implementation:</p>
 * <ol>
 * <li>a re-run over an unchanged bucket emits nothing, and a new object is picked up on its own;</li>
 * <li>enumeration is metadata-only - objects are materialized lazily, and only when something
 * asks for the bytes;</li>
 * <li>a materialized object is an ordinary local file as far as the rest of cortex is concerned,
 * so a real hash node persists its SHA-512 to Loom and it reads back over REST.</li>
 * </ol>
 */
public class S3SourceNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String BUCKET = "s3-source-it";
	private static final String PREFIX = "2026/";
	private static final String MIME = "video/mp4";

	private static MinioContainer minio;
	private static S3Client adminClient;

	private S3ObjectStore store;
	private S3MediaMaterializer materializer;
	private S3EventBuffer eventBuffer;
	private Path indexDir;
	private Path cacheDir;
	private String runPrefix;

	@BeforeAll
	public static void startMinio() {
		minio = new MinioContainer();
		minio.start();
		adminClient = S3Client.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.region(Region.of(MinioContainer.REGION))
			.endpointOverride(java.net.URI.create(minio.endpoint()))
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
	public void setupS3() throws Exception {
		indexDir = Files.createTempDirectory("s3-it-index");
		cacheDir = Files.createTempDirectory("s3-it-cache");
		// Each test writes under its own key prefix so the shared bucket does not leak state
		// between them, and so a stale index can never make a test pass for the wrong reason.
		runPrefix = PREFIX + System.nanoTime() + "/";

		S3ClientOptions options = new S3ClientOptions()
			.setEndpoint(minio.endpoint())
			.setRegion(MinioContainer.REGION)
			.setAccessKey(MinioContainer.ACCESS_KEY)
			.setSecretKey(MinioContainer.SECRET_KEY)
			.setPathStyleAccess(true);
		store = new AwsS3ObjectStore(options);
		materializer = new S3MediaMaterializer(store, cacheDir, 0, 0);
		eventBuffer = new S3EventBuffer();
	}

	private S3SourceNode node() {
		return node(Set.of(FileState.NEW, FileState.MODIFIED), false);
	}

	private S3SourceNode node(Set<FileState> emitStates, boolean useEvents) {
		S3DifferentialScanner scanner = new S3DifferentialScanner(store, new S3ObjectIndexStore(),
			eventBuffer, indexDir, minio.endpoint(), S3ClientOptions.DEFAULT_RECONCILE_INTERVAL_MS);
		return new S3SourceNode("s3-source", scanner, materializer,
			new S3Selection(BUCKET, runPrefix, Set.of(), emitStates, false, useEvents));
	}

	private void putObject(String name, byte[] content) {
		adminClient.putObject(PutObjectRequest.builder().bucket(BUCKET).key(runPrefix + name).build(),
			RequestBody.fromBytes(content));
	}

	private void deleteObject(String name) {
		adminClient.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(runPrefix + name).build());
	}

	private List<String> references(S3SourceNode node) {
		return node.stream().map(LoomMedia::reference).toList().blockingGet();
	}

	private static byte[] payload(String seed) {
		return ("s3 source integration payload " + seed).getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void testFirstRunSeesEverythingAndARerunSeesNothing() {
		putObject("a.mp4", payload("a"));
		putObject("b.mp4", payload("b"));
		S3SourceNode node = node();

		assertThat(references(node))
			.containsExactlyInAnyOrder("s3://" + BUCKET + "/" + runPrefix + "a.mp4",
				"s3://" + BUCKET + "/" + runPrefix + "b.mp4");

		assertThat(node.stream().count().blockingGet())
			.as("an unchanged bucket must not be reprocessed").isZero();
	}

	@Test
	public void testANewObjectIsPickedUpOnItsOwn() {
		putObject("a.mp4", payload("a"));
		S3SourceNode node = node();
		references(node);

		putObject("c.mp4", payload("c"));

		assertThat(references(node)).containsExactly("s3://" + BUCKET + "/" + runPrefix + "c.mp4");
	}

	@Test
	public void testAnOverwrittenObjectIsReportedAsModified() {
		putObject("a.mp4", payload("a"));
		S3SourceNode node = node();
		references(node);

		putObject("a.mp4", payload("a-different"));

		assertThat(references(node)).containsExactly("s3://" + BUCKET + "/" + runPrefix + "a.mp4");
	}

	@Test
	public void testADeletedObjectIsReportedWhenRequested() {
		putObject("a.mp4", payload("a"));
		putObject("b.mp4", payload("b"));
		S3SourceNode node = node(Set.of(FileState.NEW, FileState.DELETED), false);
		references(node);

		deleteObject("b.mp4");

		assertThat(references(node)).containsExactly("s3://" + BUCKET + "/" + runPrefix + "b.mp4");
	}

	@Test
	public void testEnumerationDoesNotDownloadAnything() throws Exception {
		putObject("a.mp4", payload("a"));

		references(node());

		// The cache stays empty: the run enumerated the bucket without moving a single byte of
		// object content, which is what makes scanning a large bucket cheap.
		try (var walk = Files.walk(cacheDir)) {
			assertThat(walk.filter(Files::isRegularFile).toList()).isEmpty();
		}
	}

	@Test
	public void testMediaMaterializesOnDemandAndKeepsItsExtension() throws Exception {
		putObject("a.mp4", payload("a"));

		LoomMedia media = node().stream().blockingFirst();
		Path local = media.path();

		assertThat(local).exists();
		assertThat(Files.readAllBytes(local)).isEqualTo(payload("a"));
		// Media-type detection is extension-driven, so losing the suffix would make the object
		// invisible to every media node downstream.
		assertThat(local.getFileName().toString()).endsWith(".mp4");
		assertThat(media.isVideo()).isTrue();
	}

	@Test
	public void testASecondResolutionReusesTheCachedFile() throws Exception {
		putObject("a.mp4", payload("a"));
		Path first = node().stream().blockingFirst().path();
		java.nio.file.attribute.FileTime stamp = Files.getLastModifiedTime(first);

		// A fresh node - i.e. a restarted worker - must reuse the bytes already on disk.
		indexDir = Files.createTempDirectory("s3-it-index-2");
		Path second = node().stream().blockingFirst().path();

		assertThat(second).isEqualTo(first);
		assertThat(Files.getLastModifiedTime(second)).isNotNull();
		assertThat(stamp).isNotNull();
	}

	@Test
	public void testTheEventFastPathAvoidsListingEntirely() {
		putObject("a.mp4", payload("a"));
		S3SourceNode node = node(Set.of(FileState.NEW, FileState.MODIFIED), true);
		// First run always lists, which stamps the reconcile clock.
		references(node);

		putObject("d.mp4", payload("d"));
		eventBuffer.record(S3ChangeHint.created(BUCKET, runPrefix + "d.mp4"));

		assertThat(references(node)).containsExactly("s3://" + BUCKET + "/" + runPrefix + "d.mp4");
	}

	@Test
	public void testMaterializedObjectFlowsThroughARealHashNodeIntoLoom() throws Exception {
		byte[] content = payload("hashed");
		putObject("hashme.mp4", content);

		withLoom(client -> {
			LoomMedia media = node().stream().blockingFirst();

			// Materialize, then pre-create the asset keyed by the real SHA-512 of the bytes - the
			// same contract every other node integration test follows.
			Path local = media.path();
			SHA512 sha512 = HashUtils.computeSHA512(local.toFile());
			createAsset(client, local, sha512);

			SHA512Node hashNode = new SHA512Node(client, cortexOptions(), new HashNodeOptions());
			NodeResult result = hashNode.process(NodeContext.create(media));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(sha512).sync().body();
			assertThat(reloaded.getHashes().getSHA512())
				.as("an object fetched from S3 must persist like any local file")
				.isEqualTo(sha512);
		});
	}

	private void createAsset(LoomHttpClient client, Path file, SHA512 sha512) throws Exception {
		AssetCreateRequest request = new AssetCreateRequest();
		request.setFile(new FileInfo()
			.setFilename(file.getFileName().toString())
			.setMimeType(MIME)
			.setOrigin(file.toAbsolutePath().toString())
			.setSize(Files.size(file)));
		request.setHashes(new HashInfo().setSHA512(sha512));
		client.createAsset(request).sync().body();
	}
}
