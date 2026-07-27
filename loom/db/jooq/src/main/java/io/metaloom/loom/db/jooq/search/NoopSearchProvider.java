package io.metaloom.loom.db.jooq.search;

import java.util.List;
import java.util.Set;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.search.SearchCapability;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.SearchSuggestion;

/**
 * The provider bound when search is disabled, set to {@code none}, or when the configured provider failed to start.
 *
 * <p>
 * Search is a capability, not a dependency: a broken search backend must never stop Loom from booting or break any other route. Queries answer 503
 * here, but {@code GET /api/v1/search/status} still answers 200 with {@code available: false} and a reason, so the UI can hide the search bar rather
 * than render one that errors on every keystroke.
 * </p>
 */
public class NoopSearchProvider implements SearchProvider {

	public static final String NAME = "none";

	private final String reason;

	public NoopSearchProvider(String reason) {
		this.reason = reason == null ? "Search is not configured." : reason;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public Set<SearchCapability> capabilities() {
		return Set.of();
	}

	@Override
	public SearchResult search(SearchRequest request) {
		throw new LoomRestException(503, LoomRestErrorCode.SEARCH_UNAVAILABLE, "Search is unavailable: " + reason);
	}

	@Override
	public List<SearchSuggestion> suggest(String prefix, Set<SearchEntityType> types, int limit) {
		return List.of();
	}

	@Override
	public SearchProviderInfo info() {
		return new SearchProviderInfo()
			.setProvider(NAME)
			.setAvailable(false)
			.setReason(reason);
	}
}
