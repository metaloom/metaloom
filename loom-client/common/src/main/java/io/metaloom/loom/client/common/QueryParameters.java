package io.metaloom.loom.client.common;

import java.time.Duration;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.filter.key.impl.DurationFilterKey;
import io.metaloom.filter.key.impl.SizeFilterKey;
import io.metaloom.filter.key.impl.StringFilterKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.parameter.QueryParameterKey;

public interface QueryParameters<T extends RestResponseModel<T>> {

	/**
	 * Add an additional query parameter.
	 * 
	 * @param key
	 * @param value
	 * @return Fluent API
	 */
	LoomClientRequest<T> addQueryParameter(String key, String value);

	default LoomClientRequest<T> addLimit(Integer limit) {
		return addQueryParameter(QueryParameterKey.LIMIT.key(), String.valueOf(limit));
	}

	default LoomClientRequest<T> addFrom(UUID startUuid) {
		return addQueryParameter(QueryParameterKey.FROM.key(), startUuid.toString());
	}

	default LoomClientRequest<T> addFilter(Filter filter) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), filter.toString());
	}

	default LoomClientRequest<T> sortBy(SortKey key) {
		return addQueryParameter(QueryParameterKey.SORT.key(), key.toString());
	}

	default LoomClientRequest<T> sortDirection(SortDirection direction) {
		return addQueryParameter(QueryParameterKey.DIRECTION.key(), direction.toString());
	}

	default LoomClientRequest<T> addEquals(SizeFilterKey key, String value) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), key.eq(value).toString());
	}

	default LoomClientRequest<T> addEquals(DurationFilterKey key, Duration duration) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), key.eq(duration).toString());
	}

	default LoomClientRequest<T> addEquals(StringFilterKey key, String value) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), key.eq(value).toString());
	}

	LoomClientRequest<T> self();

}