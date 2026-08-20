package io.metaloom.loom.mcp.tool.impl.search;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchSortMode;
import io.metaloom.loom.mcp.tool.impl.search.SearchVocabulary.Match;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The bounded filter object behind {@code find_assets}, and the translation of it into a {@link SearchRequest}.
 *
 * <p>
 * <b>Why a closed key set.</b> Every key here maps to a predicate the index can actually serve. An unrecognised key is a hard error naming the ones
 * that exist, never a silent no-op - that is the failure mode this whole design is arranged against, and the tree already has an example of it: the
 * pre-2026-08-16 {@code search_assets} declared {@code mimeType} in its schema and read nothing, so a model could narrow a search and be told
 * confidently about results that were never narrowed.
 * </p>
 *
 * <p>
 * <b>Why an unresolvable name is an error too.</b> If "pete" matches no user, running the search without the creator clause returns everybody's assets,
 * and the model - which asked for pete's - reports them as pete's. Dropping a clause is worse than refusing the call, because the refusal is visible
 * and the wrong answer is not. Same for an ambiguous name: the alternatives come back so the model can ask rather than guess.
 * </p>
 *
 * <p>
 * <b>Free text is optional.</b> "Everything pete uploaded yesterday" carries no search term, only filters. The provider accepts a termless request when
 * at least one filter narrows it, so a caller never has to invent a word to search for.
 * </p>
 */
public final class FindAssetsQuery {

	/**
	 * The accepted keys. This set is the contract: it is what the schema advertises, what the validator enforces, and what the error message lists.
	 */
	static final Set<String> KEYS = new LinkedHashSet<>(List.of(
		"text", "mimeType", "creator", "library", "space", "collection", "tags",
		"when", "createdFrom", "createdTo", "types", "sort", "mode", "limit", "offset", "highlight", "timezone"));

	private static final Set<String> ALLOWED_TYPES = Set.of(SearchEntityType.ASSET.id(), SearchEntityType.TRANSCRIPT.id());

	private FindAssetsQuery() {
	}

	/**
	 * The outcome of translating the filter object.
	 *
	 * @param request
	 *            the query to run, or null when {@code error} is set
	 * @param applied
	 *            what the server actually did, in the caller's words - "creator: Pete Miller (pete)", "created: yesterday". Reported back so the model
	 *            can tell the user what was searched rather than paraphrasing its own request.
	 * @param error
	 *            a readable refusal, or null. Written for a model to act on: it names what went wrong and what would fix it.
	 */
	public record Plan(SearchRequest request, List<String> applied, String error) {

		public boolean isError() {
			return error != null;
		}
	}

	private static Plan refuse(String message) {
		return new Plan(null, List.of(), message);
	}

