package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;

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
}
