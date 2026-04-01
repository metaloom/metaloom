package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;

public class ReactionModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Reaction reaction = mockReaction("primary");
		assertWithModel(builder().toResponse(reaction), "reaction.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Reaction reaction1 = mockReaction("primary");
		Reaction reaction2 = mockReaction("secondary");
		Page<Reaction> page = mockPage(reaction1, reaction2);
		ReactionListResponse list = builder().toReactionList(page);
		assertWithModel(list, "reaction.list_response");
	}

	private Reaction mockReaction(String title) {
		Reaction reaction = mock(Reaction.class);
		when(reaction.getUuid()).thenReturn(TASK_UUID);
		return reaction;
	}

}
