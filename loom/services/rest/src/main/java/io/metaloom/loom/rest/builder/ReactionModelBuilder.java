package io.metaloom.loom.rest.builder;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;

public interface ReactionModelBuilder extends ModelBuilder, UserModelBuilder {

	default ReactionResponse toResponse(Reaction reaction) {
		ReactionResponse response = new ReactionResponse();
		response.setUuid(reaction.getUuid());
		String type = reaction.getType();
		if (type != null) {
			response.setType(ReactionType.valueOf(type));
		}
		response.setRating(reaction.getRating());
		setStatus(reaction, response);
		return response;
	}

	default ReactionListResponse toReactionList(Page<Reaction> page) {
		return setPage(new ReactionListResponse(), page, this::toResponse);
	}

}