	/**
	 * Translate the filter object.
	 *
	 * @param args
	 *            the model-authored arguments
	 * @param vocabulary
	 *            resolves names to uuids
	 * @param options
	 *            supplies the paging caps, so the tool cannot be talked into a deep page
	 * @param now
	 *            reference instant for relative dates
	 */
	public static Plan build(JsonObject args, SearchVocabulary vocabulary, SearchOptions options, Instant now) {
		JsonObject input = args == null ? new JsonObject() : args;

		List<String> unknown = input.fieldNames().stream().filter(name -> !KEYS.contains(name)).sorted().toList();
		if (!unknown.isEmpty()) {
			return refuse("Unknown parameter" + (unknown.size() == 1 ? " " : "s ") + String.join(", ", unknown)
				+ ". This tool accepts exactly: " + String.join(", ", KEYS) + ".");
		}

		ZoneId zone;
		try {
			String timezone = string(input, "timezone");
			zone = timezone == null ? ZoneId.systemDefault() : ZoneId.of(timezone);
		} catch (java.time.DateTimeException e) {
			return refuse("Unknown timezone '" + input.getString("timezone") + "'. Use an IANA zone id such as Europe/Vienna, or omit it.");
		}

		SearchRequest request = new SearchRequest();
		List<String> applied = new ArrayList<>();
		// Whether anything actually restricts the result set. Kept apart from `applied`, which is the
		// human-readable report and also carries choices that select or order rather than restrict -
		// types, sort, mode. A termless call narrowed only by those would page the whole catalogue.
		boolean narrowed = false;

		String text = string(input, "text");
		if (text != null) {
			if (text.length() > SearchRequest.MAX_QUERY_LENGTH) {
				return refuse("The text is longer than the " + SearchRequest.MAX_QUERY_LENGTH + " character limit. Shorten it to the words that matter.");
			}
			request.setQuery(text);
			applied.add("text: \"" + text + "\"");
		}

		// --- entity types ------------------------------------------------------------------------
		Set<SearchEntityType> types = new LinkedHashSet<>();
		JsonArray requestedTypes = array(input, "types");
		if (requestedTypes == null) {
			types.add(SearchEntityType.ASSET);
		} else {
			for (Object entry : requestedTypes) {
				String id = String.valueOf(entry).trim().toLowerCase(Locale.ROOT);
				if (!ALLOWED_TYPES.contains(id)) {
					return refuse("Unsupported type '" + entry + "'. This tool searches " + String.join(" and ", ALLOWED_TYPES)
						+ ". An asset's own document already contains its transcripts, captions, OCR text, detection labels and tags, "
						+ "so 'asset' finds those; use 'transcript' only when you need the timecode of the passage.");
				}
				types.add(SearchEntityType.fromString(id));
			}
			if (types.isEmpty()) {
				types.add(SearchEntityType.ASSET);
			}
			applied.add("types: " + types.stream().map(SearchEntityType::id).toList());
		}
		request.setTypes(types);

		// --- scope resolution --------------------------------------------------------------------
		String creator = string(input, "creator");
		if (creator != null) {
			Match match = vocabulary.resolveUser(creator);
			String problem = problem(match, "creator", creator, "user");
			if (problem != null) {
				return refuse(problem);
			}
			request.setCreatorUuid(match.uuid());
			applied.add("creator: " + match.label());
			narrowed = true;
		}

		String library = string(input, "library");
		if (library != null) {
			Match match = vocabulary.resolveLibrary(library);
			String problem = problem(match, "library", library, "library");
			if (problem != null) {
				return refuse(problem);
			}
			request.setLibraryUuid(match.uuid());
			applied.add("library: " + match.label());
			narrowed = true;
		}

		String space = string(input, "space");
		if (space != null) {
			Match match = vocabulary.resolveSpace(space);
			String problem = problem(match, "space", space, "space (project)");
			if (problem != null) {
				return refuse(problem);
			}
			request.setSpaceUuid(match.uuid());
			applied.add("space: " + match.label());
			narrowed = true;
		}

		String collection = string(input, "collection");
		if (collection != null) {
			Match match = vocabulary.resolveCollection(collection);
			String problem = problem(match, "collection", collection, "collection");
			if (problem != null) {
				return refuse(problem);
			}
			request.setCollectionUuid(match.uuid());
			applied.add("collection: " + match.label());
			narrowed = true;
		}

		JsonArray tags = array(input, "tags");
		if (tags != null && !tags.isEmpty()) {
			List<String> names = new ArrayList<>();
			for (Object entry : tags) {
				String value = String.valueOf(entry).trim();
				if (value.isEmpty()) {
					continue;
				}
				Match match = vocabulary.resolveTag(value);
				String problem = problem(match, "tags", value, "tag");
				if (problem != null) {
					return refuse(problem);
				}
				names.add(match.label());
			}
			if (!names.isEmpty()) {
				request.setTags(names);
				applied.add("tags: " + names);
				narrowed = true;
			}
		}

		// --- dates -------------------------------------------------------------------------------
		String when = string(input, "when");
		if (when != null) {
			if (input.getValue("createdFrom") != null || input.getValue("createdTo") != null) {
				return refuse("Use either 'when' or the 'createdFrom'/'createdTo' pair, not both.");
			}
			DateExpressions.Range range = DateExpressions.resolve(when, now, zone);
			if (range == null) {
				return refuse(dateHelp("when", when));
			}
			request.setCreatedFrom(range.from()).setCreatedTo(range.to());
			applied.add("created: " + range.label() + " (" + zone + ")");
			narrowed = true;
		}

		String from = string(input, "createdFrom");
		if (from != null) {
			DateExpressions.Range range = DateExpressions.resolve(from, now, zone);
			if (range == null) {
				return refuse(dateHelp("createdFrom", from));
			}
			// The lower bound opens at the start of whatever period was named.
			request.setCreatedFrom(range.from());
			applied.add("created from: " + range.label() + " (" + zone + ")");
			narrowed = true;
		}

		String to = string(input, "createdTo");
		if (to != null) {
			DateExpressions.Range range = DateExpressions.resolve(to, now, zone);
			if (range == null) {
				return refuse(dateHelp("createdTo", to));
			}
			// ...and the upper bound closes at the end of it, so "createdTo: 2026-08-18" includes that day
			// rather than stopping at its first instant.
			request.setCreatedTo(range.to());
			applied.add("created to: " + range.label() + " (" + zone + ")");
			narrowed = true;
		}

		if (request.getCreatedFrom() != null && request.getCreatedTo() != null && request.getCreatedFrom().isAfter(request.getCreatedTo())) {
			return refuse("The date range is inverted: createdFrom is after createdTo.");
		}

		// --- mime, sort, mode --------------------------------------------------------------------
		String mimeType = string(input, "mimeType");
		if (mimeType != null) {
			// A model reaches for the conventional "video/*"; the index matches a LIKE prefix, where a
			// trailing star matches nothing at all. Normalising is the difference between a result and a
			// confident empty answer.
			String prefix = mimeType.trim();
			while (prefix.endsWith("*")) {
				prefix = prefix.substring(0, prefix.length() - 1);
			}
			if (!prefix.isEmpty()) {
				request.setMimeTypePrefix(prefix);
				applied.add("mimeType: " + prefix + "*");
				narrowed = true;
			}
		}

		String sort = string(input, "sort");
		if (sort != null) {
			SearchSortMode mode = enumValue(SearchSortMode.class, sort);
			if (mode == null) {
				return refuse("Unknown sort '" + sort + "'. Use one of: RELEVANCE, NEWEST, OLDEST, NAME, SIZE.");
			}
			request.setSort(mode);
			applied.add("sort: " + mode);
		} else if (request.getQuery() == null) {
			// Nothing to rank against, so newest-first is the only ordering that means anything.
			request.setSort(SearchSortMode.NEWEST);
		}

		String mode = string(input, "mode");
		if (mode != null) {
			SearchMode searchMode = enumValue(SearchMode.class, mode);
			if (searchMode == null) {
				return refuse("Unknown mode '" + mode + "'. Use one of: LEXICAL, SEMANTIC, HYBRID.");
			}
			if (searchMode != SearchMode.LEXICAL && request.getQuery() == null) {
				return refuse("Mode " + searchMode + " ranks by meaning and needs a 'text' to compare against.");
			}
			request.setMode(searchMode);
			applied.add("mode: " + searchMode);
		}

		if (Boolean.TRUE.equals(input.getBoolean("highlight"))) {
			request.setHighlight(true);
		}

		// --- paging ------------------------------------------------------------------------------
		Integer limit = integer(input, "limit");
		if (limit != null && limit <= 0) {
			return refuse("The limit must be a positive number.");
		}
		request.setLimit(Math.min(limit == null ? 25 : limit, options.getMaxLimit()));

		Integer offset = integer(input, "offset");
		if (offset != null) {
			if (offset < 0) {
				return refuse("The offset must not be negative.");
			}
			if (offset > options.getMaxOffset()) {
				return refuse("This search backend does not page past offset " + options.getMaxOffset() + ". Narrow the query instead.");
			}
			request.setOffset(offset);
		}

		// The provider refuses a request that neither carries a term nor narrows anything, but its message
		// is written for an HTTP client. Answering here lets the model read what it should have sent.
		if (request.getQuery() == null && !narrowed) {
			return refuse("Nothing to search for. Give a 'text' to match, or at least one filter "
				+ "(creator, collection, library, space, tags, mimeType, when/createdFrom/createdTo).");
		}

		return new Plan(request, List.copyOf(applied), null);
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Turn a failed resolution into a message the model can act on, or null when it resolved.
	 */
	private static String problem(Match match, String parameter, String value, String entity) {
		if (match.isResolved()) {
			return null;
		}
		if (match.isAmbiguous()) {
			return "The " + parameter + " '" + value + "' matches more than one " + entity + ": "
				+ String.join(", ", match.candidates())
				+ ". Ask which one is meant, or pass the uuid.";
		}
		return "No " + entity + " matches the " + parameter + " '" + value + "'. Check the spelling, or pass a uuid. "
			+ "The search was not run - narrowing by something that does not exist would have returned everything instead.";
	}

	private static String dateHelp(String parameter, String value) {
		return "Could not read the date '" + value + "' in '" + parameter + "'. Accepted: " + DateExpressions.vocabulary() + ".";
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equalsIgnoreCase(value.trim())) {
				return constant;
			}
		}
		return null;
	}

	private static String string(JsonObject input, String key) {
		Object value = input.getValue(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static JsonArray array(JsonObject input, String key) {
		Object value = input.getValue(key);
		if (value == null) {
			return null;
		}
		if (value instanceof JsonArray array) {
			return array;
		}
		// A model that is asked for an array will sometimes send the single element. Accepting it is not
		// leniency for its own sake: refusing would cost a turn and teach nothing, because the schema
		// already said "array" and it did not help.
		return new JsonArray().add(String.valueOf(value));
	}

	private static Integer integer(JsonObject input, String key) {
		Object value = input.getValue(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.valueOf(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

}
