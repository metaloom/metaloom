package io.metaloom.cortex.cli.dagger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CloudClientOptions;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudSupport;
import io.metaloom.cortex.cloud.CloudSupportRegistry;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.cortex.node.source.cloud.CloudDifferentialScanner;
import io.metaloom.cortex.node.source.cloud.CloudFileIndexStore;
import io.metaloom.cortex.node.source.cloud.CloudSourceNode;
import io.metaloom.cortex.node.source.cloud.CloudSourceNodeOptions;
import io.metaloom.cortex.node.source.cloud.GDriveSourceNodeOptions;
import io.metaloom.cortex.node.source.cloud.OneDriveSourceNodeOptions;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNode;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNodeOptions;
import io.metaloom.cortex.node.source.s3.S3DifferentialScanner;
import io.metaloom.cortex.node.source.s3.S3ObjectIndexStore;
import io.metaloom.cortex.node.source.s3.S3SourceNode;
import io.metaloom.cortex.node.source.s3.S3SourceNodeOptions;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.cortex.s3.event.S3EventBuffer;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.loader.NodeRegistrar;
import io.metaloom.cortex.pipeline.loader.RegistryNodeFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link NodeRegistrar} implementation that populates the shared
 * {@link RegistryNodeFactory} from the concrete cortex nodes that ship with the
 * CLI.
 *
 * <p>Processing-node kinds come from the {@code Map<String, Provider<FilesystemNode>>}
 * multibinding: every node module contributes its own {@code @IntoMap @StringKey}
 * entry, so the set of executable kinds is derived from the actual node
 * collection rather than a list hand-maintained here. The {@link Provider} keeps
 * a node uninstantiated until a task of its kind arrives, so merely advertising a
 * kind (or booting a worker) never constructs every node — some pull heavy
 * transitive dependencies (native detectors, model processors).</p>
 *
 * <p>Source nodes are pipeline-level constructs rather than {@code FilesystemNode}s,
 * so they are built directly here instead of via the {@link CortexNodeAdapter}.</p>
 */
public class RegistryNodeRegistrar implements NodeRegistrar {

	private static final Logger log = LoggerFactory.getLogger(RegistryNodeRegistrar.class);

	private final RegistryNodeFactory factory;
	private final Map<String, Provider<FilesystemNode<?, ?>>> nodeKinds;
	private final LoomMediaLoader mediaLoader;
	private final FilesystemSourceNodeOptions fsSourceOptions;
	private final S3SourceNodeOptions s3SourceOptions;
	private final S3Support s3Support;
	private final S3EventBuffer s3EventBuffer;
	private final CloudSupportRegistry cloudSupport;
	private final GDriveSourceNodeOptions gdriveSourceOptions;
	private final OneDriveSourceNodeOptions onedriveSourceOptions;
	private final CortexOptions cortexOptions;

	private boolean registered;

	public RegistryNodeRegistrar(RegistryNodeFactory factory,
		Map<String, Provider<FilesystemNode<?, ?>>> nodeKinds,
		LoomMediaLoader mediaLoader,
		FilesystemSourceNodeOptions fsSourceOptions,
		S3SourceNodeOptions s3SourceOptions,
		S3Support s3Support,
		S3EventBuffer s3EventBuffer,
		CloudSupportRegistry cloudSupport,
		GDriveSourceNodeOptions gdriveSourceOptions,
		OneDriveSourceNodeOptions onedriveSourceOptions,
		CortexOptions cortexOptions) {
		this.factory = factory;
		this.nodeKinds = nodeKinds;
		this.mediaLoader = mediaLoader;
		this.fsSourceOptions = fsSourceOptions;
		this.s3SourceOptions = s3SourceOptions;
		this.s3Support = s3Support;
		this.s3EventBuffer = s3EventBuffer;
		this.cloudSupport = cloudSupport;
		this.gdriveSourceOptions = gdriveSourceOptions;
		this.onedriveSourceOptions = onedriveSourceOptions;
		this.cortexOptions = cortexOptions;
	}

	private CloudSourceNodeOptions<?> cloudDefaults(CloudProviderId provider) {
		return provider == CloudProviderId.GDRIVE ? gdriveSourceOptions : onedriveSourceOptions;
	}

