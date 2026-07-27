package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.search.FacetBucket;
import io.metaloom.loom.api.search.SearchCapability;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.SearchSuggestion;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.search.SearchFacetResponse;
import io.metaloom.loom.rest.model.search.SearchHitResponse;
import io.metaloom.loom.rest.model.search.SearchMetaInfo;
import io.metaloom.loom.rest.model.search.SearchResultResponse;
import io.metaloom.loom.rest.model.search.SearchStatusResponse;
import io.metaloom.loom.rest.model.search.SearchSuggestionListResponse;
import io.metaloom.loom.rest.model.search.SearchSuggestionResponse;
import io.metaloom.loom.rest.parameter.SearchParameters;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Serves {@code /api/v1/search/*}.
 *
 * <p>
 * <b>Permission model.</b> {@link Permission#READ_SEARCH} is the wholesale gate, matching the single global check every other list route performs.
 * On top of that the requested entity types are narrowed against the existing {@code READ_*} permissions, because search is cross-entity by
 * construction: without narrowing, a role that may read tags but not assets would either see assets it must not, or see nothing at all. Types the
 * caller cannot read are dropped and named in {@code _metainfo.warnings} - returning fewer types silently would be indistinguishable from an empty
 * index.
 * </p>
 *
 * <p>
 * This is not a CRUD resource, so it extends {@link AbstractEndpointService} rather than the CRUD base class: there is no DAO, no element type, and
 * ranked results are structurally incompatible with the keyset paging the CRUD list path uses.
 * </p>
 */
@Singleton
public class SearchEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(SearchEndpointService.class);

	/**
	 * Which read permission each searchable entity type requires.
	 *
	 * <p>
	 * Transcripts are covered by {@code READ_ASSET}: a transcript is a component of an asset and has no permission of its own.
	 * </p>
	 */
	private static final Map<SearchEntityType, Permission> TYPE_PERMISSIONS = new EnumMap<>(SearchEntityType.class);

	static {
		TYPE_PERMISSIONS.put(SearchEntityType.ASSET, Permission.READ_ASSET);
		TYPE_PERMISSIONS.put(SearchEntityType.TRANSCRIPT, Permission.READ_ASSET);
		TYPE_PERMISSIONS.put(SearchEntityType.SEGMENT, Permission.READ_ASSET);
		TYPE_PERMISSIONS.put(SearchEntityType.TAG, Permission.READ_TAG);
		TYPE_PERMISSIONS.put(SearchEntityType.ANNOTATION, Permission.READ_ANNOTATION);
		TYPE_PERMISSIONS.put(SearchEntityType.PERSON, Permission.READ_PERSON);
		TYPE_PERMISSIONS.put(SearchEntityType.COLLECTION, Permission.READ_COLLECTION);
		TYPE_PERMISSIONS.put(SearchEntityType.LIBRARY, Permission.READ_LIBRARY);
		TYPE_PERMISSIONS.put(SearchEntityType.DETECTION, Permission.READ_DETECTION);
		TYPE_PERMISSIONS.put(SearchEntityType.CLUSTER, Permission.READ_CLUSTER);
	}

	private final SearchProvider provider;

	@Inject
	public SearchEndpointService(SearchProvider provider, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.provider = provider;
	}

	/**
	 * {@code GET /api/v1/search/results} - cross-entity ranked search.
	 */
	public void searchResults(LoomRoutingContext lrc) {
		withPermissions(lrc, permissions -> {
			SearchRequest request = SearchParameters.create(lrc).toRequest();
			request.setUserUuid(lrc.userUuid());

			List<String> warnings = new ArrayList<>();
			request.setTypes(narrowTypes(request.getTypes(), permissions, warnings));

			SearchResult result = provider.search(request);
			result.getWarnings().addAll(warnings);
			lrc.send(toResponse(result, request));
		});
	}

	/**
	 * {@code GET /api/v1/search/assets} - asset-only search. A convenience over
	 * {@link #searchResults(LoomRoutingContext)} for clients that render an asset grid.
	 */
	public void searchAssets(LoomRoutingContext lrc) {
		withPermissions(lrc, permissions -> {
			if (!permissions.test(Permission.READ_ASSET)) {
				throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Missing permission " + Permission.READ_ASSET.name());
			}
			SearchRequest request = SearchParameters.create(lrc).toRequest();
			request.setUserUuid(lrc.userUuid());
			request.setTypes(Set.of(SearchEntityType.ASSET));
			lrc.send(toResponse(provider.search(request), request));
		});
	}

	/**
	 * {@code GET /api/v1/search/suggestions} - typeahead.
	 */
	public void suggestions(LoomRoutingContext lrc) {
		withPermissions(lrc, permissions -> {
			SearchParameters params = SearchParameters.create(lrc);
			SearchRequest request = params.toRequest();
			Set<SearchEntityType> types = narrowTypes(request.getTypes(), permissions, new ArrayList<>());

			SearchSuggestionListResponse response = new SearchSuggestionListResponse();
			for (SearchSuggestion suggestion : provider.suggest(request.getQuery(), types, params.limit())) {
				response.add(new SearchSuggestionResponse()
					.setText(suggestion.getText())
					.setType(suggestion.getType() == null ? null : suggestion.getType().id())
					.setUuid(suggestion.getUuid())
					.setScore(suggestion.getScore()));
			}
			lrc.send(response);
		});
	}

	/**
	 * {@code GET /api/v1/search/status}.
	 *
	 * <p>
	 * Answers 200 even when search is unavailable. A client needs to be able to ask "should I show a search box at all?" without that question itself
	 * failing.
	 * </p>
	 */
	public void status(LoomRoutingContext lrc) {
		checkPerm(lrc, Permission.READ_SEARCH, () -> {
			SearchProviderInfo info = provider.info();
			lrc.send(new SearchStatusResponse()
				.setProvider(info.getProvider())
				.setAvailable(info.isAvailable())
				.setReason(info.getReason())
				.setCapabilities(info.getCapabilities().stream().map(Enum::name).toList())
				.setDocumentCount(info.getDocumentCount())
				.setDirtyCount(info.getDirtyCount())
				.setLastSyncedAt(info.getLastSyncedAt()));
		});
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Gate on {@link Permission#READ_SEARCH}, then hand the action a non-throwing predicate it can use to narrow entity types.
	 */
	private void withPermissions(LoomRoutingContext lrc, java.util.function.Consumer<Predicate<Permission>> action) {
		lrc.permissions().onSuccess(permissions -> {
			if (!permissions.test(Permission.READ_SEARCH)) {
				throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Missing permission " + Permission.READ_SEARCH.name());
			}
			action.accept(permissions);
		}).onFailure(e -> {
			log.error("Failed to resolve permissions", e);
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
		});
	}

	/**
	 * Drop the entity types the caller may not read.
	 *
	 * @param requested
	 *            what the caller asked for; empty means "everything I am allowed to see"
	 * @param permissions
	 *            non-throwing permission predicate
	 * @param warnings
	 *            collects one message per dropped type
	 * @return the types that will actually be searched
	 * @throws LoomRestException
	 *             403 when nothing survives - the caller may search, but may read none of what they asked for
	 */
	private Set<SearchEntityType> narrowTypes(Set<SearchEntityType> requested, Predicate<Permission> permissions, List<String> warnings) {
		Set<SearchEntityType> candidates = requested.isEmpty() ? Set.of(SearchEntityType.values()) : requested;
		Set<SearchEntityType> allowed = new LinkedHashSet<>();
		Set<Permission> missing = new LinkedHashSet<>();

		for (SearchEntityType type : SearchEntityType.values()) {
			if (!candidates.contains(type)) {
				continue;
			}
			Permission required = TYPE_PERMISSIONS.get(type);
			if (required == null || permissions.test(required)) {
				allowed.add(type);
			} else {
				missing.add(required);
			}
		}

		for (Permission permission : missing) {
			warnings.add("Some entity types were excluded from this search: missing permission " + permission.name() + ".");
		}

		if (allowed.isEmpty()) {
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM,
				"You may search, but you may not read any of the requested entity types.");
		}
		return allowed;
	}

	private SearchResultResponse toResponse(SearchResult result, SearchRequest request) {
		SearchResultResponse response = new SearchResultResponse();
		for (SearchHit hit : result.getHits()) {
			response.add(new SearchHitResponse()
				.setType(hit.getType() == null ? null : hit.getType().id())
				.setUuid(hit.getUuid())
				.setAssetUuid(hit.getAssetUuid())
				.setScore(hit.getScore())
				.setTitle(hit.getTitle())
				.setSubtitle(hit.getSubtitle())
				.setMatchedIn(hit.getMatchedIn())
				.setHighlights(hit.getHighlights())
				.setTimeFromMs(hit.getTimeFromMs())
				.setMimeType(hit.getMimeType())
				.setSize(hit.getSize())
				.setSortDate(hit.getSortDate()));
		}

		Map<String, List<SearchFacetResponse>> facets = new LinkedHashMap<>();
		for (Map.Entry<String, List<FacetBucket>> entry : result.getFacets().entrySet()) {
			List<SearchFacetResponse> buckets = new ArrayList<>();
			for (FacetBucket bucket : entry.getValue()) {
				buckets.add(new SearchFacetResponse(bucket.getValue(), bucket.getCount()));
			}
			facets.put(entry.getKey(), buckets);
		}
		response.setFacets(facets);

		List<String> capabilities = new ArrayList<>();
		for (SearchCapability capability : provider.capabilities()) {
			capabilities.add(capability.name());
		}

		response.setMetainfo(new SearchMetaInfo()
			.setTotalHits(result.getTotalHits())
			.setTotalExact(result.isTotalExact())
			.setPerPage(request.getLimit())
			.setOffset(request.getOffset())
			.setNextCursor(result.getNextCursor())
			.setTookMs(result.getTookMs())
			.setProvider(result.getProviderName())
			.setCapabilities(capabilities)
			.setWarnings(result.getWarnings()));
		return response;
	}
}
