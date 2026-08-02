package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudUri;
import io.metaloom.cortex.cloud.auth.GoogleServiceAccountTokenSource;
import io.metaloom.cortex.cloud.gdrive.GoogleDriveFileStore;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.node.source.cloud.CloudDifferentialScanner;
import io.metaloom.cortex.node.source.cloud.CloudFileIndexStore;
import io.metaloom.cortex.node.source.cloud.CloudSelection;
import io.metaloom.cortex.node.source.cloud.CloudSourceNode;
import io.metaloom.fs.FileState;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * End-to-end test for the {@code gdrive-source} node against a stub Drive v3 server.
 *
 * <p>Unlike {@code S3SourceNodeIntegrationTest}, this cannot boot a real implementation: there is no
 * MinIO for Google Drive, and no test here has a Google account. What it does instead is run the
 * <em>real</em> client — real URLs, real JSON mapping, real signed JWT assertion, real materializer —
 * against {@link StubDriveApiServer}. Everything below Google's own service is therefore exercised
 * for real, which is precisely why the API and token URLs are configurable options rather than
 * constants.</p>
 *
 * <p>The claims it proves, which the unit tests with an in-memory store cannot:</p>
 * <ol>
 * <li>a re-run over an unchanged drive emits nothing, and a new file is picked up on its own,
 * through the HTTP client rather than a fake;</li>
 * <li>enumeration is metadata-only — files are materialized lazily, and only when something asks for
 * the bytes;</li>
 * <li>a materialized file is an ordinary local file as far as the rest of cortex is concerned, so a
 * real hash node persists its SHA-512 to Loom and it reads back over REST.</li>
 * </ol>
 */
