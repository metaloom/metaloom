package io.metaloom.loom.core.dagger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.options.VectorIndexOptions;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.vector.NoopVectorIndex;

/**
 * {@link VectorIndexModule#vectorIndex(VectorIndexOptions)} binds a real Lucene backend or degrades to {@link NoopVectorIndex}, and every branch
 * runs on options and a filesystem path alone - no database, no other Dagger module needed to exercise it.
 */
public class VectorIndexModuleTest {

	private final VectorIndexModule module = new VectorIndexModule();

	@Test
	public void shouldBindNoopWhenDisabled(@TempDir Path tempDir) {
		VectorIndexOptions options = new VectorIndexOptions()
			.setProvider(VectorIndexOptions.PROVIDER_NONE)
			.setIndexPath(tempDir.resolve("vector").toString());
		VectorIndex index = module.vectorIndex(options);
		assertTrue(index instanceof NoopVectorIndex, "the vector index must default to Noop when LOOM_VECTOR_INDEX_PROVIDER=none");
	}

	@Test
	public void shouldBindNoopForAnUnknownProvider(@TempDir Path tempDir) {
		VectorIndexOptions options = new VectorIndexOptions()
			.setProvider("not-a-real-provider")
			.setIndexPath(tempDir.resolve("vector").toString());
		VectorIndex index = module.vectorIndex(options);
		// validate() would reject this at boot; the module itself must still degrade gracefully rather
		// than throwing, since options can also be constructed directly (as here, and in tests).
		assertTrue(index instanceof NoopVectorIndex, "an unknown provider must degrade to Noop, never fail boot");
	}

	@Test
	public void shouldOpenARealIndexWhenEnabledAndWritable(@TempDir Path tempDir) {
		VectorIndexOptions options = new VectorIndexOptions()
			.setProvider(VectorIndexOptions.PROVIDER_LUCENE)
			.setIndexPath(tempDir.resolve("vector-index").toString());
		VectorIndex index = module.vectorIndex(options);
		try {
			assertTrue(index.isAvailable(), "a fresh, writable directory with the lucene provider must produce a working index, not a Noop");
		} finally {
			if (index instanceof AutoCloseable closeable) {
				try {
					closeable.close();
				} catch (Exception e) {
					// best-effort cleanup
				}
			}
		}
	}
}
