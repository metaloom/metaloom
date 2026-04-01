package io.metaloom.loom.rest.parameter;

import java.util.List;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.LoomRoutingContext;

public abstract class AbstractQueryParameters {

	private LoomRoutingContext lrc;

	public AbstractQueryParameters(LoomRoutingContext lrc) {
		this.lrc = lrc;
	}

	protected <T> T mapParameter(QueryParameterKey paramKey) {
		List<String> values = lrc.queryParam(paramKey.key());
		if (values.size() == 0) {
			return paramKey.defaultValue();
		}
		if (values.size() > 1) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Parameter " + paramKey.key() + " was found multiple times");
		}
		String value = values.get(0);
		if (value == null) {
			return paramKey.defaultValue();
		} else {
			return paramKey.map(value);
		}
	}
}
