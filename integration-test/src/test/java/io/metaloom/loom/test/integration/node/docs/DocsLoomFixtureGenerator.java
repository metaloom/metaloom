package io.metaloom.loom.test.integration.node.docs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.dedup.DedupNodeOptions;
import io.metaloom.cortex.node.dedup.HashDedupNode;
import io.metaloom.cortex.node.relocate.AssignDestination;
import io.metaloom.cortex.node.relocate.AssignNode;
import io.metaloom.cortex.node.relocate.AssignNodeOptions;
import io.metaloom.cortex.node.relocate.AssignTarget;
import io.metaloom.cortex.node.relocate.CollectionAssignment;
import io.metaloom.cortex.node.relocate.LibraryAssignment;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.test.integration.node.AbstractNodeIntegrationTest;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonObject;

/**
 * The documentation fixtures for the nodes that cannot run without a Loom server.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>
 * {@link DocsFixtureGenerator} deliberately constructs every node with a {@code null} client, which
 * makes it runnable by anyone with a checkout — no database, no server, no pooled test provider.
 * Two kinds cannot join that. {@code HashDedupNode.compute} opens with
 * {@code if (isOfflineMode()) return ctx.skipped("offline mode")}, because the question it exists to
 * answer — "have I seen this hash before?" — is a database query; and {@code AssignNode} writes a
 * membership row, which is the whole of what it does. Folding either in would have added a database
 * dependency to thirty recipes that do not need one, so they live here and pay for the pool on their
 * own.
 * </p>
 *
 * <pre>
 * ./setup-pool.sh    # once, and after any Flyway change
 * mvn -o -pl integration-test test -Dtest=DocsLoomFixtureGenerator -Dloom.regenerateDocsFixtures=true
 * </pre>
 *
 * <h2>What the pictures end up showing</h2>
 *
 * <p>
 * {@code assign} emits the membership it wrote and the collection it wrote it into, so its card is an
 * ordinary one. {@code hash-dedup} used to emit nothing at all — it moved a file and wrote a ledger
 * row, and the {@code NodeResult} carried no payload, because {@code ctx.info(...)} sets a field that
 * {@code next()} never reads. It now reports the duplicate and its original on two ports and leaves
 * the relocating to a {@code move} node, so it has a card worth drawing; the page is still captured
 * from the run detail's Results tab, which is a choice worth revisiting.
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

	@Test
	public void generateAssignFixture() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE),
			"Set -D" + REGENERATE + "=true to regenerate the node documentation fixtures");

		FixtureEnv env = new FixtureEnv();
		DocsFixtureWriter writer = new DocsFixtureWriter(OUT, DocsLoomFixtureGenerator.class.getName());

		withLoom(client -> {
			Outcome outcome = runAssign(client, env);
			writer.write(ASSIGN_RECIPE, outcome.result(), outcome.mediaPath(), outcome.nodeData());
		});
	}

	/**
	 * Filing a photograph into a collection, named the way a curation pipeline names one.
	 *
	 * <p>
	 * By name with {@code onMissing=CREATE} rather than by uuid, because that is how the node is
	 * actually configured — a uuid on the card would be a uuid out of this test database, meaningful
	 * to nobody. Nothing about the file is touched, which is the node's entire point, so the recipe
	 * asserts that afterwards as well as asserting the membership exists.
	 * </p>
	 */
	private Outcome runAssign(LoomHttpClient client, FixtureEnv env) throws Exception {
		Path file = env.inLibrary(image1().path());
		byte[] before = Files.readAllBytes(file);
		AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

		JsonObject nodeDef = new JsonObject()
			.put("id", "assign")
			.put("target", "COLLECTION")
			.put("collectionName", "Selects")
			.put("onMissing", "CREATE");

		AssignNode node = new AssignNode(client, env.cortexOptions("assign"), new AssignNodeOptions(),
			Map.of(AssignTarget.COLLECTION, (Provider<AssignDestination>) CollectionAssignment::new,
				AssignTarget.LIBRARY, (Provider<AssignDestination>) LibraryAssignment::new));
		node.configure(nodeDef);
		node.initialize();

		NodeResult result = node.process(NodeContext.create(new LoomMediaImpl(file)));

		// The membership is the only thing this node leaves behind, and `assigned=false` on a second
		// run against a warm database is a perfectly ordinary SKIPPED — which the writer would then
		// reject with a message about the node, not about the database. Say which it was here.
		boolean member = client.listAssetCollections(asset.getUuid()).sync().body().getData().stream()
			.anyMatch(c -> "Selects".equals(c.getName()));
		if (!member) {
			throw new IllegalStateException("the asset is not in the 'Selects' collection — the node "
				+ "resolved or created nothing. Check that the admin token holds CREATE_COLLECTION");
		}
		if (!Arrays.equals(before, Files.readAllBytes(file))) {
			throw new IllegalStateException("the source file changed. This node must never touch a file");
		}

		return new Outcome(result, file.toString(), nodeDef);
	}

	/** The recipe metadata for {@code assign}; the run is above, because it needs a client. */
	private static final DocsFixtureRecipe ASSIGN_RECIPE = new DocsFixtureRecipe() {
		@Override
		public String kind() {
			return "assign";
		}

		@Override
		public Requirement requirement() {
			return Requirement.of(true, "loom server (pooled test database)",
				"run ./setup-pool.sh first — a membership is a row, so this one needs a database");
		}

		@Override
		public Outcome run(FixtureEnv env) {
			throw new UnsupportedOperationException("driven by DocsLoomFixtureGenerator, which owns the client");
		}
	};

	/**
	 * A genuine duplicate, made the way one actually appears: the same bytes at a second path.
	 *
	 * <p>
	 * The asset is created in Loom for the <em>original</em> and keyed by its real SHA-512, with its
	 * real path recorded as the file's name. The node is then run over the copy. It hashes the copy,
	 * finds the asset, sees that the path on record still exists and is a different file, and re-hashes
	 * that one to be sure before reporting the pair. Nothing about that is arranged — the only thing
	 * this recipe does is put the same bytes in two places, which is what a duplicate is.
	 * </p>
	 *
	 * <p>
	 * 🔴 The node no longer <em>moves</em> anything. It emits the duplicate and the copy Loom already
	 * knew about on two ports and leaves the relocating to a downstream {@code move} node, so both
	 * files are still where they were when this returns.
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

		DedupNodeOptions options = new DedupNodeOptions();
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

		// Every one of this node's early exits also returns SUCCESS — "the asset has no path on
		// record", "that path no longer exists", "it is the same file" — and they are silent rather
		// than green-with-a-finding. So the fixture is verified against the finding itself: the
		// duplicate port has to name the copy. Without this the page could show a green card for a
		// node that matched nothing.
		if (!result.getOutputs().containsKey(HashDedupNode.OUT_DUPLICATE.id())) {
			throw new IllegalStateException("the node reported no duplicate — it found no match. "
				+ "Check that the asset's file.filename is the original's absolute path");
		}
		// Both files stay put: this node reports, a downstream move node relocates.
		if (!Files.exists(copy) || !Files.exists(original)) {
			throw new IllegalStateException("a file moved. hash-dedup reports duplicates, it does not relocate them");
		}

		return new Outcome(result, copy.toString(), new JsonObject().put("id", "dedup"));
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
		public List<Upstream> upstream() {
			return List.of(new Upstream("sha512", "sha512", "hash"));
		}

		@Override
		public Outcome run(FixtureEnv env) {
			throw new UnsupportedOperationException("driven by DocsLoomFixtureGenerator, which owns the client");
		}
	};
}
