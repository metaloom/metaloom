package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Routing on who is in an item - the face and speaker clusters a reviewer confirmed, or that person matching suggested.
 *
 * <p>
 * What this class guards above all is the difference between three answers: a person is in it, nobody known is in it, and "we could not find out".
 * The last must never read as the second, or a Loom outage would route a library's worth of interviews down the "nobody" branch.
 * </p>
 */
class PersonFilterNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final UUID ANNA = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");

	private static final UUID BERT = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;

	private StubLoomMedia media;

	private CortexOptions cortexOptions;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(new AssetResponse().setUuid(assetUuid), 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<JsonCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync()).thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "interview.wav", "some bytes");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);
	}

	private static ClusterResponse confirmed(String type, UUID person, String name) {
		return new ClusterResponse().setType(type).setReviewStatus("CONFIRMED").setPersonUuid(person.toString()).setPersonName(name);
	}

	private static ClusterResponse suggested(String type, UUID person, String name, float score) {
		return new ClusterResponse().setType(type).setReviewStatus("PENDING").setSuggestedPersonUuid(person.toString())
			.setSuggestedPersonName(name).setSuggestedScore(score);
	}

	@SuppressWarnings("unchecked")
	private void clusters(ClusterResponse... clusters) throws Exception {
		LoomClientRequest<ClusterListResponse> req = mock(LoomClientRequest.class);
		ClusterListResponse response = new ClusterListResponse();
		response.setData(new java.util.ArrayList<>(List.of(clusters)));
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));
		when(client.listAssetClusters(any(UUID.class))).thenReturn(req);
	}

	@SuppressWarnings("unchecked")
	private void clustersUnreachable() throws Exception {
		LoomClientRequest<ClusterListResponse> req = mock(LoomClientRequest.class);
		when(req.sync()).thenThrow(new RuntimeException("connection refused"));
		when(client.listAssetClusters(any(UUID.class))).thenReturn(req);
	}

	private FilterNode node(LoomHttpClient loom, JsonObject extra) {
		Provider<FilterStrategy> strategy = PersonFilterStrategy::new;
		FilterNode node = new FilterNode(loom, cortexOptions, new FilterNodeOptions(), Map.of(FilterBy.PERSON, strategy));
		JsonObject def = new JsonObject()
			.put("id", "by-person")
			.put("filterBy", "PERSON")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "anna").put("match", "Anna  Meyer"))
				.add(new JsonObject().put("id", "bert").put("match", BERT.toString()))
				.add(new JsonObject().put("id", "known").put("match", "*"))
				.add(new JsonObject().put("id", "none").put("match", "nobody")))
			.mergeIn(extra);
		node.configure(def);
		return node;
	}

	private FilterNode node() {
		return node(client, new JsonObject());
	}

	private NodeResult run(FilterNode node) {
		return node.process(NodeContext.create((LoomMedia) media, NodeInputs.empty()));
	}

	/** Offline: no client, no asset, and an unknown asset is a routing answer - the task succeeds. */
	@Test
	void testAnAssetLoomDoesNotKnowGoesToOther() throws Exception {
		assertThat(run(node(null, new JsonObject()))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	@Test
	void testAConfirmedFaceIsMatchedByName() throws Exception {
		clusters(confirmed("face", ANNA, "Anna Meyer"));
		assertThat(run(node()))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("anna"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("known"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE);
		verifyComp(data -> data.getJsonArray("persons").getJsonObject(0).getBoolean("confirmed")
			&& "CONFIRMED".equals(data.getString("personEvidence")));
	}

	@Test
	void testAPersonUuidMatchesWhateverTheyAreCalled() throws Exception {
		clusters(confirmed("speaker", BERT, "Somebody Renamed"));
		assertThat(run(node())).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "bert");
	}

	/** By default only a reviewer's verdict counts: a machine's suggestion about a biometric identity routes nothing. */
	@Test
	void testASuggestionDoesNotCountUnlessAskedFor() throws Exception {
		clusters(suggested("speaker", ANNA, "Anna Meyer", 0.71f));
		assertThat(run(node())).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "none");

		assertThat(run(node(client, new JsonObject().put("personEvidence", "SUGGESTED"))))
			.isSuccess()
			.hasOutput(FilterNode.OUT_BUCKET, "anna");
		verifyComp(data -> "SUGGESTED".equals(data.getString("personEvidence")) && Math.abs(data.getDouble("confidence") - 0.71) < 1e-4);
	}

	/** A rejected cluster names nobody, even though rejection leaves its earlier attribution readable. */
	@Test
	void testARejectedClusterNamesNobody() throws Exception {
		clusters(new ClusterResponse().setType("face").setReviewStatus("REJECTED").setPersonUuid(ANNA.toString()).setPersonName("Anna Meyer"));
		assertThat(run(node())).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "none");
	}

	@Test
	void testPersonSourceNarrowsToOneModality() throws Exception {
		clusters(confirmed("face", ANNA, "Anna Meyer"));
		assertThat(run(node(client, new JsonObject().put("personSource", "VOICE")))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "none");
		assertThat(run(node(client, new JsonObject().put("personSource", "FACE")))).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "anna");
	}

	/** Clusters that are not about people - an object group, say - are ignored. */
	@Test
	void testOtherClusterTypesAreNotPersons() throws Exception {
		clusters(new ClusterResponse().setType("object").setReviewStatus("CONFIRMED").setPersonUuid(ANNA.toString()).setPersonName("Anna Meyer"));
		assertThat(run(node())).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "none");
	}

	/** The one that matters most: an unreachable Loom is "we could not find out", never "nobody". */
	@Test
	void testAFailedClusterLookupIsOtherRatherThanNobody() throws Exception {
		clustersUnreachable();
		assertThat(run(node()))
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("none"));
		verifyComp(data -> "persons unavailable".equals(data.getString("reason")));
	}

	/**
	 * Who is in a file changes when a reviewer confirms a face; the file does not. A worker-lifetime cache keyed on the file would keep answering
	 * "nobody" after the confirmation, so this strategy is never cached.
	 */
	@Test
	void testTheVerdictIsNeverCached() throws Exception {
		FilterNode node = node();
		clusters();
		assertThat(run(node)).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "none");
		clusters(confirmed("face", ANNA, "Anna Meyer"));
		assertThat(run(node)).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "anna");
	}

	@Test
	void testVetoes() throws Exception {
		List<PersonMention> anna = List.of(new PersonMention(ANNA, "Anna Meyer", PersonSource.FACE, true, null));
		List<PersonMention> bert = List.of(new PersonMention(BERT, "Bert", PersonSource.VOICE, true, null));
		assertFalse(PersonFilterStrategy.matches(anna, List.of("!anna meyer")));
		assertTrue(PersonFilterStrategy.matches(bert, List.of("!anna meyer")), "a bucket of only vetoes matches when none is present");
		assertFalse(PersonFilterStrategy.matches(anna, List.of("*", "!anna meyer")), "one veto is enough, whatever else matched");
		assertTrue(PersonFilterStrategy.matches(List.of(), List.of("nobody")));
		assertFalse(PersonFilterStrategy.matches(anna, List.of("nobody")));
	}

	@Test
	void testUnusableHintsFailTheConfiguration() throws Exception {
		Provider<FilterStrategy> strategy = PersonFilterStrategy::new;
		FilterNode node = new FilterNode(client, cortexOptions, new FilterNodeOptions(), Map.of(FilterBy.PERSON, strategy));
		assertThrows(IllegalStateException.class, () -> node.configure(new JsonObject().put("filterBy", "PERSON")
			.put("buckets", new JsonArray().add(new JsonObject().put("id", "x").put("match", "!")))));
		assertThrows(IllegalStateException.class, () -> node.configure(new JsonObject().put("filterBy", "PERSON")
			.put("buckets", new JsonArray().add(new JsonObject().put("id", "x").put("match", "!nobody")))));
		assertThrows(IllegalStateException.class, () -> node.configure(new JsonObject().put("filterBy", "PERSON").put("personEvidence", "MAYBE")
			.put("buckets", new JsonArray().add(new JsonObject().put("id", "x")))));
	}

	/** Two nodes differing only in the evidence they accept must not share a producer version or a cache entry. */
	@Test
	void testTheEvidenceIsPartOfTheProducerVersion() throws Exception {
		String confirmedOnly = node().producerVersion();
		String withSuggestions = node(client, new JsonObject().put("personEvidence", "SUGGESTED")).producerVersion();
		assertTrue(confirmedOnly.startsWith("filter/1:PERSON:"));
		assertFalse(confirmedOnly.equals(withSuggestions));
	}

	private void verifyComp(java.util.function.Predicate<JsonObject> check) {
		org.mockito.Mockito.verify(client, org.mockito.Mockito.atLeastOnce()).createAssetJsonComp(eq(assetUuid),
			argThat((JsonCompCreateRequest r) -> check.test(r.getData())));
	}
}
