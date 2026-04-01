package io.metaloom.loom.client.http.parameter;

import java.time.Duration;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.filter.key.impl.DurationFilterKey;
import io.metaloom.filter.key.impl.SizeFilterKey;
import io.metaloom.filter.key.impl.StringFilterKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.client.common.QueryParameters;
import io.metaloom.loom.client.http.LoomClientHttpRequest;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.parameter.QueryParameterKey;

public interface HttpQueryParameters<T extends RestResponseModel<T>> extends QueryParameters<T>{

	/**
	 * Add an additional query parameter.
	 * 
	 * @param key
	 * @param value
	 * @return Fluent API
	 */
	LoomClientHttpRequest<T> addQueryParameter(String key, String value);

	default LoomClientHttpRequest<T> addLimit(Integer limit) {
		return addQueryParameter(QueryParameterKey.LIMIT.key(), String.valueOf(limit));
	}

	default LoomClientHttpRequest<T> addFrom(UUID startUuid) {
		return addQueryParameter(QueryParameterKey.FROM.key(), startUuid.toString());
	}

	default LoomClientHttpRequest<T> addFilter(Filter filter) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), filter.toString());
	}

	default LoomClientHttpRequest<T> sortBy(SortKey key) {
		return addQueryParameter(QueryParameterKey.SORT.key(), key.toString());
	}

	default LoomClientHttpRequest<T> sortDirection(SortDirection direction) {
		return addQueryParameter(QueryParameterKey.DIRECTION.key(), direction.toString());
	}

	default LoomClientHttpRequest<T> addEquals(SizeFilterKey key, String value) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), key.eq(value).toString());
	}

	default LoomClientHttpRequest<T> addEquals(DurationFilterKey key, Duration duration) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), key.eq(duration).toString());
	}

	default LoomClientHttpRequest<T> addEquals(StringFilterKey key, String value) {
		return addQueryParameter(QueryParameterKey.FILTER.key(), key.eq(value).toString());
	}

	LoomClientHttpRequest<T> self();

}