package io.metaloom.loom.core.dagger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.NoopTextEmbedder;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.TextEmbedder;
import io.metaloom.loom.db.jooq.search.NoopSearchProvider;
import io.metaloom.loom.db.jooq.search.PostgresSearchProvider;

/**
 * {@link SearchModule#searchProvider} and {@link SearchModule#textEmbedder} are the same "capability, not a dependency" fail-safe pattern as the
 * similarity and vector index modules: disabled, unknown-provider and "the real provider's constructor blew up" must all degrade to a Noop rather
 * than fail boot. None of these paths touches the database - the postgres branch is deliberately not exercised here since building a real
 * {@link PostgresSearchProvider} needs a live connection, which is out of scope for a unit test.
 */
public class SearchModuleTest {

	private final SearchModule module = new SearchModule();

	/** A {@link Provider} that fails the moment it is asked to build the real provider - standing in for "postgres could not connect". */
	private static final Provider<PostgresSearchProvider> FAILING_POSTGRES_PROVIDER = () -> {
		throw new IllegalStateException("simulated connection failure");
	};

	@Test
	public void shouldBindNoopSearchProviderWhenDisabled() {
		SearchOptions options = new SearchOptions().setEnabled(false);
		SearchProvider provider = module.searchProvider(options, FAILING_POSTGRES_PROVIDER);
		assertTrue(provider instanceof NoopSearchProvider, "search must default to Noop when LOOM_SEARCH_ENABLED=false");
		assertFalse(provider.isAvailable());
	}

	@Test
	public void shouldBindNoopSearchProviderForElasticsearch() {
		SearchOptions options = new SearchOptions().setEnabled(true).setProvider(SearchOptions.PROVIDER_ELASTICSEARCH);
		SearchProvider provider = module.searchProvider(options, FAILING_POSTGRES_PROVIDER);
		assertTrue(provider instanceof NoopSearchProvider, "elasticsearch is not implemented yet and must degrade to Noop, not fail boot");
	}

	@Test
	public void shouldBindNoopSearchProviderForAnUnknownProvider() {
		SearchOptions options = new SearchOptions().setEnabled(true).setProvider("not-a-real-provider");
		SearchProvider provider = module.searchProvider(options, FAILING_POSTGRES_PROVIDER);
		assertTrue(provider instanceof NoopSearchProvider);
	}

	@Test
	public void shouldBindNoopSearchProviderWhenThePostgresProviderFailsToConstruct() {
		SearchOptions options = new SearchOptions().setEnabled(true).setProvider(SearchOptions.PROVIDER_POSTGRES);
		// The @Provides method must catch a failing provider.get() and degrade, exactly as the javadoc
		// on SearchModule promises ("search must never fail server boot") - not let the exception
		// propagate out of Dagger's object graph construction.
		SearchProvider provider = module.searchProvider(options, FAILING_POSTGRES_PROVIDER);
		assertTrue(provider instanceof NoopSearchProvider, "a postgres provider that fails to construct must degrade to Noop rather than abort boot");
	}

	@Test
	public void shouldBindNoopTextEmbedderWhenSemanticSearchIsDisabled() {
		SearchOptions options = new SearchOptions().setEnabled(true).setSemanticEnabled(false);
		TextEmbedder embedder = module.textEmbedder(options);
		assertTrue(embedder instanceof NoopTextEmbedder);
		assertFalse(embedder.isAvailable());
		assertThrows(IllegalStateException.class, () -> embedder.embed("hello"));
	}

	@Test
	public void shouldBindNoopTextEmbedderWhenSearchItselfIsDisabled() {
		SearchOptions options = new SearchOptions().setEnabled(false).setSemanticEnabled(true).setEmbedUrl("http://localhost:1")
			.setEmbedModel("m");
		TextEmbedder embedder = module.textEmbedder(options);
		assertTrue(embedder instanceof NoopTextEmbedder, "semantic search must stay off when search itself is disabled, regardless of its own flag");
	}

	@Test
	public void shouldBindNoopTextEmbedderWhenTheHostIsUnreachable() {
		SearchOptions options = new SearchOptions().setEnabled(true).setSemanticEnabled(true)
			.setEmbedUrl("http://127.0.0.1:1") // port 1 is reserved and nothing answers on it
			.setEmbedModel("test-model")
			.setEmbedTimeoutMs(500);
		TextEmbedder embedder = module.textEmbedder(options);
		assertTrue(embedder instanceof NoopTextEmbedder, "an unreachable embedding host must degrade to Noop rather than fail boot");
	}
}
