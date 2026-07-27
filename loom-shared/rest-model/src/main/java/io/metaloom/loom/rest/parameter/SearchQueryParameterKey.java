package io.metaloom.loom.rest.parameter;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchSortMode;

/**
 * Query parameters of the {@code /api/v1/search/*} routes.
 *
 * <p>
 * Deliberately <b>separate</b> from {@link QueryParameterKey}: {@code AbstractEndpoint.addListRoute} iterates every value of that enum and documents it
 * on every list route, so adding {@code q} there would inject a {@code q} parameter into the OpenAPI spec of some forty routes that ignore it.
 * </p>
 *
 * <p>
 * {@code q} has to be a first-class parameter rather than an LHS filter: the filter library's operator vocabulary is
 * {@code EQUALS, NOT_EQUALS, AFTER, BEFORE, RANGE, GREATER, LESSER}, with no {@code LIKE} or {@code CONTAINS}, so {@code ?filter=} structurally cannot
 * carry a search term. Filters remain available for facets.
 * </p>
 */
public enum SearchQueryParameterKey {

	QUERY("q", null, s -> s, "The search term. Supports \"quoted phrases\", or, and -negation", "aurora"),

	TYPES("types", null, s -> s, "Comma separated entity types to search", "asset,transcript,tag"),

	MODE("mode", null, SearchMode::fromString, "Matching mode. Providers without the capability reject the request rather than silently degrading",
		"LEXICAL"),

	LIMIT("limit", 25, Integer::valueOf, "Page size", "25"),

	OFFSET("offset", 0, Integer::valueOf, "Result offset. Capped per provider; prefer the returned cursor when present", "0"),

	CURSOR("cursor", null, s -> s, "Opaque cursor from a previous response. Wins over offset", ""),

	SORT("sort", null, SearchSortMode::fromString, "Result ordering", "RELEVANCE"),

	HIGHLIGHT("highlight", false, Boolean::valueOf, "Return match snippets", "true"),

	MIME("mime", null, s -> s, "Restrict to a mime type prefix", "image/"),

	LIBRARY("library", null, UUID::fromString, "Restrict to a library", "e829f0f1-4775-4857-a326-850440cf9577"),

	SPACE("space", null, UUID::fromString, "Restrict to a space", "e829f0f1-4775-4857-a326-850440cf9577"),

	COLLECTION("collection", null, UUID::fromString, "Restrict to a collection", "e829f0f1-4775-4857-a326-850440cf9577"),

	TAG("tag", null, s -> s, "Comma separated tag names the asset must carry", "nightscape"),

	FROM("from", null, Instant::parse, "Only elements first seen at or after this instant", "2026-01-01T00:00:00Z"),

	TO("to", null, Instant::parse, "Only elements first seen at or before this instant", "2026-12-31T23:59:59Z"),

	LANG("lang", null, s -> s, "Restrict to a language tag", "en"),

	PROFILE("profile", null, s -> s, "Named search profile (vector_config row). Reserved for hybrid search", "default"),

	FACETS("facets", null, s -> s, "Comma separated facets to compute", "mime_type,entity_type");

	private final String key;
	private final Function<String, ?> converter;
	private final Object defaultValue;
	private final String example;
	private final String description;

	SearchQueryParameterKey(String key, Object defaultValue, Function<String, ?> converter, String description, String example) {
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
	public <T> T map(String value) {
		return (T) converter.apply(value);
	}

	@SuppressWarnings("unchecked")
	public <T> T defaultValue() {
		return (T) defaultValue;
	}
}