	@Override
	public synchronized void registerAll() {
		if (registered) {
			return;
		}

		// Source nodes are pipeline-level constructs rather than FilesystemNodes,
		// so they are constructed directly instead of via the CortexNodeAdapter.
		factory.register("filesystem-source", def -> filesystemSource(def, mediaLoader, fsSourceOptions, cortexOptions));

		// S3 source: only advertised when this worker actually has S3 configuration. Registering it
		// unconditionally would let Loom dispatch a source task the worker cannot serve, and the
		// failure would surface as a dead run rather than a missing capability.
		if (s3Support != null && s3Support.isActive()) {
			factory.register("s3-source", def -> s3Source(def, s3Support, s3EventBuffer, s3SourceOptions, cortexOptions));
		} else {
			log.info("S3 is not configured on this worker; the 's3-source' kind is not advertised");
		}

		// Cloud sources: one kind per provider, each advertised only when this worker holds
		// credentials for it. A single 'cloud-source' kind could not express "Google yes,
		// Microsoft no", and Loom would dispatch a run this worker cannot serve.
		for (CloudProviderId provider : CloudProviderId.values()) {
			CloudSupport support = cloudSupport == null ? null : cloudSupport.get(provider);
			if (support != null && support.isActive()) {
				factory.register(provider.kind(),
					def -> cloudSource(def, provider, support, cloudDefaults(provider), cortexOptions));
			} else {
				log.info("{} is not configured on this worker; the '{}' kind is not advertised",
					provider.displayName(), provider.kind());
			}
		}

		// Asset source: run a pipeline against a single asset. Loom injects the asset's
		// stored path as the 'path' option when it dispatches an asset-scoped run.
		factory.register("asset-source", def -> assetSource(def, mediaLoader));

		// Processing nodes: each kind is contributed by its own node module via the
		// @IntoMap @StringKey multibinding, resolved lazily through the Provider.
		nodeKinds.forEach((kind, provider) ->
			factory.register(kind, def -> adapt(provider.get(), def, cortexOptions)));

		registered = true;
		log.info("Registered {} node producers with the pipeline node factory", factory.registeredTypes().size());
	}

	/**
	 * Build a {@code filesystem-source} node from its JSON definition. The
	 * selection may be given as a single {@code path} or as a {@code pathGlobs}
	 * array; when neither is present the node falls back to the configured
	 * {@link FilesystemSourceNodeOptions} defaults.
	 */
	private static PipelineNode filesystemSource(JsonObject nodeDef,
		LoomMediaLoader mediaLoader, FilesystemSourceNodeOptions defaults, CortexOptions cortexOptions) {

		String id = nodeDef.getString("id", FilesystemSourceNode.DEFAULT_ID);
		String path = nodeDef.getString("path");

		List<String> globs = readStringArray(nodeDef, "pathGlobs");
		List<String> emitStates = readStringArray(nodeDef, "emitStates");

		if (defaults != null) {
			ValidationResult result = defaults.validate();
			if (result.isInvalid()) {
				throw new IllegalStateException("Node '" + id + "' options validation failed: "
					+ String.join("; ", result.getErrors()));
			}
		}

		// Resolve the local base directory for the persisted per-root indexes:
		// an explicit indexPath option wins, otherwise derive it from the meta path.
		String configuredIndexPath = defaults != null ? defaults.getIndexPath() : null;
		java.nio.file.Path indexBaseDir;
		if (configuredIndexPath != null && !configuredIndexPath.isBlank()) {
			indexBaseDir = java.nio.file.Paths.get(configuredIndexPath).toAbsolutePath().normalize();
		} else if (cortexOptions != null && cortexOptions.getMetaPath() != null) {
			indexBaseDir = cortexOptions.getMetaPath().resolve("filesystem-index");
		} else {
			indexBaseDir = null;
		}

		return FilesystemSourceNode.create(id, mediaLoader, path, globs, emitStates, defaults, indexBaseDir);
	}

