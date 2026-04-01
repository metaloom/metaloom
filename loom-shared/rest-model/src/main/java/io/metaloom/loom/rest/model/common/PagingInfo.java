package io.metaloom.loom.rest.model.common;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class PagingInfo {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The UUID of the last element.")
	private UUID lastUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Number of elements which can be included in a single page.")
	private Long perPage;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Number of elements in the page.")
	private long totalCount;

	public PagingInfo() {

	}

	/**
	 * Return the amount of items that should be included in one page.
	 * 
	 * @return Per page count
	 */
	public Long getPerPage() {
		return perPage;
	}

	/**
	 * Set the per page count.
	 * 
	 * @param perPage
	 *            Per page count
	 * @return Fluent API
	 */
	public PagingInfo setPerPage(Long perPage) {
		this.perPage = perPage;
		return this;
	}

	/**
	 * Return the total element count.
	 * 
	 * @return Total element count
	 */
	public long getTotalCount() {
		return totalCount;
	}

	/**
	 * Set the total element count.
	 * 
	 * @param totalCount
	 *            Total element count
	 * @return Fluent API
	 */
	public PagingInfo setTotalCount(long totalCount) {
		this.totalCount = totalCount;
		return this;
	}

	public UUID getLastUuid() {
		return lastUuid;
	}

	public PagingInfo setLastUuid(UUID lastUuid) {
		this.lastUuid = lastUuid;
		return this;
	}
}
