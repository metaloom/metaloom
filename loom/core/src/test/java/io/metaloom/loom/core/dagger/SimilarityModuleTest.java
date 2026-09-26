package io.metaloom.loom.core.dagger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.similarity.NoopSimilarityIndex;

/**
 * {@link SimilarityModule#similarityIndex(SimilarityOptions)} is a boot-time {@code @Provides} method with real branching logic - disabled,
 * unwritable directory, and the happy path - that never touches a database and is therefore exercisable without the rest of the Dagger graph.
 * These are the fail-safe paths the class-level javadoc promises: "similarity must never fail server boot".
 */
public class SimilarityModuleTest {

	private final SimilarityModule module = new SimilarityModule();

	@Test
	public void shouldBindNoopWhenDisabled(@TempDir Path tempDir) {
		SimilarityOptions options = new SimilarityOptions()
			.setEnabled(false)
			.setIndexPath(tempDir.resolve("similarity").toString());
		SimilarityIndex index = module.similarityIndex(options);
		assertTrue(index instanceof NoopSimilarityIndex, "similarity must default to Noop when LOOM_SIMILARITY_ENABLED=false");
	}

	@Test
	public void shouldBindNoopWhenIndexDirectoryIsUnwritable(@TempDir Path tempDir) throws IOException {
		Path readOnlyParent = tempDir.resolve("readonly");
		Files.createDirectories(readOnlyParent);
		File asFile = readOnlyParent.toFile();
		boolean writableChanged = asFile.setWritable(false);
		try {
			if (!writableChanged || asFile.canWrite()) {
				// Running as root (common in CI containers) ignores POSIX write permission bits entirely -
				// there is then no way to provoke an unwritable directory, so the fail-safe path cannot be
				// exercised and the assertion would be meaningless rather than false.
				return;
			}
			SimilarityOptions options = new SimilarityOptions()
				.setEnabled(true)
				.setIndexPath(readOnlyParent.resolve("index").toString());
			SimilarityIndex index = module.similarityIndex(options);
			assertTrue(index instanceof NoopSimilarityIndex, "an unwritable index directory must degrade to Noop rather than fail boot");
		} finally {
			asFile.setWritable(true);
		}
	}

	@Test
	public void shouldOpenARealIndexWhenEnabledAndWritable(@TempDir Path tempDir) {
		SimilarityOptions options = new SimilarityOptions()
			.setEnabled(true)
			.setIndexPath(tempDir.resolve("similarity-index").toString());
		SimilarityIndex index = module.similarityIndex(options);
		try {
			assertTrue(index.isAvailable(), "a fresh, writable directory must produce a working index, not a Noop");
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
