package io.metaloom.loom.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.GraphqlErrorException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.SearchTypePermissions;

/**
 * Wiring for the cross-entity {@code search} query.
 *
 * <p>The odd one out among the domain wirings: it holds no DAO and talks to the {@link SearchProvider} SPI instead. That is deliberate - anything that
 * searches has to go through the one provider, because a second query path is a second ranking and the two drift.</p>
 *
 * <p><b>Permissions.</b> {@link Permission#READ_SEARCH} is the wholesale gate; on top of it the requested entity types are narrowed against the caller's
 * read permissions exactly as {@code SearchEndpointService} narrows them over REST, using the shared {@link SearchTypePermissions} map. GraphQL can do
 * this because {@link GraphQLPermissionChecker} is a non-throwing check, which is precisely what narrowing (as opposed to rejecting) needs.</p>
 */
public class SearchWiring extends AbstractDomainWiring {

	/**
	 * Selection path of the highlight field, relative to the {@code search} field.
	 *
	 * <p>Highlighting re-parses the whole source document per returned hit and cannot use an index, so it is not run unless the client actually asked for
	 * the snippets. REST spells this as {@code ?highlight=true}; in GraphQL the selection set already says it.</p>
	 */
	private static final String HIGHLIGHTS_SELECTION = "hits/highlights";

	private final SearchProvider provider;

	public SearchWiring(SearchProvider provider) {
		this.provider = provider;
	}

	@Override
	public void wire(RuntimeWiring.Builder builder) {

		DataFetcher<SearchResult> searchFetcher = env -> {
			requirePermission(env, Permission.READ_SEARCH);

			List<String> warnings = new ArrayList<>();
			SearchRequest request = new SearchRequest()
				.setQuery(env.getArgument("q"))
				.setTypes(narrowTypes(typesArg(env), requireChecker(env), warnings))
				.setMode(modeArg(env))
				.setLimit(intArg(env, "limit", 25))
				.setOffset(intArg(env, "offset", 0))
				.setHighlight(env.getSelectionSet().contains(HIGHLIGHTS_SELECTION));

			// Term validation (presence, length), mode support, paging depth and the page size cap all live in the
			// provider, so REST, MCP and GraphQL enforce the same rules in the same place.
			SearchResult result = search(request);
			result.getWarnings().addAll(warnings);
			return result;
		};

		// SearchHit renames two properties (type -> entityType, uuid -> entityUuid) because "type" and "uuid"
		// mean something else on every other node of this schema. The property fetcher cannot bridge that.
		DataFetcher<SearchEntityType> hitTypeFetcher = env -> ((SearchHit) env.getSource()).getType();
		DataFetcher<Object> hitUuidFetcher = env -> ((SearchHit) env.getSource()).getUuid();

		builder
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("search", searchFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("SearchHit")
				.dataFetcher("entityType", hitTypeFetcher)
				.dataFetcher("entityUuid", hitUuidFetcher));
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Run the query, translating the provider's transport-shaped failures into GraphQL errors.
	 *
	 * <p>An unavailable provider (503) and a request the provider cannot serve (400 - an unsupported mode, a blank or oversized term, paging past the
	 * cap) must reach the client as errors carrying the provider's reason. Answering with an empty result instead would be read as "nothing matched",
	 * which is the one thing that is definitely not true.</p>
	 */
	private SearchResult search(SearchRequest request) {
		try {
			return provider.search(request);
		} catch (LoomRestException e) {
			throw GraphqlErrorException.newErrorException()
				.message(e.getMessage())
				.extensions(errorExtensions(e))
				.build();
		}
	}

	/**
	 * Map the provider's HTTP status onto this schema's error vocabulary.
	 *
	 * <p>The status is carried through as well, because the three 400 cases (unsupported mode, malformed term, paging past the cap) are only
	 * distinguishable by their message. The {@code LoomRestErrorCode} would say it more precisely, but {@code LoomRestException} is a split package class -
	 * {@code loom-common} ships a second copy of it whose accessor is named differently, and which of the two the JVM loads depends on the classpath.
	 * Only the members both copies agree on are safe to call here.</p>
	 */
	private static Map<String, Object> errorExtensions(LoomRestException e) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("code", switch (e.httpCode()) {
			case 400 -> "BAD_USER_INPUT";
			case 403 -> "FORBIDDEN";
			case 503 -> "SEARCH_UNAVAILABLE";
			default -> "INTERNAL_ERROR";
		});
		extensions.put("status", e.httpCode());
		return extensions;
	}

