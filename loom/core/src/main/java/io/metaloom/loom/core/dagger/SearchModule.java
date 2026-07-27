package io.metaloom.loom.core.dagger;

import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.SearchIndexer;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.db.jooq.search.NoopSearchIndexer;
import io.metaloom.loom.db.jooq.search.NoopSearchProvider;
import io.metaloom.loom.db.jooq.search.PostgresSearchProvider;

/**
 * Binds the search backend selected by {@code LOOM_SEARCH_PROVIDER}.
 *
 * <p>
 * 🔴 <b>Search must never fail server boot.</b> Search is a capability, not a dependency: if the configured provider cannot be constructed, this module
 * logs the failure and binds {@link NoopSearchProvider} instead. The search routes then answer 503 while every other route keeps working, and
 * {@code GET /api/v1/search/status} answers 200 with {@code available: false} so the UI can hide its search control rather than render one that errors
 * on every keystroke.
 * </p>
 */
@Module
public class SearchModule {

	private static final Logger log = LoggerFactory.getLogger(SearchModule.class);

	@Provides
	@Singleton
	public SearchOptions searchOptions(LoomOptions options) {
		return options.getSearch();
	}

	@Provides
	@Singleton
	public SearchProvider searchProvider(SearchOptions options, Provider<PostgresSearchProvider> postgres) {
		if (!options.isEnabled()) {
			log.info("Search is disabled (LOOM_SEARCH_ENABLED=false)");
			return new NoopSearchProvider("Search is disabled on this deployment.");
		}
		String provider = options.getProvider();
		try {
			if (SearchOptions.PROVIDER_POSTGRES.equalsIgnoreCase(provider)) {
				log.info("Using the postgres search provider");
				return postgres.get();
			}
			if (SearchOptions.PROVIDER_ELASTICSEARCH.equalsIgnoreCase(provider)) {
				// Phase 2. Degrade honestly rather than silently answering with a different backend.
				log.warn("The elasticsearch search provider is not implemented yet; search is unavailable");
				return new NoopSearchProvider("The elasticsearch search provider is not implemented yet.");
			}
			log.info("Search provider is set to '{}'; search is unavailable", provider);
			return new NoopSearchProvider("No search provider is configured (LOOM_SEARCH_PROVIDER=" + provider + ").");
		} catch (Exception e) {
			log.error("Search provider '{}' failed to start; search will be unavailable", provider, e);
			return new NoopSearchProvider("The configured search provider failed to start: " + e.getMessage());
		}
	}

	@Provides
	@Singleton
	public SearchIndexer searchIndexer(NoopSearchIndexer indexer) {
		// The Postgres index is maintained synchronously by database triggers, so there is nothing for
		// an indexer to push. Phase 2 replaces this with the Elasticsearch indexer.
		return indexer;
	}
}
