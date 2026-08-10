package io.metaloom.loom.rest.model.common;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Abstract model for list REST responses.
 *
 * @param <T>
 */
public abstract class AbstractListResponse<LT extends AbstractListResponse<LT, T>, T extends RestResponseModel<T>> implements RestResponseModel<LT> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Array which contains the found elements.")
	private List<T> data;

	@JsonPropertyDescription("Paging information of the list result.")
	@JsonProperty(value = "_metainfo", required = true)
	private PagingInfo metainfo;

	public AbstractListResponse() {
	}

	/**
	 * Return the meta info for the list.
	 * 
	 * @return Meta info
	 */
	public PagingInfo getMetainfo() {
		return metainfo;
	}

	/**
	 * Set the meta info for the list.
	 * 
	 * @param metainfo
	 *            Meta info
	 */
	public LT setMetainfo(PagingInfo metainfo) {
		this.metainfo = metainfo;
		return self();
	}

	/**
	 * Return the list data.
	 * 
	 * @return List data
	 */
	public List<T> getData() {
		return data;
	}

	/**
	 * Replace the list data.
	 *
	 * <p>
	 * {@code data} is declared {@code required}, but {@link #add(RestResponseModel)} only creates the list on first use, so a listing that found
	 * nothing answers with no array at all. Endpoints whose empty result is an ordinary state rather than an edge case set the list up front instead,
	 * so a caller can iterate the response without special-casing emptiness.
	 * </p>
	 *
	 * @param data
	 *            the elements, copied into a mutable list so {@link #add(RestResponseModel)} still works afterwards
	 */
	public LT setData(List<T> data) {
		this.data = data == null ? new ArrayList<>() : new ArrayList<>(data);
		return self();
	}

	/**
	 * Add the given element to the list.
	 *
	 * @param e
	 */
	public LT add(T e) {
		if (data == null) {
			data = new ArrayList<>();
		}
		data.add(e);
		return self();
	}
}
