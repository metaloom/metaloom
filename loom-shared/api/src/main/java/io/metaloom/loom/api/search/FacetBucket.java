package io.metaloom.loom.api.search;

/**
 * One facet value and how many hits carry it.
 */
public class FacetBucket {

	private String value;

	private long count;

	public FacetBucket() {
	}

	public FacetBucket(String value, long count) {
		this.value = value;
		this.count = count;
	}

	public String getValue() {
		return value;
	}

	public FacetBucket setValue(String value) {
		this.value = value;
		return this;
	}

	public long getCount() {
		return count;
	}

	public FacetBucket setCount(long count) {
		this.count = count;
		return this;
	}

	@Override
	public String toString() {
		return value + "=" + count;
	}
}
