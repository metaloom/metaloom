package io.metaloom.loom.rest.model.user;

import io.metaloom.loom.rest.model.common.AbstractNamedReference;

public class UserReference extends AbstractNamedReference<UserReference> {

	@Override
	public UserReference self() {
		return this;
	}

}
