package io.metaloom.loom.client.common;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.reactivex.rxjava3.core.Single;

public interface LoomClientRequest<T extends RestResponseModel<T>> extends QueryParameters<T> {

	Single<T> async();

	T sync() throws LoomClientException;

}
