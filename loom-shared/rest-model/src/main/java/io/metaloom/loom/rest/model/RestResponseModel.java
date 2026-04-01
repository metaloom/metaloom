package io.metaloom.loom.rest.model;

/**
 * Marker interface for REST responses
 */
public interface RestResponseModel<T extends RestResponseModel<T>> extends RestModel {

	T self();

}
