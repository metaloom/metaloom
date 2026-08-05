package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.tag.LabelsTagStrategy;
import io.metaloom.cortex.node.tag.RulesTagStrategy;
import io.metaloom.cortex.node.tag.TagBy;
import io.metaloom.cortex.node.tag.TagNode;
import io.metaloom.cortex.node.tag.TagNodeOptions;
import io.metaloom.cortex.node.tag.TagStrategy;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.search.SearchResultResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code TagNode} against a real Loom server.
 *
 * <p>
 * The node is the one place where Cortex writes into the catalog's shared vocabulary rather than into a component table of its own, and almost
 * everything that can go wrong there only goes wrong against a real server: the natural key of {@code tag}, the idempotence of the join, the
 * search trigger, and the permission set of the bulk route. The unit tests mock the client and therefore cannot see any of it.
 * </p>
 *
 * <p>
 * The last test is the important one. Reconciliation deletes rows in a namespace every user of the instance shares, and the only proof the node has
 * that a tag is its own is the component it wrote on a previous run. Here that proof is exercised end to end - written by one run, read back by the
 * next, with a hand-typed tag sitting next to it that must survive.
 * </p>
 */
public class TagNodeIntegrationTest extends AbstractNodeIntegrationTest {

	/** A node whose single rule fires when the text port carries anything. */
	private static JsonObject nodeDef(String id, String tag) {
		return new JsonObject()
			.put("id", id)
			.put("collection", "it-quality")
			.put("rules", new JsonArray().add(new JsonObject()
				.put("id", "r-" + tag)
				.put("tag", tag)
				.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "NOT_BLANK")))));
	}

	private TagNode node(LoomHttpClient client, JsonObject nodeDef) {
		Provider<TagStrategy> rules = RulesTagStrategy::new;
		Provider<TagStrategy> labels = LabelsTagStrategy::new;
		TagNode node = new TagNode(client, cortexOptions(), new TagNodeOptions(),
			Map.of(TagBy.RULES, rules, TagBy.LABELS, labels));
		node.configure(nodeDef);
		return node;
	}

	private NodeResult run(TagNode node) {
		NodeContext<LoomMedia> ctx = NodeContext.create(media(image1()),
			NodeInputs.builder().input(TagNode.IN_TEXT, "a sentence to match on").build());
		return node.process(ctx);
	}

	/**
	 * The whole point of the node: a computed verdict becomes a tag on the asset, and the tag is findable. Everything else it writes is bookkeeping
	 * around that one fact.
	 */
	@Test
	public void testTagIsAttachedAndSearchable() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			assertThat(run(node(client, nodeDef("it-tags", "it-blurry"))).getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse tagged = client.loadAsset(asset.getUuid()).sync().body();
			assertThat(tagged.getTags()).as("the tag must be attached to the asset")
				.anyMatch(t -> "it-blurry".equals(t.getName()));

			// The reason a tag is worth writing at all: a database trigger folds tag_asset into
			// search_document.tag_names as the row is written, so no indexing step stands between
			// tagging and finding.
			SearchResultResponse hits = client.search("it-blurry").sync().body();
			assertThat(hits.getData()).as("the tag must be searchable straight away")
				.anyMatch(hit -> asset.getUuid().equals(hit.getAssetUuid()) || asset.getUuid().equals(hit.getUuid()));

			// The placement says a machine wrote it, which is what a UI needs to tell these apart from
			// curated tags - and what scopes the node's own withdrawals to its own work.
			var placement = tagged.getTags().stream()
				.filter(t -> "it-blurry".equals(t.getName()))
				.findFirst().orElseThrow();
			assertThat(placement.getNodeKind()).as("the placement must name the writer").isEqualTo("tag");
			assertThat(placement.getNodeId()).isEqualTo("tag:it-tags");
			assertThat(placement.getPlacementUuid()).as("and carry its own identity").isNotNull();

			// ...and the node's own record of what it applied, which is what makes withdrawal safe later.
			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> TagNode.SCHEMA_TYPE.equals(c.getSchemaType()) && "it-tags".equals(c.getVariant()))
				.findFirst().orElse(null);
			assertThat(comp).as("the tags component must be readable via REST").isNotNull();
			JsonObject applied = comp.getData().getJsonArray("applied").getJsonObject(0);
			assertThat(applied.getString("tag")).isEqualTo("it-blurry");
			assertThat(applied.getString("uuid")).as("the uuid of the shared tag row - a later run can only withdraw what it recorded")
				.isNotNull();
		});
	}

	/**
	 * Tags are global, so a second asset receiving a name that already exists must land on the same row. This is the defect that blocked the node
	 * before {@code resolveOrCreateAssetTag} existed, and only a real database can show that it is gone.
	 */
	@Test
	public void testTwoAssetsShareOneTagRow() throws Exception {
		withLoom(client -> {
			AssetResponse first = getOrCreateAsset(client, image1(), "image/jpeg");
			// A second, collision-free asset: the fixture ships one image, and one asset cannot show that a
			// tag row is shared.
			UniqueAsset second = createUniqueMediaAsset(client, image1(), "image/jpeg", "tag-node-it");

			assertThat(run(node(client, nodeDef("it-shared", "it-shared-tag"))).getState()).isEqualTo(ResultState.SUCCESS);

			// The same node configuration over the second asset's media.
			TagNode node = node(client, nodeDef("it-shared", "it-shared-tag"));
			NodeResult result = node.process(NodeContext.create(second.media(),
				NodeInputs.builder().input(TagNode.IN_TEXT, "another sentence").build()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			UUID firstTag = tagUuid(client, first.getUuid(), "it-shared-tag");
			UUID secondTag = tagUuid(client, second.asset().getUuid(), "it-shared-tag");
			assertThat(firstTag).as("both assets must carry the same, shared tag row").isEqualTo(secondTag);

			assertThat(client.listTags().sync().body().getData().stream()
				.filter(t -> "it-shared-tag".equals(t.getName()) && "it-quality".equals(t.getCollection()))
				.count()).as("only one tag may exist for one (name, collection)").isEqualTo(1);
		});
	}

	/**
	 * 🔴 The safety property, end to end: a run withdraws the tag it applied before and no longer stands behind, and leaves a hand-typed tag on the
	 * same asset alone. The proof of ownership travels through the component written by the earlier run, and the removal goes through the bulk route
	 * which deletes exactly the uuids it is given.
	 */
	@Test
	public void testWithdrawsItsOwnTagAndSparesAHumanOne() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			// A person tags the asset, in the same collection the node writes into - the hardest case for
			// the node to get right, because the collection alone cannot tell the two apart.
			TagResponse curated = client.tagAsset(asset.getUuid(),
				new TagCreateRequest().setName("it-hand-typed").setCollection("it-quality")).sync().body();

			// First run: the node applies its verdict.
			assertThat(run(node(client, nodeDef("it-reconcile", "it-stale"))).getState()).isEqualTo(ResultState.SUCCESS);
			UUID stale = tagUuid(client, asset.getUuid(), "it-stale");
			assertThat(stale).as("the first run must have attached its tag").isNotNull();

			// Second run: the rules changed, and the node no longer stands behind the old answer.
			NodeResult second = run(node(client, nodeDef("it-reconcile", "it-fresh").put("removeWithdrawn", true)));
			assertThat(second.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse after = client.loadAsset(asset.getUuid()).sync().body();
			assertThat(after.getTags()).as("the node's own stale tag must be withdrawn")
				.noneMatch(t -> t.getUuid().equals(stale));
			assertThat(after.getTags()).as("the new verdict must be attached")
				.anyMatch(t -> "it-fresh".equals(t.getName()));
			assertThat(after.getTags()).as("a tag the node did not write must survive, collection or not")
				.anyMatch(t -> t.getUuid().equals(curated.getUuid()));

			// Withdrawing detaches; the tag itself is a global object others may carry.
			assertThat(client.loadTag(stale).sync().body()).as("the withdrawn tag must still exist").isNotNull();
		});
	}

	private UUID tagUuid(LoomHttpClient client, UUID assetUuid, String name) throws Exception {
		return client.loadAsset(assetUuid).sync().body().getTags().stream()
			.filter(t -> name.equals(t.getName()))
			.map(t -> t.getUuid())
			.findFirst().orElse(null);
	}
}