	/**
	 * Build an {@code s3-source} node from its JSON definition.
	 *
	 * <p>Only the selection comes from the definition ({@code bucket}, {@code prefix},
	 * {@code suffixes}, {@code emitStates}, {@code startAfter}, {@code useEvents}). Endpoint,
	 * region and credentials are worker-level and arrive through {@link S3Support}, so a pipeline
	 * definition - stored in the database and rendered in the editor - never carries a secret.</p>
	 */
	private static PipelineNode s3Source(JsonObject nodeDef, S3Support s3Support,
		S3EventBuffer s3EventBuffer, S3SourceNodeOptions defaults, CortexOptions cortexOptions) {

		String id = nodeDef.getString("id", S3SourceNode.DEFAULT_ID);

		if (defaults != null) {
			ValidationResult result = defaults.validate();
			if (result.isInvalid()) {
				throw new IllegalStateException("Node '" + id + "' options validation failed: "
					+ String.join("; ", result.getErrors()));
			}
		}

		java.nio.file.Path indexBaseDir = s3Support.indexBaseDir();
		if (indexBaseDir == null) {
			throw new IllegalStateException("Node '" + id + "' (s3-source) needs an index directory; "
				+ "set --s3-index-path (CORTEX_S3_INDEX_PATH) or --meta-path (CORTEX_META_PATH)");
		}

		S3ClientOptions s3Options = cortexOptions == null ? null : cortexOptions.getS3();
		// Endpoint identity scopes the index, so the same bucket name on two servers cannot share
		// - and corrupt - one index file.
		String endpointId = s3Options == null || s3Options.getEndpoint() == null
			? "aws:" + (s3Options == null ? "" : s3Options.getRegion())
			: s3Options.getEndpoint();
		long reconcileMs = s3Options == null
			? S3ClientOptions.DEFAULT_RECONCILE_INTERVAL_MS
			: s3Options.getReconcileIntervalMs();

		// The buffer is a worker-wide singleton: transports fill it continuously while each run
		// drains what it needs, so every s3-source instance must share the one instance.
		S3EventBuffer eventBuffer = s3Options != null && s3Options.getEvents().isEnabled()
			? s3EventBuffer
			: null;

		S3DifferentialScanner scanner = new S3DifferentialScanner(s3Support.store(),
			new S3ObjectIndexStore(), eventBuffer, indexBaseDir, endpointId, reconcileMs);

		return S3SourceNode.create(id, scanner, s3Support.materializer(),
			nodeDef.getString("bucket"),
			nodeDef.getString("prefix"),
			nodeDef.getString("suffixes"),
			readStringArray(nodeDef, "emitStates"),
			nodeDef.getBoolean("startAfter"),
			nodeDef.getBoolean("useEvents"),
			defaults);
	}

	/**
	 * Build a {@code gdrive-source} or {@code onedrive-source} node from its JSON definition.
	 *
	 * <p>One builder for both kinds: only the {@link CloudSupport} and the defaults differ. As with
	 * {@code s3-source}, only the selection comes from the definition ({@code driveId},
	 * {@code folderId}, {@code recursive}, {@code maxDepth}, {@code suffixes}, {@code mimeTypes},
	 * {@code emitStates}, {@code useDelta}, {@code includeTrashed}). Credentials are worker-level
	 * and arrive through {@link CloudSupport}, so a pipeline definition - stored in the database and
	 * rendered in the editor - never carries a secret.</p>
	 */
	private static PipelineNode cloudSource(JsonObject nodeDef, CloudProviderId provider,
		CloudSupport support, CloudSourceNodeOptions<?> defaults, CortexOptions cortexOptions) {

		String id = nodeDef.getString("id", provider.kind());

		if (defaults != null) {
			ValidationResult result = defaults.validate();
			if (result.isInvalid()) {
				throw new IllegalStateException("Node '" + id + "' options validation failed: "
					+ String.join("; ", result.getErrors()));
			}
		}

		java.nio.file.Path indexBaseDir = support.indexBaseDir();
		if (indexBaseDir == null) {
			throw new IllegalStateException("Node '" + id + "' (" + provider.kind() + ") needs an index directory; "
				+ "set --" + provider.scheme() + "-index-path (CORTEX_" + provider.scheme().toUpperCase()
				+ "_INDEX_PATH) or --meta-path (CORTEX_META_PATH)");
		}

		CloudClientOptions<?> clientOptions = clientOptionsFor(provider, cortexOptions);

		long reconcileMs = clientOptions == null
			? CloudClientOptions.DEFAULT_RECONCILE_INTERVAL_MS
			: clientOptions.getReconcileIntervalMs();

		// A drive id is optional for Google (blank means My Drive) and effectively required for
		// Microsoft, which is why the store resolves it rather than the caller guessing.
		String configuredDriveId = firstNonBlank(nodeDef.getString("driveId"),
			defaults == null ? null : defaults.getDriveId(),
			clientOptions == null ? null : clientOptions.getDefaultDriveId());
		String driveId;
		try {
			driveId = support.store().resolveDriveId(configuredDriveId);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Node '" + id + "' (" + provider.kind() + "): " + e.getMessage(), e);
		}

		// The export setting is a Google-only worker default that a node may raise but not invent.
		boolean exportDocs = provider == CloudProviderId.GDRIVE
			&& (defaults instanceof GDriveSourceNodeOptions gdriveDefaults && gdriveDefaults.isExportNativeDocs()
				|| Boolean.TRUE.equals(nodeDef.getBoolean("exportNativeDocs"))
				|| cortexOptions != null && cortexOptions.getGdrive() != null
					&& cortexOptions.getGdrive().isExportNativeDocs());

		CloudDifferentialScanner scanner = new CloudDifferentialScanner(support.store(),
			new CloudFileIndexStore(), indexBaseDir, reconcileMs);

		return CloudSourceNode.create(id, scanner, support.materializer(), provider, driveId,
			nodeDef.getString("folderId"),
			nodeDef.getBoolean("recursive"),
			nodeDef.getInteger("maxDepth"),
			nodeDef.getString("suffixes"),
			nodeDef.getString("mimeTypes"),
			readStringArray(nodeDef, "emitStates"),
			nodeDef.getBoolean("useDelta"),
			nodeDef.getBoolean("includeTrashed"),
			exportDocs,
			defaults);
	}

