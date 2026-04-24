package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.blacklist.BlacklistListResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistResponse;

public interface BlacklistModelBuilder extends ModelBuilder, UserModelBuilder {

	default BlacklistResponse toResponse(Blacklist blacklist) {
		BlacklistResponse response = new BlacklistResponse();
		response.setName(blacklist.getName());
		response.setUuid(blacklist.getUuid());
		if (blacklist.getAssetUuid() != null) {
			response.setAssetUuid(blacklist.getAssetUuid().toString());
		}
		setStatus(blacklist, response);
		return response;
	}

	default BlacklistListResponse toBlacklistList(Page<Blacklist> page) {
		return setPage(new BlacklistListResponse(), page, this::toResponse);
	}
}
