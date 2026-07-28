package io.metaloom.cortex.cli;

import java.util.HashMap;
import java.util.Map;

import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Picocli default value provider that resolves values from environment variables.
 */
public class EnvDefaultProvider implements IDefaultValueProvider {

	private static final Map<String, String> OPTION_ENV_MAP = new HashMap<>();

	static {
		OPTION_ENV_MAP.put("--hostname", "LOOM_HOST");
		OPTION_ENV_MAP.put("--port", "LOOM_PORT");
		OPTION_ENV_MAP.put("--monitoring-port", "CORTEX_MONITORING_PORT");
		OPTION_ENV_MAP.put("--meta-path", "CORTEX_META_PATH");
		OPTION_ENV_MAP.put("--node-id", "CORTEX_NODE_ID");
		OPTION_ENV_MAP.put("--node-whitelist", "CORTEX_NODE_WHITELIST");
		OPTION_ENV_MAP.put("--node-blacklist", "CORTEX_NODE_BLACKLIST");

		// S3. Needed by every worker that touches s3:// media, not only the one running the
		// s3-source node, because media is materialized lazily by whoever runs the node task.
		OPTION_ENV_MAP.put("--s3-endpoint", "CORTEX_S3_ENDPOINT");
		OPTION_ENV_MAP.put("--s3-region", "CORTEX_S3_REGION");
		OPTION_ENV_MAP.put("--s3-access-key", "CORTEX_S3_ACCESS_KEY");
		OPTION_ENV_MAP.put("--s3-secret-key", "CORTEX_S3_SECRET_KEY");
		OPTION_ENV_MAP.put("--s3-path-style", "CORTEX_S3_PATH_STYLE");
		OPTION_ENV_MAP.put("--s3-cache-path", "CORTEX_S3_CACHE_PATH");
		OPTION_ENV_MAP.put("--s3-index-path", "CORTEX_S3_INDEX_PATH");
		OPTION_ENV_MAP.put("--s3-max-cache-bytes", "CORTEX_S3_MAX_CACHE_BYTES");
		OPTION_ENV_MAP.put("--s3-max-object-size", "CORTEX_S3_MAX_OBJECT_SIZE");
		OPTION_ENV_MAP.put("--s3-reconcile-interval-ms", "CORTEX_S3_RECONCILE_INTERVAL_MS");
		OPTION_ENV_MAP.put("--s3-events-enabled", "CORTEX_S3_EVENTS_ENABLED");
		OPTION_ENV_MAP.put("--s3-events-mode", "CORTEX_S3_EVENTS_MODE");
		OPTION_ENV_MAP.put("--s3-events-webhook-path", "CORTEX_S3_EVENTS_WEBHOOK_PATH");
		OPTION_ENV_MAP.put("--s3-events-webhook-secret", "CORTEX_S3_EVENTS_WEBHOOK_SECRET");
		OPTION_ENV_MAP.put("--s3-events-queue-url", "CORTEX_S3_EVENTS_QUEUE_URL");
		OPTION_ENV_MAP.put("--s3-events-max-buffered-keys", "CORTEX_S3_EVENTS_MAX_BUFFERED_KEYS");
	}

	@Override
	public String defaultValue(ArgSpec argSpec) throws Exception {
		if (argSpec instanceof OptionSpec) {
			OptionSpec option = (OptionSpec) argSpec;
			String envVar = OPTION_ENV_MAP.get(option.longestName());
			if (envVar != null) {
				return System.getenv(envVar);
			}
		}
		return null;
	}
}
