package io.metaloom.loom.rest.parameter;

import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.rest.LoomRoutingContext;

public class SortParameters extends AbstractQueryParameters {

	public SortParameters(LoomRoutingContext lrc) {
		super(lrc);
	}

	public static SortParameters create(LoomRoutingContext lrc) {
		return new SortParameters(lrc);
	}

	public SortKey sortBy() {
		return mapParameter(QueryParameterKey.SORT);
	}

	public SortDirection sortOrder() {
		return mapParameter(QueryParameterKey.DIRECTION);
	}
}
