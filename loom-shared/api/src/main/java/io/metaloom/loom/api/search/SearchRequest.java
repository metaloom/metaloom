package io.metaloom.loom.api.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.metaloom.filter.Filter;

/**
 * A search query, provider independent.
 *
 * <p>
 * <b>The term always travels in {@link #getQuery()}, never in {@link #getFilters()}.</b> The LHS filter library's operator vocabulary is
 * {@code EQUALS, NOT_EQUALS, AFTER, BEFORE, RANGE, GREATER, LESSER} - there is no {@code LIKE} or {@code CONTAINS}, so {@code ?filter=} structurally
 * cannot carry a search term. Filters are for facets (mime, size, date) only.
 * </p>
 */
public class SearchRequest {

	public static final int MAX_QUERY_LENGTH = 512;

	private String query;

	private Set<SearchEntityType> types = new LinkedHashSet<>();

	private SearchMode mode = SearchMode.LEXICAL;

	private List<Filter> filters = new ArrayList<>();

	private String mimeTypePrefix;

	private UUID libraryUuid;

	private UUID spaceUuid;

	private UUID collectionUuid;

	private UUID clusterUuid;

	private List<String> tags = new ArrayList<>();

	private Instant createdFrom;

	private Instant createdTo;

	private String lang;

	private int limit = 25;

	private int offset = 0;

	/** Opaque provider cursor. Wins over {@link #getOffset()} when set. Null under the Postgres provider. */
	private String cursor;

	private SearchSortMode sort = SearchSortMode.RELEVANCE;

	private boolean highlight;

	private Set<String> facets = new LinkedHashSet<>();

	/** Names a {@code vector_config} row. Phase 3. */
	private String profile;

	/** The calling user. Carried from day 1; unused while enforcement is a global gate. */
	private UUID userUuid;

	/**
	 * Reserved for row-level ACL. {@code null} means "no restriction", which is what every caller passes today - enforcement is a single global
	 * permission gate, exactly as it is for every CRUD list route. When row-level ACL lands, populating this is the only change the provider needs; the
	 * index already carries the matching {@code library_uuids} column.
	 */
	private Set<UUID> allowedLibraryUuids;

	/** Reserved for row-level ACL. See {@link #getAllowedLibraryUuids()}. */
	private Set<UUID> allowedSpaceUuids;

	public String getQuery() {
		return query;
	}

	public SearchRequest setQuery(String query) {
		this.query = query;
		return this;
	}

	public Set<SearchEntityType> getTypes() {
		return types;
	}

	public SearchRequest setTypes(Set<SearchEntityType> types) {
		this.types = types == null ? new LinkedHashSet<>() : types;
		return this;
	}

	public SearchRequest addType(SearchEntityType type) {
		this.types.add(type);
		return this;
	}

	public SearchMode getMode() {
		return mode;
	}

	public SearchRequest setMode(SearchMode mode) {
		this.mode = mode == null ? SearchMode.LEXICAL : mode;
		return this;
	}

	public List<Filter> getFilters() {
		return filters;
	}

	public SearchRequest setFilters(List<Filter> filters) {
		this.filters = filters == null ? new ArrayList<>() : filters;
		return this;
	}

	public String getMimeTypePrefix() {
		return mimeTypePrefix;
	}

	public SearchRequest setMimeTypePrefix(String mimeTypePrefix) {
		this.mimeTypePrefix = mimeTypePrefix;
		return this;
	}

	public UUID getLibraryUuid() {
		return libraryUuid;
	}

	public SearchRequest setLibraryUuid(UUID libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public UUID getSpaceUuid() {
		return spaceUuid;
	}

	public SearchRequest setSpaceUuid(UUID spaceUuid) {
		this.spaceUuid = spaceUuid;
		return this;
	}

	public UUID getCollectionUuid() {
		return collectionUuid;
	}

	public SearchRequest setCollectionUuid(UUID collectionUuid) {
		this.collectionUuid = collectionUuid;
		return this;
	}

	public UUID getClusterUuid() {
		return clusterUuid;
	}

	public SearchRequest setClusterUuid(UUID clusterUuid) {
		this.clusterUuid = clusterUuid;
		return this;
	}

	public List<String> getTags() {
		return tags;
	}

	public SearchRequest setTags(List<String> tags) {
		this.tags = tags == null ? new ArrayList<>() : tags;
		return this;
	}

	public Instant getCreatedFrom() {
		return createdFrom;
	}

	public SearchRequest setCreatedFrom(Instant createdFrom) {
		this.createdFrom = createdFrom;
		return this;
	}

	public Instant getCreatedTo() {
		return createdTo;
	}

	public SearchRequest setCreatedTo(Instant createdTo) {
		this.createdTo = createdTo;
		return this;
	}

	public String getLang() {
		return lang;
	}

	public SearchRequest setLang(String lang) {
		this.lang = lang;
		return this;
	}

	public int getLimit() {
		return limit;
	}

	public SearchRequest setLimit(int limit) {
		this.limit = limit;
		return this;
	}

	public int getOffset() {
		return offset;
	}

	public SearchRequest setOffset(int offset) {
		this.offset = offset;
		return this;
	}

	public String getCursor() {
		return cursor;
	}

	public SearchRequest setCursor(String cursor) {
		this.cursor = cursor;
		return this;
	}

	public SearchSortMode getSort() {
		return sort;
	}

	public SearchRequest setSort(SearchSortMode sort) {
		this.sort = sort == null ? SearchSortMode.RELEVANCE : sort;
		return this;
	}

	public boolean isHighlight() {
		return highlight;
	}

	public SearchRequest setHighlight(boolean highlight) {
		this.highlight = highlight;
		return this;
	}

	public Set<String> getFacets() {
		return facets;
	}

	public SearchRequest setFacets(Set<String> facets) {
		this.facets = facets == null ? new LinkedHashSet<>() : facets;
		return this;
	}

	public String getProfile() {
		return profile;
	}

	public SearchRequest setProfile(String profile) {
		this.profile = profile;
		return this;
	}

	public UUID getUserUuid() {
		return userUuid;
	}

	public SearchRequest setUserUuid(UUID userUuid) {
		this.userUuid = userUuid;
		return this;
	}

	public Set<UUID> getAllowedLibraryUuids() {
		return allowedLibraryUuids;
	}

	public SearchRequest setAllowedLibraryUuids(Set<UUID> allowedLibraryUuids) {
		this.allowedLibraryUuids = allowedLibraryUuids;
		return this;
	}

	public Set<UUID> getAllowedSpaceUuids() {
		return allowedSpaceUuids;
	}

	public SearchRequest setAllowedSpaceUuids(Set<UUID> allowedSpaceUuids) {
		this.allowedSpaceUuids = allowedSpaceUuids;
		return this;
	}

	@Override
	public String toString() {
		return "SearchRequest[q=" + query + ", types=" + types + ", mode=" + mode + ", limit=" + limit + ", offset=" + offset + "]";
	}
}
