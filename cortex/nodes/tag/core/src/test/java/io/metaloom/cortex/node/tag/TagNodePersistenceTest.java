package io.metaloom.cortex.node.tag;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompListResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.tag.AssetTagBulkRequest;
import io.metaloom.loom.rest.model.tag.AssetTagBulkResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What reaches Loom: one {@code tagAsset} call per applied tag, one {@code tags} component recording
 * what was applied, and one ledger row pointing at it.
 *
 * <p>
 * The component is not bookkeeping. {@code tag_asset} carries no provenance of its own, so that
 * record is the <em>only</em> evidence that a tag on an asset came from this node instance — and
 * therefore the only thing that makes withdrawing one safe. The tests that matter most here are the
 * ones asserting what is <strong>not</strong> deleted.
 * </p>
 */
class TagNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;

	private StubLoomMedia media;

	private CortexOptions cortexOptions;

	/** The component the node reads back as its own previous verdict; empty unless a test sets one. */
	private JsonCompListResponse existingComps;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);
		// No previous verdict by default. Note the list body carries a null data array in that case,
		// which is exactly what Loom returns for an asset with no components.
		existingComps = new JsonCompListResponse();

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		// The server resolves every name to its own shared row, keyed by name here so a test can assert
		// which uuid was withdrawn.
		when(client.bulkTagAsset(any(UUID.class), any(AssetTagBulkRequest.class))).thenAnswer(invocation -> {
			AssetTagBulkRequest request = invocation.getArgument(1);
			AssetTagBulkResponse body = new AssetTagBulkResponse();
			body.setTotal(request.getTags().size());
			body.setApplied(request.getTags().size());
			body.setWithdrawn(request.getWithdraw().size());
			for (TagCreateRequest tag : request.getTags()) {
				String collection = tag.getCollection() == null ? request.getCollection() : tag.getCollection();
				body.add((TagResponse) new TagResponse().setName(tag.getName()).setCollection(collection).setUuid(uuidOf(tag.getName())));
			}
			LoomClientRequest<AssetTagBulkResponse> bulkReq = mock(LoomClientRequest.class);
			when(bulkReq.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
			return bulkReq;
		});

		LoomClientRequest<JsonCompListResponse> listReq = mock(LoomClientRequest.class);
		when(listReq.sync()).thenAnswer(i -> new LoomClientResponseImpl<>(existingComps, 200, "OK", Map.of()));
		when(client.listAssetJsonComps(any())).thenReturn(listReq);

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(compUuid), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "asset.jpg", "some bytes");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	/** A stable uuid per tag name, so an assertion can name the tag it expects to have been removed. */
	private static UUID uuidOf(String name) {
		return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private TagNode node(JsonObject nodeDef) {
		Provider<TagStrategy> rules = RulesTagStrategy::new;
		Provider<TagStrategy> labels = LabelsTagStrategy::new;
		TagNode node = new TagNode(client, cortexOptions, new TagNodeOptions(), Map.of(TagBy.RULES, rules, TagBy.LABELS, labels));
		node.configure(nodeDef);
		return node;
	}

	/** A node whose single rule fires whenever the text port carries anything. */
	private static JsonObject nodeDef(String id, String tag) {
		return new JsonObject()
			.put("id", id)
			.put("collection", "quality")
			.put("rules", new JsonArray().add(new JsonObject()
				.put("id", "r-" + tag)
				.put("tag", tag)
				.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "NOT_BLANK")))));
	}

	private NodeResult run(TagNode node) {
		NodeContext<LoomMedia> ctx = NodeContext.create(media, NodeInputs.builder().input(TagNode.IN_TEXT, "some text").build());
		return node.process(ctx);
	}

	/** A previous verdict of this node instance, as it would have been read back from Loom. */
	private void previousVerdict(String variant, String... tags) {
		JsonArray applied = new JsonArray();
		for (String tag : tags) {
			applied.add(new JsonObject()
				.put("tag", tag)
				.put("collection", "quality")
				.put("ruleId", "r-" + tag)
				.put("confidence", 1.0)
				.put("uuid", uuidOf(tag).toString()));
		}
		JsonCompResponse comp = new JsonCompResponse();
		comp.setNodeKind("tag");
		comp.setSchemaType(TagNode.SCHEMA_TYPE);
		comp.setVariant(variant);
		comp.setData(new JsonObject().put("applied", applied));
		existingComps = new JsonCompListResponse();
		existingComps.add(comp);
	}

	@Test
	void testAttachesTheTagAndRecordsTheComponentAndLedgerRow() {
		assertThat(run(node(nodeDef("quality-tags", "blurry")))).isSuccess();

		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getTags().size() == 1
			&& "blurry".equals(r.getTags().get(0).getName())
			&& "quality".equals(r.getTags().get(0).getCollection())
			&& r.getWithdraw().isEmpty()));

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "tag".equals(r.getNodeKind())
			&& TagNode.SCHEMA_TYPE.equals(r.getSchemaType())
			// The pipeline node id, so two tag instances coexist on one asset - and so each reads back
			// its own previous verdict rather than the other's.
			&& "quality-tags".equals(r.getVariant())
			&& "blurry".equals(r.getData().getJsonArray("applied").getJsonObject(0).getString("tag"))
			// The uuid of the shared tag row, which is what a later withdrawal needs.
			&& uuidOf("blurry").toString().equals(r.getData().getJsonArray("applied").getJsonObject(0).getString("uuid"))));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "tag".equals(r.getNodeKind())
			&& "tag:quality-tags".equals(r.getNodeId())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			&& r.getResultRef() != null));
	}

	/** A cache hit must not re-attach anything: the durable copy is already in Loom. */
	@Test
	void testASecondRunIsServedFromTheCache() {
		TagNode node = node(nodeDef("quality-tags", "blurry"));

		assertThat(run(node)).isSuccess();
		assertThat(run(node)).isSuccess();

		verify(client, times(1)).bulkTagAsset(any(UUID.class), any(AssetTagBulkRequest.class));
		verify(client, times(1)).createAssetJsonComp(any(), any());
	}

	/** Dry run computes and records the verdict but attaches nothing - the way to try a rule set out. */
	@Test
	void testDryRunWritesNoTagButStillRecords() {
		assertThat(run(node(nodeDef("quality-tags", "blurry").put("dryRun", true)))).isSuccess();

		verify(client, never()).bulkTagAsset(any(UUID.class), any(AssetTagBulkRequest.class));
		verify(client).createAssetJsonComp(eq(assetUuid),
			argThat((JsonCompCreateRequest r) -> Boolean.TRUE.equals(r.getData().getBoolean("dryRun"))
				&& r.getData().getJsonArray("applied").size() == 1));
	}

	/**
	 * Reconciliation withdraws a tag this node applied on an earlier run and no longer stands behind -
	 * and only that one.
	 */
	@Test
	void testWithdrawsATagItPreviouslyApplied() {
		previousVerdict("quality-tags", "sharp");

		NodeResult result = run(node(nodeDef("quality-tags", "blurry").put("removeWithdrawn", true)));

		assertThat(result).isSuccess();
		// The attachment and the withdrawal travel in one request, so the item cannot end up carrying the
		// new tag and the old one at once.
		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getWithdraw().equals(List.of(uuidOf("sharp")))
			&& r.getTags().size() == 1
			&& "blurry".equals(r.getTags().get(0).getName())));
		JsonObject record = new JsonObject((String) result.get(TagNode.OUT_APPLIED));
		assertEquals("sharp", record.getJsonArray("withdrawn").getJsonObject(0).getString("tag"));
	}

	/**
	 * The node says who it is on every write. Since V2.71 the join row keeps that, which is what lets a
	 * UI separate machine tags from curated ones - and what makes withdrawal safe, because the server
	 * then removes only placements carrying this node id.
	 */
	@Test
	void testDeclaresItsOwnProvenance() {
		TagNode node = node(nodeDef("quality-tags", "blurry"));
		assertThat(run(node)).isSuccess();

		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> "tag".equals(r.getNodeKind())
			// Scoped per pipeline node id, so two tag nodes in one graph own their placements separately.
			&& "tag:quality-tags".equals(r.getNodeId())
			&& r.getProducerVersion() != null && r.getProducerVersion().startsWith("tag/1:")
			&& r.getTags().get(0).getConfidence() != null));
	}

	/** An item that matches no rule and has nothing to take back is not worth a request at all. */
	@Test
	void testWritesNothingWhenThereIsNothingToWrite() {
		JsonObject def = nodeDef("quality-tags", "blurry");
		// A rule that cannot fire: the flag port is not wired in this test.
		def.getJsonArray("rules").getJsonObject(0).put("when",
			new JsonArray().add(new JsonObject().put("input", "flag").put("op", "EQ").put("value", true)));

		assertThat(run(node(def))).isSuccess();

		verify(client, never()).bulkTagAsset(any(UUID.class), any(AssetTagBulkRequest.class));
		// The verdict is still recorded: "nothing matched" is an answer, and the ledger row is what keeps
		// the item from being re-evaluated on the next run.
		verify(client).createAssetJsonComp(any(), any());
	}

	/** A tag that is still desired stays put; withdrawing and re-adding it would churn the search index. */
	@Test
	void testDoesNotWithdrawATagItStillStandsBehind() {
		previousVerdict("quality-tags", "blurry");

		assertThat(run(node(nodeDef("quality-tags", "blurry").put("removeWithdrawn", true)))).isSuccess();

		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getWithdraw().isEmpty()));
	}

	/**
	 * 🔴 The safety property. A tag that is not in this instance's previous applied list is never
	 * touched, whoever put it there - a person, or another tag node in the same graph. A bug here
	 * silently destroys human curation, so it is asserted from both directions.
	 */
	@Test
	void testNeverWithdrawsATagItCannotProveItWrote() {
		// The previous verdict belongs to a different node instance...
		previousVerdict("other-tag-node", "sharp");
		assertThat(run(node(nodeDef("quality-tags", "blurry").put("removeWithdrawn", true)))).isSuccess();
		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getWithdraw().isEmpty()));
	}

	/** ...and a tag outside the collection this instance writes is equally off limits. */
	@Test
	void testNeverWithdrawsOutsideItsOwnCollection() {
		JsonArray applied = new JsonArray().add(new JsonObject()
			.put("tag", "holiday")
			.put("collection", "people")
			.put("uuid", uuidOf("holiday").toString()));
		JsonCompResponse comp = new JsonCompResponse();
		comp.setSchemaType(TagNode.SCHEMA_TYPE);
		comp.setVariant("quality-tags");
		comp.setData(new JsonObject().put("applied", applied));
		existingComps = new JsonCompListResponse();
		existingComps.add(comp);

		assertThat(run(node(nodeDef("quality-tags", "blurry").put("removeWithdrawn", true)))).isSuccess();

		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getWithdraw().isEmpty()));
	}

	/** Without {@code removeWithdrawn} nothing is deleted, and the read-back is not even requested. */
	@Test
	void testWithdrawalIsOffByDefault() {
		previousVerdict("quality-tags", "sharp");

		assertThat(run(node(nodeDef("quality-tags", "blurry")))).isSuccess();

		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getWithdraw().isEmpty()));
		verify(client, never()).listAssetJsonComps(any());
	}

	/**
	 * A rejected write is a failure, not a skip: the worker could not do the job it was given. It has
	 * to leave a FAILED ledger row, because reporting success for a run that tagged nothing is how a
	 * broken token looks like a library that simply matched no rules.
	 */
	@Test
	void testARejectedWriteFailsTheNodeAndRecordsAFailedRow() {
		when(client.bulkTagAsset(any(UUID.class), any(AssetTagBulkRequest.class))).thenThrow(new RuntimeException("403 Forbidden"));

		NodeResult result = run(node(nodeDef("quality-tags", "blurry")));

		assertThat(result).isFailed();
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && r.getResultRef() == null));
		verify(client, never()).createAssetJsonComp(any(), any());
	}

	/**
	 * If the previous verdict cannot be read there is no proof of anything, so nothing is withdrawn.
	 * Failing closed is the only safe direction when the alternative is deleting someone else's tags.
	 */
	@Test
	void testWithdrawsNothingWhenTheReadBackFails() {
		previousVerdict("quality-tags", "sharp");
		when(client.listAssetJsonComps(any())).thenThrow(new RuntimeException("connection refused"));

		assertThat(run(node(nodeDef("quality-tags", "blurry").put("removeWithdrawn", true)))).isSuccess();

		verify(client).bulkTagAsset(eq(assetUuid), argThat((AssetTagBulkRequest r) -> r.getWithdraw().isEmpty()));
	}
}
