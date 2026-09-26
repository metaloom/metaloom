package io.metaloom.loom.core.dagger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.graph.AssetGraphIndex;
import io.metaloom.loom.api.options.AssetGraphOptions;
import io.metaloom.loom.graph.NoopAssetGraphIndex;

/**
 * {@link AssetGraphIndexModule#assetGraphIndex(AssetGraphOptions)} follows the same "never fail boot" contract as the similarity and vector index
 * modules: disabled, an unknown provider, and the happy path, all reachable with plain options and a filesystem path.
 */
public class AssetGraphIndexModuleTest {

	private final AssetGraphIndexModule module = new AssetGraphIndexModule();

	@Test
	public void shouldBindNoopWhenDisabled(@TempDir Path tempDir) {
		AssetGraphOptions options = new AssetGraphOptions()
			.setProvider(AssetGraphOptions.PROVIDER_NONE)
			.setIndexPath(tempDir.resolve("graph").toString());
		AssetGraphIndex index = module.assetGraphIndex(options);
		assertTrue(index instanceof NoopAssetGraphIndex, "the asset graph index must default to Noop when LOOM_ASSET_GRAPH_PROVIDER=none");
	}

	@Test
	public void shouldBindNoopForAnUnknownProvider(@TempDir Path tempDir) {
		AssetGraphOptions options = new AssetGraphOptions()
			.setProvider("not-a-real-provider")
			.setIndexPath(tempDir.resolve("graph").toString());
		AssetGraphIndex index = module.assetGraphIndex(options);
		assertTrue(index instanceof NoopAssetGraphIndex, "an unknown provider must degrade to Noop, never fail boot");
	}

	@Test
	public void shouldOpenARealIndexWhenEnabledAndWritable(@TempDir Path tempDir) {
		AssetGraphOptions options = new AssetGraphOptions()
			.setProvider(AssetGraphOptions.PROVIDER_GRAPHSTORE)
			.setIndexPath(tempDir.resolve("graph-index").toString());
		AssetGraphIndex index = module.assetGraphIndex(options);
		try {
			assertTrue(index.isAvailable(), "a fresh, writable directory with the graphstore provider must produce a working index, not a Noop");
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
