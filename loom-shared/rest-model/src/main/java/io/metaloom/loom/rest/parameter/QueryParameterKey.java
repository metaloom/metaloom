package io.metaloom.loom.rest.parameter;

import java.util.UUID;
import java.util.function.Function;

import io.metaloom.loom.api.filter.LoomLHSFilterParser;
import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortDirection;

public enum QueryParameterKey {

	LIMIT("limit", 25, Integer::valueOf, "Limit the page size", "25"),

	FROM("from", null, UUID::fromString, "Seek to the element with the given UUID", "e829f0f1-4775-4857-a326-850440cf9577"),

	FILTER("filter", null, filterStr -> {
		return new LoomLHSFilterParser().parse(filterStr);
	}, "Filter the elements", "name[eq]=joedoe"),

	SORT("sort", null, LoomSortKey::fromString, "Sort the elements by the provided field", "username"),

	DIRECTION("dir", null, SortDirection::fromString, "Sort order direction", SortDirection.ASCENDING.name());

	private String key;
	private Function<String, ?> converter;
	private Object defaultValue;
	private String example;
	private String description;

	QueryParameterKey(String key, Object defaultValue, Function<String, ?> converter, String description, String example) {
		this.key = key;
		this.converter = converter;
		this.defaultValue = defaultValue;
		this.description = description;
		this.example = example;
	}

	public String key() {
		return key;
	}

	public String description() {
		return description;
	}

	public String example() {
		return example;
	}

	@SuppressWarnings("unchecked")
	<T> T map(String value) {
		return (T) converter.apply(value);
	}

	@SuppressWarnings("unchecked")
	<T> T defaultValue() {
		return (T) defaultValue;
	}

}
