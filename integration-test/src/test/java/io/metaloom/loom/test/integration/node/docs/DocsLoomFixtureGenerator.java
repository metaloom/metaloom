package io.metaloom.loom.test.integration.node.docs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.dedup.DedupNodeOptions;
import io.metaloom.cortex.node.dedup.HashDedupNode;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.test.integration.node.AbstractNodeIntegrationTest;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonObject;

/**
 * The documentation fixture for the one node family that cannot run without a Loom server.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>
 * {@link DocsFixtureGenerator} deliberately constructs every node with a {@code null} client, which
 * makes it runnable by anyone with a checkout — no database, no server, no pooled test provider.
 * Dedup cannot join that: {@code HashDedupNode.compute} opens with
 * {@code if (isOfflineMode()) return ctx.skipped("offline mode")}, because the question it exists to
 * answer — "have I seen this hash before?" — is a database query. Folding it in would have added a
 * database dependency to thirty recipes that do not need one, so it lives here and pays for the
 * pool on its own.
 * </p>
 *
 * <pre>
 * ./setup-pool.sh    # once, and after any Flyway change
 * mvn -o -pl integration-test test -Dtest=DocsLoomFixtureGenerator -Dloom.regenerateDocsFixtures=true
 * </pre>
 *
 * <h2>What the picture ends up showing</h2>
 *
 * <p>
 * Almost nothing, and that is the honest answer. These nodes declare <strong>no output ports</strong>:
 * a successful dedup moves a file and writes a ledger row, and the {@code NodeResult} that comes back
 * carries no payload at all — {@code ctx.info(...)} sets a field that {@code next()} never reads. So
 * the node card has nothing to draw, and the debug view for this page is the run detail's Results
 * tab, which at least states the node ran, how long it took, and that it produced no outputs.
 * </p>
 */
public class DocsLoomFixtureGenerator extends AbstractNodeIntegrationTest {

	private static final String REGENERATE = "loom.regenerateDocsFixtures";

	private static final Path OUT = Path.of("..", "loom-ui", "scripts", "fixtures", "nodes");

	@Test
	public void generateDedupFixture() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE),
			"Set -D" + REGENERATE + "=true to regenerate the node documentation fixtures");

		FixtureEnv env = new FixtureEnv();
		DocsFixtureWriter writer = new DocsFixtureWriter(OUT, DocsLoomFixtureGenerator.class.getName());

		withLoom(client -> {
			Outcome outcome = runDedup(client, env);
			writer.write(RECIPE, outcome.result(), outcome.mediaPath(), outcome.nodeData());
			System.out.println("  hash-dedup: " + outcome.result().getState()
				+ ", " + outcome.result().getOutputs().size() + " ports");
		});
	}

	/**
	 * A genuine duplicate, made the way one actually appears: the same bytes at a second path.
	 *
	 * <p>
	 * The asset is created in Loom for the <em>original</em> and keyed by its real SHA-512, with its
	 * real path recorded as the file's name. The node is then run over the copy. It hashes the copy,
	 * finds the asset, sees that the path on record still exists and is a different file, re-hashes
	 * that one to be sure, and only then moves the copy aside. Nothing about that is arranged — the
	 * only thing this recipe does is put the same bytes in two places, which is what a duplicate is.
	 * </p>
	 */
	private Outcome runDedup(LoomHttpClient client, FixtureEnv env) throws Exception {
		Path original = env.inLibrary(image1().path());
		AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");
		// `getOrCreateAsset` records the bare file name, and the node does
		// `new File(asset.getFile().getFilename()).exists()` — which a bare name never satisfies, so
		// without this the run stops at "Source from db not found for currentpath". The asset has to
		// say where the original actually is.
		client.updateAsset(asset.getUuid(),
			new AssetUpdateRequest().setFile(asset.getFile().setFilename(original.toString()))).sync();

		Path copy = env.libraryRoot().resolve("duplicates-in/" + original.getFileName());
		Files.createDirectories(copy.getParent());
		Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);

		Path dupFolder = Files.createTempDirectory("docs-fixture-dedup-dups");
		DedupNodeOptions options = new DedupNodeOptions();
		options.setDupFolder(dupFolder);
		LoomMediaLoader loader = new LoomMediaLoader(null) {
			@Override
			public LoomMedia load(Path path) {
				return new LoomMediaImpl(path);
			}
		};
		HashDedupNode node = new HashDedupNode(client, env.cortexOptions("dedup"), options, loader);
		node.initialize();

		LoomMedia media = new LoomMediaImpl(copy);
		NodeResult result = node.process(NodeContext.create(media));

		// This node has no output port to check, and every one of its early exits also returns
		// SUCCESS with nothing — "the asset has no path on record", "that path no longer exists",
		// "it is the same file". So the fixture is verified against the only thing a real dedup
		// leaves behind: the duplicate is gone from where it was and is now in the quarantine
		// folder. Without this the page could show a green card for a node that did nothing.
		if (Files.exists(copy)) {
			throw new IllegalStateException("the duplicate was not moved — the node found no match. "
				+ "Check that the asset's file.filename is the original's absolute path");
		}
		if (Files.list(dupFolder).findAny().isEmpty()) {
			throw new IllegalStateException("nothing landed in the duplicates folder " + dupFolder);
		}

		return new Outcome(result, copy.toString(),
			new JsonObject().put("dupFolder", "duplicates").put("dryRun", false));
	}

	/** The recipe metadata the writer records; the run itself is above, because it needs a client. */
	private static final DocsFixtureRecipe RECIPE = new DocsFixtureRecipe() {
		@Override
		public String kind() {
			return "hash-dedup";
		}

		@Override
		public Requirement requirement() {
			return Requirement.of(true, "loom server (pooled test database)",
				"run ./setup-pool.sh first — this is the one recipe that needs a database");
		}

		@Override
		public boolean emitsNoPorts() {
			return true;
		}

		@Override
		public List<Upstream> upstream() {
			return List.of(new Upstream("sha512", "sha512", "hash"));
		}

		@Override
		public Outcome run(FixtureEnv env) {
			throw new UnsupportedOperationException("driven by DocsLoomFixtureGenerator, which owns the client");
		}
	};
}