public class CloudSourceNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String MIME = "video/mp4";
	private static final String DRIVE = CloudUri.MY_DRIVE;

	private StubDriveApiServer drive;
	private CloudFileStore store;
	private CloudMediaMaterializer materializer;
	private Path indexDir;
	private Path cacheDir;

	@BeforeEach
	public void setupDrive() throws Exception {
		drive = new StubDriveApiServer();
		indexDir = Files.createTempDirectory("gdrive-it-index");
		cacheDir = Files.createTempDirectory("gdrive-it-cache");

		GDriveClientOptions options = new GDriveClientOptions()
			.setApiBaseUrl(drive.baseUrl())
			.setTokenUrl(drive.tokenUrl())
			.setServiceAccountJson(serviceAccountKey());

		// The real token source: it signs an RS256 assertion and exchanges it, exactly as it would
		// against Google.
		store = new GoogleDriveFileStore(options, new GoogleServiceAccountTokenSource(options, java.time.Clock.systemUTC()));
		materializer = new CloudMediaMaterializer(store, cacheDir, 0, 0);
	}

	@AfterEach
	public void stopDrive() {
		if (drive != null) {
			drive.close();
		}
	}

	private static String serviceAccountKey() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		String pem = "-----BEGIN PRIVATE KEY-----\n"
			+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
				.encodeToString(keyPair.getPrivate().getEncoded())
			+ "\n-----END PRIVATE KEY-----\n";
		return new JsonObject()
			.put("client_email", "ingest@example.iam.gserviceaccount.com")
			.put("private_key", pem)
			.encode();
	}

	private CloudSourceNode node() {
		return node(Set.of(FileState.NEW, FileState.MODIFIED, FileState.MOVED), true);
	}

	private CloudSourceNode node(Set<FileState> emitStates, boolean useDelta) {
		CloudDifferentialScanner scanner = new CloudDifferentialScanner(store, new CloudFileIndexStore(),
			indexDir, io.metaloom.cortex.api.option.CloudClientOptions.DEFAULT_RECONCILE_INTERVAL_MS);
		return new CloudSourceNode("gdrive-source", scanner, materializer,
			new CloudSelection(CloudProviderId.GDRIVE, DRIVE, null, true, 0, Set.of(), Set.of(),
				emitStates, useDelta, false, false));
	}

	private List<String> references(CloudSourceNode node) {
		return node.stream().map(LoomMedia::reference).toList().blockingGet();
	}

	private static byte[] payload(String seed) {
		return ("gdrive source integration payload " + seed).getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void testFirstRunSeesEverythingAndARerunSeesNothing() {
		String a = drive.putFile(null, "a.mp4", payload("a"));
		String b = drive.putFile(null, "b.mp4", payload("b"));
		CloudSourceNode node = node();

		assertThat(references(node)).containsExactlyInAnyOrder(
			"gdrive://" + DRIVE + "/" + a + "/a.mp4",
			"gdrive://" + DRIVE + "/" + b + "/b.mp4");

		assertThat(node.stream().count().blockingGet())
			.as("an unchanged drive must not be reprocessed").isZero();
	}

	@Test
	public void testANewFileIsPickedUpOnItsOwn() {
		drive.putFile(null, "a.mp4", payload("a"));
		CloudSourceNode node = node();
		references(node);

		String added = drive.putFile(null, "c.mp4", payload("c"));

		assertThat(references(node)).containsExactly("gdrive://" + DRIVE + "/" + added + "/c.mp4");
	}

	@Test
	public void testAnEditedFileIsReportedAsModified() {
		String a = drive.putFile(null, "a.mp4", payload("a"));
		CloudSourceNode node = node();
		references(node);

		drive.update(a, payload("a-different"));

		assertThat(references(node)).containsExactly("gdrive://" + DRIVE + "/" + a + "/a.mp4");
		assertThat(node.lastState("gdrive://" + DRIVE + "/" + a + "/a.mp4")).isEqualTo(FileState.MODIFIED);
	}

	/** The capability the S3 source cannot offer, proven over the real client. */
	@Test
	public void testARenameIsReportedAsMovedNotAsANewFile() {
		String a = drive.putFile(null, "a.mp4", payload("a"));
		CloudSourceNode node = node();
		references(node);

		drive.rename(a, "renamed.mp4");

		String reference = "gdrive://" + DRIVE + "/" + a + "/renamed.mp4";
		assertThat(references(node)).containsExactly(reference);
		assertThat(node.lastState(reference)).isEqualTo(FileState.MOVED);
	}

	@Test
	public void testADeletedFileIsReportedWhenRequested() {
		drive.putFile(null, "a.mp4", payload("a"));
		String b = drive.putFile(null, "b.mp4", payload("b"));
		// The delta feed reports only what changed, and a stub cannot invent a tombstone for a file
		// it has forgotten - so a deletion is what the reconcile walk is for.
		CloudSourceNode node = node(Set.of(FileState.NEW, FileState.DELETED), false);
		references(node);

		drive.remove(b);

		assertThat(references(node)).containsExactly("gdrive://" + DRIVE + "/" + b + "/b.mp4");
	}

	@Test
	public void testEnumerationDoesNotDownloadAnything() throws Exception {
		drive.putFile(null, "a.mp4", payload("a"));

		references(node());

		assertThat(drive.downloadCalls).hasValue(0);
		// The cache stays empty: the run enumerated the drive without moving a single byte of file
		// content, which is what makes scanning a large drive cheap.
		try (var walk = Files.walk(cacheDir)) {
			assertThat(walk.filter(Files::isRegularFile).toList()).isEmpty();
		}
	}

	@Test
	public void testTheChangeFeedAvoidsListingEntirely() {
		drive.putFile(null, "a.mp4", payload("a"));
		CloudSourceNode node = node();
		// The first run always walks, which stamps the reconcile clock and takes a cursor.
		references(node);
		int listsAfterFirstRun = drive.listCalls.get();

		String added = drive.putFile(null, "d.mp4", payload("d"));

		assertThat(references(node)).containsExactly("gdrive://" + DRIVE + "/" + added + "/d.mp4");
		assertThat(drive.listCalls).as("the delta path must not list the folders").hasValue(listsAfterFirstRun);
	}

	@Test
	public void testMediaMaterializesOnDemandAndKeepsItsExtension() throws Exception {
		drive.putFile(null, "a.mp4", payload("a"));

		LoomMedia media = node().stream().blockingFirst();
		Path local = media.path();

		assertThat(local).exists();
		assertThat(Files.readAllBytes(local)).isEqualTo(payload("a"));
		// Media-type detection is extension-driven, so losing the suffix would make the file
		// invisible to every media node downstream.
		assertThat(local.getFileName().toString()).endsWith(".mp4");
		assertThat(media.isVideo()).isTrue();
		assertThat(drive.downloadCalls).hasValue(1);
	}

	@Test
	public void testASecondResolutionReusesTheCachedFile() throws Exception {
		drive.putFile(null, "a.mp4", payload("a"));
		Path first = node().stream().blockingFirst().path();

		// A fresh index - i.e. a restarted worker - must reuse the bytes already on disk.
		indexDir = Files.createTempDirectory("gdrive-it-index-2");
		Path second = node().stream().blockingFirst().path();

		assertThat(second).isEqualTo(first);
		assertThat(drive.downloadCalls).as("the cached copy must be reused").hasValue(1);
	}

	@Test
	public void testMaterializedFileFlowsThroughARealHashNodeIntoLoom() throws Exception {
		byte[] content = payload("hashed");
		drive.putFile(null, "hashme.mp4", content);

		withLoom(client -> {
			LoomMedia media = node().stream().blockingFirst();

			Path local = media.path();
			SHA512 sha512 = HashUtils.computeSHA512(local.toFile());
			createAsset(client, local, sha512);

			SHA512Node hashNode = new SHA512Node(client, cortexOptions(), new HashNodeOptions());
			NodeResult result = hashNode.process(NodeContext.create(media));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(sha512).sync().body();
			assertThat(reloaded.getHashes().getSHA512())
				.as("a file fetched from Google Drive must persist like any local file")
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
