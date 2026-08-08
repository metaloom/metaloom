package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
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
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Routing on the tags a person (or a node) put on an asset.
 *
 * <p>
 * Tags ride along on the {@code AssetResponse} the node already loads, so unlike
 * {@link RatingFilterNodeTest} this needs a client only to have an asset at all — there is no extra
 * round trip to stub.
 * </p>
 */
class TagFilterNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private LoomHttpClient client;
	private StubLoomMedia media;
	private CortexOptions cortexOptions;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "asset.txt", "some bytes");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private static TagReference tag(String name) {
		return (TagReference) new TagReference().setName(name).setUuid(UUID.randomUUID());
	}

	private static TagReference machineTag(String name, String nodeKind) {
		TagReference reference = tag(name);
		reference.setNodeKind(nodeKind);
		return reference;
	}

	@SuppressWarnings("unchecked")
	private void tags(TagReference... tags) throws Exception {
		AssetResponse asset = new AssetResponse().setUuid(UUID.randomUUID());
		asset.setTags(List.of(tags));
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);
	}

	private FilterNode node(JsonObject nodeDef) {
		Provider<FilterStrategy> strategy = TagFilterStrategy::new;
		FilterNode node = new FilterNode(client, cortexOptions, new FilterNodeOptions(), Map.of(FilterBy.TAG, strategy));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject nodeDef() {
		return new JsonObject().put("id", "by-tag").put("filterBy", "TAG")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "publish").put("match", "hero"))
				.add(new JsonObject().put("id", "faces").put("match", "person/*")));
	}

	private NodeResult run(FilterNode node) {
		return node.process(NodeContext.create((LoomMedia) media, NodeInputs.empty()));
	}

	@Test
	void testAnExactTagNameTakesItsBucket() throws Exception {
		tags(tag("hero"), tag("landscape"));

		assertThat(run(node(nodeDef())))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("publish"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("faces"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE);
	}

	@Test
	void testAPrefixGlobMatchesAWholeNamespace() throws Exception {
		tags(tag("person/ada"));

		assertThat(run(node(nodeDef()))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "faces");
	}

	@Test
	void testAnAssetWithNoMatchingTagGoesToOther() throws Exception {
		tags(tag("landscape"));

		assertThat(run(node(nodeDef())))
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasOutput(FilterNode.OUT_PASSED, Boolean.FALSE);
	}

	/** Like MIME, a bucket with no hint routes by its own id — the author should not type 'hero' twice. */
	@Test
	void testABucketWithNoMatchFallsBackToItsId() throws Exception {
		tags(tag("hero"));

		JsonObject def = nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "hero")));

		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "hero");
	}

	@Test
	void testUntaggedMatchesOnlyAnAssetWithNoTags() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "todo").put("match", "untagged")));

		tags();
		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "todo");

		tags(tag("hero"));
		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	/**
	 * {@code hero, !archive} means "hero but not archive". Reading the veto as another alternative
	 * would make the bucket match every archived asset too — the exact opposite of what it says.
	 */
	@Test
	void testANegatedHintVetoesABucketThatOtherwiseMatched() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "publish").put("match", "hero, !archive")));

		tags(tag("hero"));
		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "publish");

		tags(tag("hero"), tag("archive"));
		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	/** A bucket that is only a veto matches whenever the veto does not fire — the "not yet reviewed" branch. */
	@Test
	void testABucketOfOnlyNegationsMatchesWhenNoneApply() throws Exception {
		JsonObject def = nodeDef().put("buckets", new JsonArray()
			.add(new JsonObject().put("id", "unreviewed").put("match", "!reviewed")));

		tags(tag("landscape"));
		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "unreviewed");

		tags(tag("reviewed"));
		assertThat(run(node(def))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	@Test
	void testTagSourceManualIgnoresWhatANodeAttached() throws Exception {
		tags(machineTag("hero", "tag"));

		JsonObject def = nodeDef().put("tagSource", "MANUAL");

		assertThat(run(node(def)))
			.isSuccess()
			.hasOutput(FilterNode.OUT_BUCKET, "other")
			.hasNoOutput(FilterNode.bucketPort("publish"));
	}

	@Test
	void testTagSourceMachineIgnoresWhatAPersonAttached() throws Exception {
		tags(tag("hero"));

		assertThat(run(node(nodeDef().put("tagSource", "MACHINE")))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	/**
	 * An absent {@code nodeKind} counts as a person's tag: the column defaults to {@code 'manual'}
	 * deliberately, because a machine tag mislabelled human is merely not filtered out, while a human
	 * tag mislabelled machine could be deleted by a reconciling node.
	 */
	@Test
	void testATagWithNoNodeKindCountsAsManual() throws Exception {
		tags(tag("hero"));

		assertThat(run(node(nodeDef().put("tagSource", "MANUAL")))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "publish");
	}

	@Test
	void testTheTagSourceIsPartOfTheConfigHash() {
		// Otherwise two nodes differing only in tag source would share a result-cache entry, and the
		// second would re-emit the first's verdict for every item they both saw.
		String any = node(nodeDef()).producerVersion();
		String manual = node(nodeDef().put("tagSource", "MANUAL")).producerVersion();

		assertTrue(any.startsWith("filter/1:TAG:"), any);
		assertFalse(any.equals(manual), "a different tag source must produce a different producerVersion");
	}

	@Test
	void testABareNegationIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "publish").put("match", "!")))));

		assertTrue(e.getMessage().contains("negates nothing"), e.getMessage());
	}

	// ── The combination rule, directly ────────────────────────────────────

	@Test
	void testTheMatchRule() {
		assertTrue(TagFilterStrategy.matches(List.of("hero"), List.of("hero")));
		assertFalse(TagFilterStrategy.matches(List.of("hero"), List.of("archive")));
		assertTrue(TagFilterStrategy.matches(List.of("person/ada"), List.of("person/*")));
		assertTrue(TagFilterStrategy.matches(List.of("anything"), List.of("*")), "a bare star is 'has any tag'");
		assertFalse(TagFilterStrategy.matches(List.of(), List.of("*")));

		// One veto beats any number of positive matches.
		assertFalse(TagFilterStrategy.matches(List.of("hero", "archive"), List.of("hero", "!archive")));
		assertTrue(TagFilterStrategy.matches(List.of("hero"), List.of("hero", "!archive")));
	}
}
