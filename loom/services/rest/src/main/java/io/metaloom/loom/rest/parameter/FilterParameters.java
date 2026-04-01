package io.metaloom.loom.rest.parameter;

import java.util.List;

import io.metaloom.filter.Filter;
import io.metaloom.loom.rest.LoomRoutingContext;

public class FilterParameters extends AbstractQueryParameters {

	public FilterParameters(LoomRoutingContext lrc) {
		super(lrc);
	}

	public static FilterParameters create(LoomRoutingContext lrc) {
		return new FilterParameters(lrc);
	}

	public List<Filter> filters() {
		return mapParameter(QueryParameterKey.FILTER);
	}

}