	/**
	 * Drop the entity types the caller may not read.
	 *
	 * @param requested
	 *            what the caller asked for; empty means "everything I am allowed to see"
	 * @param checker
	 *            non-throwing permission check
	 * @param warnings
	 *            collects one message per dropped type
	 * @return the types that will actually be searched
	 * @throws GraphqlErrorException
	 *             {@code FORBIDDEN} when nothing survives - the caller may search, but may read none of what they asked for. An empty result would be
	 *             indistinguishable from an empty index.
	 */
	private static Set<SearchEntityType> narrowTypes(Set<SearchEntityType> requested, GraphQLPermissionChecker checker, List<String> warnings) {
		Set<SearchEntityType> candidates = requested.isEmpty() ? Set.of(SearchEntityType.values()) : requested;
		Set<SearchEntityType> allowed = new LinkedHashSet<>();
		Set<Permission> missing = new LinkedHashSet<>();

		for (SearchEntityType type : SearchEntityType.values()) {
			if (!candidates.contains(type)) {
				continue;
			}
			Permission required = SearchTypePermissions.required(type);
			if (required == null || checker.hasPermission(required)) {
				allowed.add(type);
			} else {
				missing.add(required);
			}
		}

		for (Permission permission : missing) {
			warnings.add(SearchTypePermissions.warning(permission));
		}

		if (allowed.isEmpty()) {
			throw GraphqlErrorException.newErrorException()
				.message("You may search, but you may not read any of the requested entity types.")
				.extensions(Map.of(
					"code", "FORBIDDEN",
					"missingPermissions", missing.stream().map(Permission::name).toList()))
				.build();
		}
		return allowed;
	}

	/**
	 * Read the optional {@code types} argument. Enum arguments arrive as their name.
	 */
	private static Set<SearchEntityType> typesArg(DataFetchingEnvironment env) {
		List<?> raw = env.getArgument("types");
		Set<SearchEntityType> types = new LinkedHashSet<>();
		if (raw == null) {
			return types;
		}
		for (Object value : raw) {
			if (value instanceof SearchEntityType type) {
				types.add(type);
				continue;
			}
			SearchEntityType type = SearchEntityType.fromString(String.valueOf(value));
			if (type == null) {
				// Unreachable through a valid query - the schema enum already rejects unknown members - but a
				// silently dropped type would search less than the caller asked for.
				throw GraphqlErrorException.newErrorException()
					.message("Argument 'types' contains an unknown search entity type: " + value)
					.extensions(Map.of("code", "BAD_USER_INPUT", "argument", "types"))
					.build();
			}
			types.add(type);
		}
		return types;
	}

	/**
	 * Read the {@code mode} argument. Absent or explicitly null means {@link SearchMode#LEXICAL}, matching the schema default.
	 */
	private static SearchMode modeArg(DataFetchingEnvironment env) {
		Object raw = env.getArgument("mode");
		if (raw == null) {
			return SearchMode.LEXICAL;
		}
		if (raw instanceof SearchMode mode) {
			return mode;
		}
		SearchMode mode = SearchMode.fromString(String.valueOf(raw));
		if (mode == null) {
			throw GraphqlErrorException.newErrorException()
				.message("Argument 'mode' is not a valid search mode: " + raw)
				.extensions(Map.of("code", "BAD_USER_INPUT", "argument", "mode"))
				.build();
		}
		return mode;
	}

	/**
	 * Read an int argument. The schema carries the default, but a client may pass an explicit null.
	 */
	private static int intArg(DataFetchingEnvironment env, String name, int fallback) {
		Integer value = env.getArgument(name);
		return value == null ? fallback : value;
	}
}
