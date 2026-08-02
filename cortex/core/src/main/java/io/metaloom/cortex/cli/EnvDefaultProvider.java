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
		OPTION_ENV_MAP.put("--drain-timeout-ms", "CORTEX_DRAIN_TIMEOUT_MS");

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

		// Google Drive. Same deployment note as S3: every worker that touches gdrive:// media
		// needs these, not only the one running the gdrive-source node.
		OPTION_ENV_MAP.put("--gdrive-service-account-json", "CORTEX_GDRIVE_SERVICE_ACCOUNT_JSON");
		OPTION_ENV_MAP.put("--gdrive-service-account-file", "CORTEX_GDRIVE_SERVICE_ACCOUNT_FILE");
		OPTION_ENV_MAP.put("--gdrive-impersonate-subject", "CORTEX_GDRIVE_IMPERSONATE_SUBJECT");
		OPTION_ENV_MAP.put("--gdrive-client-id", "CORTEX_GDRIVE_CLIENT_ID");
		OPTION_ENV_MAP.put("--gdrive-client-secret", "CORTEX_GDRIVE_CLIENT_SECRET");
		OPTION_ENV_MAP.put("--gdrive-refresh-token", "CORTEX_GDRIVE_REFRESH_TOKEN");
		OPTION_ENV_MAP.put("--gdrive-scopes", "CORTEX_GDRIVE_SCOPES");
		OPTION_ENV_MAP.put("--gdrive-api-base-url", "CORTEX_GDRIVE_API_BASE_URL");
		OPTION_ENV_MAP.put("--gdrive-token-url", "CORTEX_GDRIVE_TOKEN_URL");
		OPTION_ENV_MAP.put("--gdrive-default-drive-id", "CORTEX_GDRIVE_DEFAULT_DRIVE_ID");
		OPTION_ENV_MAP.put("--gdrive-cache-path", "CORTEX_GDRIVE_CACHE_PATH");
		OPTION_ENV_MAP.put("--gdrive-index-path", "CORTEX_GDRIVE_INDEX_PATH");
		OPTION_ENV_MAP.put("--gdrive-max-cache-bytes", "CORTEX_GDRIVE_MAX_CACHE_BYTES");
		OPTION_ENV_MAP.put("--gdrive-max-object-size", "CORTEX_GDRIVE_MAX_OBJECT_SIZE");
		OPTION_ENV_MAP.put("--gdrive-reconcile-interval-ms", "CORTEX_GDRIVE_RECONCILE_INTERVAL_MS");
		OPTION_ENV_MAP.put("--gdrive-request-timeout-ms", "CORTEX_GDRIVE_REQUEST_TIMEOUT_MS");
		OPTION_ENV_MAP.put("--gdrive-max-retries", "CORTEX_GDRIVE_MAX_RETRIES");
		OPTION_ENV_MAP.put("--gdrive-export-native-docs", "CORTEX_GDRIVE_EXPORT_NATIVE_DOCS");

		// OneDrive / SharePoint, over Microsoft Graph.
		OPTION_ENV_MAP.put("--onedrive-tenant-id", "CORTEX_ONEDRIVE_TENANT_ID");
		OPTION_ENV_MAP.put("--onedrive-client-id", "CORTEX_ONEDRIVE_CLIENT_ID");
		OPTION_ENV_MAP.put("--onedrive-client-secret", "CORTEX_ONEDRIVE_CLIENT_SECRET");
		OPTION_ENV_MAP.put("--onedrive-refresh-token", "CORTEX_ONEDRIVE_REFRESH_TOKEN");
		OPTION_ENV_MAP.put("--onedrive-scopes", "CORTEX_ONEDRIVE_SCOPES");
		OPTION_ENV_MAP.put("--onedrive-api-base-url", "CORTEX_ONEDRIVE_API_BASE_URL");
		OPTION_ENV_MAP.put("--onedrive-authority-url", "CORTEX_ONEDRIVE_AUTHORITY_URL");
		OPTION_ENV_MAP.put("--onedrive-default-drive-id", "CORTEX_ONEDRIVE_DEFAULT_DRIVE_ID");
		OPTION_ENV_MAP.put("--onedrive-cache-path", "CORTEX_ONEDRIVE_CACHE_PATH");
		OPTION_ENV_MAP.put("--onedrive-index-path", "CORTEX_ONEDRIVE_INDEX_PATH");
		OPTION_ENV_MAP.put("--onedrive-max-cache-bytes", "CORTEX_ONEDRIVE_MAX_CACHE_BYTES");
		OPTION_ENV_MAP.put("--onedrive-max-object-size", "CORTEX_ONEDRIVE_MAX_OBJECT_SIZE");
		OPTION_ENV_MAP.put("--onedrive-reconcile-interval-ms", "CORTEX_ONEDRIVE_RECONCILE_INTERVAL_MS");
		OPTION_ENV_MAP.put("--onedrive-request-timeout-ms", "CORTEX_ONEDRIVE_REQUEST_TIMEOUT_MS");
		OPTION_ENV_MAP.put("--onedrive-max-retries", "CORTEX_ONEDRIVE_MAX_RETRIES");
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
