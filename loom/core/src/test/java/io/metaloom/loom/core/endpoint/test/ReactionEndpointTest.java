package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.reaction.ReactionUpdateRequest;

/**
 * Reads the reactions the fixture ships, on all three of the things a reaction can hang from.
 *
 * <p>
 * {@code reaction.type} is a plain varchar in the schema but an enum at the REST boundary: the create path stores {@link ReactionType#name()} and
 * {@code ReactionModelBuilder.toResponse} reads it back with {@code valueOf}. Three of the four fixture reactions used to store the asset's
 * <em>mime type</em> there and the fourth a lowercase {@code "thumbsup"}, so every read of a fixture reaction answered <b>500</b> —
 * {@code No enum constant ...ReactionType.image/jpeg}. Nothing caught it: the model-builder test mocks a valid type, and no endpoint test read those
 * rows. These do.
 * </p>
 */
public class ReactionEndpointTest extends AbstractEndpointTest {

	@Test
	public void testLoadFixtureAssetReaction() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ReactionResponse reaction = client.loadAssetReaction(ASSET_UUID, REACTION_2_UUID).sync().body();
			assertNotNull(reaction, "The fixture reaction on the asset must be readable");
			org.assertj.core.api.Assertions.assertThat(reaction.getType()).as("type").isEqualTo(ReactionType.THUMBSUP);

			ReactionListResponse list = client.listAssetReaction(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(list.getData().stream().anyMatch(r -> r.getUuid().equals(REACTION_2_UUID)))
				.as("The fixture reaction must be listed on the asset").isTrue();
		}
	}

	@Test
	public void testLoadFixtureTaskReaction() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ReactionResponse reaction = client.loadTaskReaction(TASK_UUID, REACTION_3_UUID).sync().body();
			assertNotNull(reaction, "The fixture reaction on the task must be readable");
			org.assertj.core.api.Assertions.assertThat(reaction.getType()).as("type").isEqualTo(ReactionType.PLUS_ONE);

			ReactionListResponse list = client.listTaskReaction(TASK_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(list.getData().stream().anyMatch(r -> r.getUuid().equals(REACTION_3_UUID)))
				.as("The fixture reaction must be listed on the task").isTrue();
		}
	}

	@Test
	public void testLoadFixtureCommentReaction() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// The fixture comment has no fixed uuid - it is the one comment on the fixture task.
			UUID commentUuid = client.listTaskComments(TASK_UUID).sync().body().getData().get(0).getUuid();

			ReactionResponse reaction = client.loadCommentReaction(commentUuid, REACTION_1_UUID).sync().body();
			assertNotNull(reaction, "The fixture reaction on the comment must be readable");
			org.assertj.core.api.Assertions.assertThat(reaction.getType()).as("type").isEqualTo(ReactionType.SATISFIED);
		}
	}

	/**
	 * The workflow star rating, end to end on its own reaction type.
	 *
	 * <p>
	 * The read is the load-bearing half: {@code reaction.type} is a varchar and {@code ReactionModelBuilder.toResponse} runs it through
	 * {@code ReactionType.valueOf}, so a {@code RATING} row written against an enum that lacks the constant answers <b>500</b> rather than failing at
	 * write time. Creating and never reading it back would pass with the constant removed.
	 * </p>
	 */
	@Test
	public void testRatingRoundTrip() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ReactionCreateRequest request = new ReactionCreateRequest();
			request.setType(ReactionType.RATING);
			request.setRating(8);
			ReactionResponse created = client.createAssetReaction(ASSET_UUID, request).sync().body();
			assertNotNull(created.getUuid());
			assertThat(created.getType()).as("type").isEqualTo(ReactionType.RATING);
			assertThat(created.getRating()).as("rating").isEqualTo(8);

			ReactionResponse loaded = client.loadAssetReaction(ASSET_UUID, created.getUuid()).sync().body();
			assertThat(loaded.getType()).as("type of the re-read rating").isEqualTo(ReactionType.RATING);
			assertThat(loaded.getRating()).as("rating of the re-read rating").isEqualTo(8);

			ReactionListResponse list = client.listAssetReaction(ASSET_UUID).sync().body();
			assertThat(list.getData().stream().anyMatch(r -> r.getUuid().equals(created.getUuid())))
				.as("The rating must be listed on the asset").isTrue();
			// The fixture's emoji reaction on the same asset must still be there: a rating and a reaction
			// are separate rows now, which is the whole point of the dedicated type.
			assertThat(list.getData().stream().anyMatch(r -> r.getType() == ReactionType.THUMBSUP))
				.as("The emoji reaction must survive alongside the rating").isTrue();

			ReactionUpdateRequest update = new ReactionUpdateRequest();
			update.setType(ReactionType.RATING);
			update.setRating(3);
			client.updateAssetReaction(ASSET_UUID, created.getUuid(), update).sync().body();
			assertThat(client.loadAssetReaction(ASSET_UUID, created.getUuid()).sync().body().getRating())
				.as("rating after the update").isEqualTo(3);

			client.deleteAssetReaction(ASSET_UUID, created.getUuid()).sync().body();
			expect(404, "Not Found", client.loadAssetReaction(ASSET_UUID, created.getUuid()));
		}
	}

	/**
	 * Each reaction route is gated by its own permission, so a caller who may rate must not thereby be able to edit or delete someone else's rating.
	 *
	 * <p>
	 * The grants go through a role and a group rather than {@code user_permission}, whose primary key allows only one direct grant per user.
	 * </p>
	 */
	@Test
	public void testRatingPermissions() throws LoomClientException {
		UUID ratingUuid;
		try (LoomHttpClient reviewer = loginClientWith("rating-reviewer", Permission.CREATE_REACTION, Permission.READ_REACTION)) {
			ReactionCreateRequest request = new ReactionCreateRequest();
			request.setType(ReactionType.RATING);
			request.setRating(9);
			ratingUuid = reviewer.createAssetReaction(ASSET_UUID, request).sync().body().getUuid();
			assertNotNull(ratingUuid, "CREATE_REACTION must be enough to rate an asset");

			assertThat(reviewer.loadAssetReaction(ASSET_UUID, ratingUuid).sync().body().getRating())
				.as("READ_REACTION must be enough to read the rating back").isEqualTo(9);

			// The reviewer holds neither UPDATE_REACTION nor DELETE_REACTION.
			ReactionUpdateRequest update = new ReactionUpdateRequest();
			update.setType(ReactionType.RATING);
			update.setRating(1);
			expect(403, "Forbidden", reviewer.updateAssetReaction(ASSET_UUID, ratingUuid, update));
			expect(403, "Forbidden", reviewer.deleteAssetReaction(ASSET_UUID, ratingUuid));
		}

		try (LoomHttpClient nobody = loginPermissionlessClient()) {
			expect(403, "Forbidden", nobody.loadAssetReaction(ASSET_UUID, ratingUuid));
			ReactionCreateRequest request = new ReactionCreateRequest();
			request.setType(ReactionType.RATING);
			request.setRating(5);
			expect(403, "Forbidden", nobody.createAssetReaction(ASSET_UUID, request));
		}

		// The rating must have survived every rejected write.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			assertThat(client.loadAssetReaction(ASSET_UUID, ratingUuid).sync().body().getRating())
				.as("rating after the rejected update").isEqualTo(9);
		}
	}
}
