package io.metaloom.loom.rest.model.remix;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class RemixListResponse extends AbstractListResponse<RemixListResponse, RemixResponse> {

	@Override
	public RemixListResponse self() {
		return this;
	}

}
