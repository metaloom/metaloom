package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.cortexOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.mediaWith;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * The assign node.
 *
 * <p>
 * 🔴 Every test here ends by asserting the source file is at the same path with the same bytes. "Add to a collection" writing a join row and nothing
 * else is the entire contract; the day that stops being true is the day a curation pipeline starts relocating people's originals.
 * </p>
 */
class AssignNodeTest {

	private static final UUID COLLECTION_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@TempDir
	File tempDir;

	private LoomHttpClient client;
	private StubLoomMedia media;
	private Path sourcePath;

	@BeforeEach
	void setup() throws Exception {
		client = mock(LoomHttpClient.class);

		LoomClientRequest<NodeResultResponse> ledger = request(new NodeResultResponse().setUuid(UUID.randomUUID()));
		var req1 = request(RelocateTestFixtures.asset());
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledger);

		media = mediaWith(tempDir, "clip.mp4", "payload");
		sourcePath = Path.of(media.absolutePath());

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(req1);
	}

	@SuppressWarnings("unchecked")
	private <T extends RestResponseModel<T>> LoomClientRequest<T> request(T body) throws Exception {
		LoomClientRequest<T> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
		return req;
	}

	private AssignNode node(AssignNodeOptions options) {
		Map<AssignTarget, Provider<AssignDestination>> destinations = Map.of(
			AssignTarget.COLLECTION, CollectionAssignment::new,
			AssignTarget.LIBRARY, LibraryAssignment::new);
		return new AssignNode(client, cortexOptions(tempDir), options, destinations);
	}

	private AssignNodeOptions options() {
		return new AssignNodeOptions()
			.setTarget(AssignTarget.COLLECTION)
			.setCollectionUuid(COLLECTION_UUID.toString());
	}

	private void assertTheFileWasNotTouched() throws Exception {
		assertTrue(Files.exists(sourcePath), "the assign node must never move or delete a file");
		assertEquals("payload", Files.readString(sourcePath, UTF_8), "the assign node must never modify a file");
	}

	@Test
	void testAddsTheAssetToTheCollection() throws Exception {
		var req2 = request(new CollectionResponse().setUuid(COLLECTION_UUID));
		when(client.loadCollection(eq(COLLECTION_UUID))).thenReturn(req2);
		var req3 = request(new CollectionListResponse());
		when(client.listAssetCollections(any())).thenReturn(req3);
		var req4 = request(new CollectionResponse());
		when(client.addCollectionAsset(eq(COLLECTION_UUID), any(UUID.class))).thenReturn(req4);

		NodeResult result = node(options()).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals(true, result.get(AssignNode.OUT_ASSIGNED));
		assertEquals(COLLECTION_UUID.toString(), result.get(AssignNode.OUT_TARGET));
		verify(client, times(1)).addCollectionAsset(eq(COLLECTION_UUID), any(UUID.class));
		assertTheFileWasNotTouched();
	}

	/**
	 * An asset that is already a member is a skip, not a second write. Membership is a set, so a re-run over a curated corpus should be quiet.
	 */
	@Test
	void testAnExistingMembershipIsSkipped() throws Exception {
		CollectionListResponse existing = new CollectionListResponse();
		existing.add(new CollectionResponse().setUuid(COLLECTION_UUID));

		var req5 = request(new CollectionResponse().setUuid(COLLECTION_UUID));
		when(client.loadCollection(eq(COLLECTION_UUID))).thenReturn(req5);
		var req6 = request(existing);
		when(client.listAssetCollections(any())).thenReturn(req6);

		NodeResult result = node(options()).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertEquals(false, result.get(AssignNode.OUT_ASSIGNED));
		verify(client, never()).addCollectionAsset(any(UUID.class), any(UUID.class));
		assertTheFileWasNotTouched();
	}

	@Test
	void testAMissingCollectionFailsByDefault() throws Exception {
		var req7 = request((CollectionResponse) null);
		when(client.loadCollection(eq(COLLECTION_UUID))).thenReturn(req7);

		NodeResult result = node(options()).process(NodeContext.create(media));

		assertThat(result).isFailed();
		verify(client, never()).addCollectionAsset(any(UUID.class), any(UUID.class));
		assertTheFileWasNotTouched();
	}

	@Test
	void testAMissingCollectionCanBeConfiguredToSkip() throws Exception {
		var req8 = request((CollectionResponse) null);
		when(client.loadCollection(eq(COLLECTION_UUID))).thenReturn(req8);

		NodeResult result = node(options().setOnMissing("SKIP")).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertTheFileWasNotTouched();
	}

	@Test
	void testDryRunWritesNothing() throws Exception {
		var req9 = request(new CollectionResponse().setUuid(COLLECTION_UUID));
		when(client.loadCollection(eq(COLLECTION_UUID))).thenReturn(req9);
		var req10 = request(new CollectionListResponse());
		when(client.listAssetCollections(any())).thenReturn(req10);

		NodeResult result = node(options().setDryRun(true)).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertEquals(false, result.get(AssignNode.OUT_ASSIGNED));
		verify(client, never()).addCollectionAsset(any(UUID.class), any(UUID.class));
		assertTheFileWasNotTouched();
	}

	/**
	 * A logical membership cannot exist without the asset row, so an asset Loom has never seen is nothing to do rather than something to fail over.
	 */
	@Test
	void testAnAssetUnknownToLoomIsSkipped() throws Exception {
		var req11 = request((AssetResponse) null);
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(req11);

		NodeResult result = node(options()).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertTheFileWasNotTouched();
	}

	/**
	 * Resolving by name is the pattern the tag node uses. {@code collection.name} is UNIQUE, so a name identifies at most one collection.
	 */
	@Test
	void testResolvesACollectionByName() throws Exception {
		CollectionListResponse all = new CollectionListResponse();
		all.add(new CollectionResponse().setUuid(UUID.randomUUID()).setName("Rejected"));
		all.add(new CollectionResponse().setUuid(COLLECTION_UUID).setName("Published"));

		var req12 = request(all);
		when(client.listCollections()).thenReturn(req12);
		var req13 = request(new CollectionListResponse());
		when(client.listAssetCollections(any())).thenReturn(req13);
		var req14 = request(new CollectionResponse());
		when(client.addCollectionAsset(eq(COLLECTION_UUID), any(UUID.class))).thenReturn(req14);

		AssignNodeOptions options = new AssignNodeOptions()
			.setTarget(AssignTarget.COLLECTION)
			.setCollectionName("Published");

		NodeResult result = node(options).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		verify(client, times(1)).addCollectionAsset(eq(COLLECTION_UUID), any(UUID.class));
		assertTheFileWasNotTouched();
	}

	/**
	 * 🔴 The name lookup can only see the first page - {@code listCollections()} takes no paging parameters - so a miss on a truncated listing is not
	 * evidence that the collection is absent. Reporting it as absent makes {@code onMissing: CREATE} try to create a name that already exists and
	 * {@code FAIL} blame the pipeline for a limitation of the lookup, so it says which it is instead.
	 */
	@Test
	void testANameMissingFromATruncatedListingIsNotReportedAsAbsent() throws Exception {
		CollectionListResponse firstPage = new CollectionListResponse();
		firstPage.add(new CollectionResponse().setUuid(UUID.randomUUID()).setName("Rejected"));
		firstPage.setMetainfo(new io.metaloom.loom.rest.model.common.PagingInfo().setTotalCount(412L));

		var page = request(firstPage);
		when(client.listCollections()).thenReturn(page);

		AssignNodeOptions options = new AssignNodeOptions()
			.setTarget(AssignTarget.COLLECTION)
			.setCollectionName("Published")
			.setOnMissing("CREATE");

		NodeResult result = node(options).process(NodeContext.create(media));

		assertThat(result).isFailed().hasMessageContaining("cannot be paged");
		verify(client, never()).createCollection(any());
		verify(client, never()).addCollectionAsset(any(UUID.class), any(UUID.class));
		assertTheFileWasNotTouched();
	}

	/**
	 * The counterpart: a complete listing that does not hold the name really does mean the collection is absent.
	 */
	@Test
	void testANameMissingFromACompleteListingIsAbsent() throws Exception {
		CollectionListResponse all = new CollectionListResponse();
		all.add(new CollectionResponse().setUuid(UUID.randomUUID()).setName("Rejected"));
		all.setMetainfo(new io.metaloom.loom.rest.model.common.PagingInfo().setTotalCount(1L));

		var page = request(all);
		when(client.listCollections()).thenReturn(page);

		AssignNodeOptions options = new AssignNodeOptions()
			.setTarget(AssignTarget.COLLECTION)
			.setCollectionName("Published")
			.setOnMissing("SKIP");

		NodeResult result = node(options).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertTheFileWasNotTouched();
	}

	@Test
	void testExactlyOneOfUuidOrNameIsRequired() {
		List<String> both = new CollectionAssignment().validate(new AssignNodeOptions()
			.setCollectionUuid(COLLECTION_UUID.toString())
			.setCollectionName("Published"));
		assertTrue(both.stream().anyMatch(p -> p.contains("exactly one")), "Expected a complaint about both fields, got: " + both);

		List<String> neither = new CollectionAssignment().validate(new AssignNodeOptions());
		assertTrue(neither.stream().anyMatch(p -> p.contains("exactly one")), "Expected a complaint about neither field, got: " + neither);
	}

	/**
	 * With {@code nodes: assign:} in the worker YAML every assign node shares one injected options instance. One node naming a collection by uuid and
	 * another by name used to end with the second carrying both - and failing configure - or with the first adding to the second's collection.
	 */
	@Test
	void testTwoAssignNodesSharingInjectedOptionsDoNotLeakIntoEachOther() {
		AssignNodeOptions shared = new AssignNodeOptions().setTarget(AssignTarget.COLLECTION);
		AssignNode byUuid = node(shared);
		AssignNode byName = node(shared);

		byUuid.configure(new io.vertx.core.json.JsonObject().put("id", "a").put("collectionUuid", COLLECTION_UUID.toString()));
		byName.configure(new io.vertx.core.json.JsonObject().put("id", "b").put("collectionName", "Published"));

		assertEquals(COLLECTION_UUID.toString(), byUuid.options().getCollectionUuid());
		assertEquals(null, byUuid.options().getCollectionName());
		assertEquals("Published", byName.options().getCollectionName());
		assertEquals(null, byName.options().getCollectionUuid());
		assertEquals(null, shared.getCollectionUuid(), "the injected instance must stay untouched");
	}
}
