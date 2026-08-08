package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
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
import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Routing on a rating with Loom reachable: which number wins when several people rated, and what the
 * persisted decision records about it.
 *
 * <p>
 * The three states a rating lookup can end in are deliberately kept apart, and the last two are what
 * this class is really guarding:
 * </p>
 * <ul>
 * <li>rated — routed by the mean</li>
 * <li>known to Loom, nobody rated it — <em>unrated</em>, which an {@code unrated} bucket catches</li>
 * <li>the reaction fetch failed — {@code other}, <b>not</b> unrated. Collapsing the two would route
 * the whole un-reviewed backlog down the trash branch during a Loom outage</li>
 * </ul>
 */
class RatingFilterNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

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

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

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

	private static ReactionResponse rating(int value) {
		return (ReactionResponse) new ReactionResponse().setType(ReactionType.RATING).setRating(value).setUuid(UUID.randomUUID());
	}

	private static ReactionResponse emoji() {
		return (ReactionResponse) new ReactionResponse().setType(ReactionType.THUMBSUP).setUuid(UUID.randomUUID());
	}

	@SuppressWarnings("unchecked")
	private void reactions(ReactionResponse... reactions) throws Exception {
		LoomClientRequest<ReactionListResponse> req = mock(LoomClientRequest.class);
		ReactionListResponse response = new ReactionListResponse();
		for (ReactionResponse reaction : reactions) {
			response.add(reaction);
		}
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));
		when(client.listAssetReaction(any(UUID.class))).thenReturn(req);
	}

	@SuppressWarnings("unchecked")
	private void reactionsUnreachable() throws Exception {
		LoomClientRequest<ReactionListResponse> req = mock(LoomClientRequest.class);
		when(req.sync()).thenThrow(new RuntimeException("connection refused"));
		when(client.listAssetReaction(any(UUID.class))).thenReturn(req);
	}

	private FilterNode node() {
		Provider<FilterStrategy> strategy = RatingFilterStrategy::new;
		FilterNode node = new FilterNode(client, cortexOptions, new FilterNodeOptions(), Map.of(FilterBy.RATING, strategy));
		node.configure(new JsonObject()
			.put("id", "by-rating")
			.put("filterBy", "RATING")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "keep").put("match", ">=8"))
				.add(new JsonObject().put("id", "trash").put("match", "<=2"))
				.add(new JsonObject().put("id", "todo").put("match", "unrated"))));
		return node;
	}

	private NodeResult run(FilterNode node) {
		return node.process(NodeContext.create((LoomMedia) media, NodeInputs.empty()));
	}

	@Test
	void testASingleHighRatingTakesTheKeepBranch() throws Exception {
		reactions(rating(9), emoji());

		assertThat(run(node()))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("keep"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("trash"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE);
	}

	/**
	 * Two reviewers disagreeing average out, and the persisted decision says so — the raw mean and the
	 * count are both recorded, so a routing choice can be explained after the fact rather than guessed at.
	 */
	@Test
	void testSeveralReviewersAreAveraged() throws Exception {
		reactions(rating(10), rating(7));

		assertThat(run(node())).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "keep");

		verifyComp(data -> Integer.valueOf(9).equals(data.getInteger("rating"))
			&& Double.valueOf(8.5d).equals(data.getDouble("ratingMean"))
			&& Integer.valueOf(2).equals(data.getInteger("ratingCount"))
			&& "mean".equals(data.getString("ratingSource")));
	}

	/** An emoji reaction is not a rating, however enthusiastic. */
	@Test
	void testEmojiReactionsAreNotCountedAsRatings() throws Exception {
		reactions(emoji());

		assertThat(run(node())).isSuccess().hasOutput(FilterNode.OUT_BUCKET, "todo");
	}

	@Test
	void testAnAssetNobodyRatedIsUnrated() throws Exception {
		reactions();

		assertThat(run(node()))
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("todo"), media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("trash"));

		verifyComp(data -> Integer.valueOf(0).equals(data.getInteger("ratingCount")) && data.getInteger("rating") == null);
	}

	/**
	 * The one that matters most. An unreachable Loom must not look like "nobody rated it": the
	 * {@code unrated} branch is where re-review and trash work goes, and a blip must not send a
	 * reviewed, well-rated backlog down it. The task still succeeds — a routing answer, not a failure.
	 */
	@Test
	void testAFailedReactionLookupIsOtherRatherThanUnrated() throws Exception {
		reactionsUnreachable();

		assertThat(run(node()))
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("todo"))
			.hasNoOutput(FilterNode.bucketPort("keep"))
			.hasNoOutput(FilterNode.bucketPort("trash"));

		verifyComp(data -> "reactions unavailable".equals(data.getString("reason")));
	}

	private void verifyComp(java.util.function.Predicate<JsonObject> check) {
		org.mockito.Mockito.verify(client).createAssetJsonComp(eq(assetUuid),
			argThat((JsonCompCreateRequest r) -> check.test(r.getData())));
	}
}
