package io.metaloom.loom.rest.parameter;

import java.util.UUID;

import io.metaloom.loom.rest.LoomRoutingContext;

public class PagingParameters extends AbstractQueryParameters {

	public PagingParameters(LoomRoutingContext lrc) {
		super(lrc);
	}

	public static PagingParameters create(LoomRoutingContext lrc) {
		return new PagingParameters(lrc);
	}

	public UUID from() {
		return mapParameter(QueryParameterKey.FROM);
	}

	public int limit() {
		return mapParameter(QueryParameterKey.LIMIT);
	}

}
