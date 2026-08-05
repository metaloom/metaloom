package io.metaloom.loom.test.integration.node.docs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.option.CloudClientOptions;
import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudUri;
import io.metaloom.cortex.cloud.auth.GoogleServiceAccountTokenSource;
import io.metaloom.cortex.cloud.gdrive.GoogleDriveFileStore;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.source.cloud.CloudDifferentialScanner;
import io.metaloom.cortex.node.source.cloud.CloudFileIndexStore;
import io.metaloom.cortex.node.source.cloud.CloudSelection;
import io.metaloom.cortex.node.source.cloud.CloudSourceNode;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNode;
import io.metaloom.fs.FileState;
import io.metaloom.loom.test.integration.node.StubDriveApiServer;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonObject;

/**
 * The source nodes, which have to be driven differently from everything else.
 *
 * <p>
 * A source implements {@code process(media, inputs)} like any other node, but calling it on its own
 * only echoes the reference it was handed — a truthful picture of nothing. What a source actually
 * <em>does</em> is enumerate: it walks a filesystem, a bucket or a drive, decides which entries are
 * new or changed since the last pass, and emits one media item for each. So these recipes subscribe
 * to the real stream, take the first item, and run {@code process} over that — the payload on the
 * card is then a reference the node genuinely discovered.
 * </p>
 *
 * <p>
 * They also have no upstream, which is why they override it to empty: a source is where the graph
 * starts, and drawing something feeding it would be a lie about the model.
 * </p>
 */
public final class SourceRecipes {

	private SourceRecipes() {
	}

	/**
	 * The two cloud sources, against the in-repo Drive API stub.
	 *
	 * <p>
	 * These are the <strong>only</strong> two recipes allowed to report {@code backend: "stub"}, and
	 * the reason is narrow: what the stub replaces is Google or Microsoft, and nothing else. The
	 * token source really signs an RS256 assertion and exchanges it, the store really speaks Drive
	 * v3, the differential scanner really diffs a delta feed against its index, and the materializer
	 * really downloads. There is no version of this that runs against a live account on a build
	 * machine, and a picture of the parts we do own is worth more than no picture at all — provided
	 * the page says so, which is what the allowlist and the caption are for.
	 * </p>
	 */
	public static DocsFixtureRecipe driveSource(String kind, CloudProviderId provider) {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return kind;
			}

			@Override
			public Requirement requirement() {
				return Requirement.offline();
			}

			@Override
			public String backend() {
				return "stub";
			}

			@Override
			public List<Upstream> upstream() {
				return List.of();
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				try (StubDriveApiServer drive = new StubDriveApiServer()) {
					// Real files, put into the stub drive the way an account would hold them. A null
					// parent means "in the drive root", which is what the selection below scans.
					drive.putFile(null, env.video1().path().getFileName().toString(),
						Files.readAllBytes(env.inLibrary(env.video1().path())));
					drive.putFile(null, env.image1().path().getFileName().toString(),
						Files.readAllBytes(env.inLibrary(env.image1().path())));

					GDriveClientOptions options = new GDriveClientOptions()
						.setApiBaseUrl(drive.baseUrl())
						.setTokenUrl(drive.tokenUrl())
						.setServiceAccountJson(serviceAccountKey());
					CloudFileStore store = new GoogleDriveFileStore(options,
						new GoogleServiceAccountTokenSource(options, Clock.systemUTC()));
					Path index = Files.createTempDirectory("docs-fixture-" + kind + "-index");
					Path cache = Files.createTempDirectory("docs-fixture-" + kind + "-cache");
					CloudDifferentialScanner scanner = new CloudDifferentialScanner(store,
						new CloudFileIndexStore(), index, CloudClientOptions.DEFAULT_RECONCILE_INTERVAL_MS);
					CloudSourceNode node = new CloudSourceNode(kind, scanner,
						new CloudMediaMaterializer(store, cache, 0, 0),
						new CloudSelection(provider, DRIVE_ROOT, null, true, 0, Set.of(), Set.of(),
							Set.of(FileState.NEW, FileState.MODIFIED, FileState.MOVED), true, false, false));
					node.initialize();

					LoomMedia first = node.stream().blockingFirst();
					NodeResult result = node.process(first, NodeInputs.builder().build());
					return new Outcome(result, first.reference(),
						new JsonObject().put("driveId", DRIVE_ROOT).put("useDelta", true)
							.put("emitStates", "NEW, MODIFIED, MOVED"));
				}
			}
		};
	}

	private static final String DRIVE_ROOT = CloudUri.MY_DRIVE;

	/** A throwaway service-account key: the token source really signs with it. */
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

	public static DocsFixtureRecipe filesystemSource() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "filesystem-source";
			}

			@Override
			public Requirement requirement() {
				return Requirement.offline();
			}

			@Override
			public List<Upstream> upstream() {
				return List.of();
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				// A fresh index directory, so the scan reports NEW rather than "nothing has changed
				// since last time" — which is what a reader running this for the first time sees.
				Path index = Files.createTempDirectory("docs-fixture-fs-index");
				// The production loader is Dagger-wired (it resolves a subcomponent per file) and the
				// scan only needs "path in, media out". Subclassing it to call the same
				// `LoomMediaImpl` the rest of these recipes run on skips the injector without
				// substituting anything — the media object is the production one.
				LoomMediaLoader loader = new LoomMediaLoader(null) {
					@Override
					public LoomMedia load(Path path) {
						return new LoomMediaImpl(path);
					}
				};
				// The neutral library, not the build tree: this node's whole output is a path, and it
				// is drawn verbatim on the card.
				Path library = env.libraryRoot();
				env.inLibrary(env.video1().path());
				env.inLibrary(env.image1().path());
				FilesystemSourceNode node = new FilesystemSourceNode("filesystem-source", loader,
					library, List.of(), Set.of(FileState.NEW, FileState.MODIFIED), index);
				node.initialize();

				// The real scan, not a synthesised item: this walks the corpus, consults the
				// differential index and reports what it found. `process` then answers for the first
				// item it emitted, which is the payload the card shows.
				LoomMedia first = node.stream().blockingFirst();
				NodeResult result = node.process(first, NodeInputs.builder().build());
				return new Outcome(result, first.absolutePath(),
					new JsonObject().put("path", library.toString()).put("emitStates", "NEW, MODIFIED"));
			}
		};
	}
}
