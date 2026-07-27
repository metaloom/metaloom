package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.SearchEndpointService;

/**
 * Search routes.
 *
 * <p>
 * {@code search} is a <b>namespace</b> with no handler mounted on it - every route below it is plural, which is what the REST guideline requires. It is
 * deliberately not a CRUD resource: results are relevance ranked, which the keyset seek used by the CRUD list routes cannot express.
 * </p>
 */
public class SearchEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(SearchEndpoint.class);

	private final SearchEndpointService service;
	private final ModelExamples examples;

	@Inject
	public SearchEndpoint(SearchEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "search";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/search";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addSearchRoute(basePath() + "/results", GET,
			"Search across assets, transcripts, tags, annotations, persons, collections, libraries and clusters",
			examples.searchResultResponseExample(),
			lrc -> {
				service.searchResults(lrc);
			});

		addSearchRoute(basePath() + "/assets", GET,
			"Search assets only",
			examples.searchResultResponseExample(),
			lrc -> {
				service.searchAssets(lrc);
			});

		addSearchRoute(basePath() + "/suggestions", GET,
			"Typeahead suggestions for a partial search term",
			examples.searchSuggestionListResponseExample(),
			lrc -> {
				service.suggestions(lrc);
			});

		// Singleton status resource. Answers 200 even when search is unavailable, so a client can
		// decide whether to show a search box without that question itself failing.
		addRoute(basePath() + "/status", GET,
			"Report which search backend is bound, whether it is available and what it can do",
			null,
			examples.searchStatusResponseExample(),
			lrc -> {
				service.status(lrc);
			});
	}
}