	/**
	 * The worker-level client options for a provider, or null when none are configured.
	 */
	private static CloudClientOptions<?> clientOptionsFor(CloudProviderId provider, CortexOptions cortexOptions) {
		if (cortexOptions == null) {
			return null;
		}
		return provider == CloudProviderId.GDRIVE ? cortexOptions.getGdrive() : cortexOptions.getOnedrive();
	}

	private static String firstNonBlank(String... candidates) {
		for (String candidate : candidates) {
			if (candidate != null && !candidate.isBlank()) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Build an {@code asset-source} node from its JSON definition. The single asset it emits is identified by the {@code path} option, which Loom fills
	 * in from the asset's stored binary location when it dispatches an asset-scoped run.
	 */
	private static PipelineNode assetSource(JsonObject nodeDef, LoomMediaLoader mediaLoader) {
		String id = nodeDef.getString("id", "asset-source");
		String path = nodeDef.getString("path");
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("Node '" + id + "' (asset-source) requires a 'path' option identifying the asset file");
		}
		LoomMedia media = mediaLoader.load(java.nio.file.Paths.get(path));
		return new AssetSourceNode(id, "Asset Source", media);
	}

	private static List<String> readStringArray(JsonObject nodeDef, String field) {
		List<String> values = new ArrayList<>();
		JsonArray array = nodeDef.getJsonArray(field);
		if (array != null) {
			for (int i = 0; i < array.size(); i++) {
				String value = array.getString(i);
				if (value != null && !value.isBlank()) {
					values.add(value);
				}
			}
		}
		return values;
	}

	private static PipelineNode adapt(FilesystemNode<?, ?> wrapped, JsonObject nodeDef, CortexOptions cortexOptions) {
		String id = nodeDef.getString("id", wrapped.name());
		NodeMode mode = NodeMode.valueOf(nodeDef.getString("mode", "PARALLEL"));
		boolean blocking = nodeDef.getBoolean("blocking", true);
		int concurrency = nodeDef.getInteger("concurrency", 1);
		boolean syncToLoom = nodeDef.getBoolean("syncToLoom", false);

		// Validate concurrency
		if (concurrency <= 0) {
			throw new IllegalStateException("Node '" + id + "': concurrency must be positive, got " + concurrency);
		}

		// Use timeout from JSON if specified, otherwise fall back to default from config
		long timeoutMs = nodeDef.getLong("timeoutMs", 0L);
		if (timeoutMs == 0) {
			// Try to get default timeout from config based on node type
			String nodeType = nodeDef.getString("type", wrapped.name());
			timeoutMs = cortexOptions.getDefaultTimeoutMs(nodeType);
		}

		// Validate timeout
		if (timeoutMs < 0) {
			throw new IllegalStateException("Node '" + id + "': timeoutMs must be non-negative, got " + timeoutMs);
		}

		// Per-instance configuration. Opt-in: only nodes that declare PipelineConfigurable see the
		// definition, so a node configured from the worker's YAML keeps behaving exactly as before.
		if (wrapped instanceof PipelineConfigurable configurable) {
			try {
				configurable.configure(nodeDef);
			} catch (RuntimeException e) {
				throw new IllegalStateException("Node '" + id + "' configuration failed: " + e.getMessage(), e);
			}
		}

		CortexNodeAdapter adapter = new CortexNodeAdapter(id, wrapped, mode, blocking, concurrency, timeoutMs);
		if (syncToLoom) {
			adapter.setSyncToLoom(true);
		}

		// Validate node options if available in cortexOptions
		if (cortexOptions != null && cortexOptions.getNodes() != null) {
			CortexNodeOptions nodeOptions = cortexOptions.getNodes().get(wrapped.name());
			if (nodeOptions != null) {
				ValidationResult result = nodeOptions.validate();
				if (result.isInvalid()) {
					throw new IllegalStateException("Node '" + id + "' options validation failed: " + String.join("; ", result.getErrors()));
				}
			}
		}

		return adapter;
	}
}
