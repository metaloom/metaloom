package io.metaloom.cortex.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import ch.qos.logback.classic.Level;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.api.option.S3EventOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

@Singleton
@Command(name = "cortex", mixinStandardHelpOptions = false, version = "Cortex 1.0.0-SNAPSHOT", description = "Cortex is a media processing tool", showDefaultValues = true)
public class CortexCLI implements Runnable {

	public static final int DEFAULT_PORT = 7733;
	public static final String DEFAULT_PORT_STR = "7733";
	public static final String DEFAULT_HOSTNAME = "localhost";
	public static final int DEFAULT_MONITORING_PORT = 8093;
	public static final String DEFAULT_MONITORING_PORT_STR = "8093";
	public static final String DEFAULT_META_PATH = "${user.home}/.cache/metaloom/cortex/meta";

	private String hostname = DEFAULT_HOSTNAME;

	private int port = DEFAULT_PORT;

	private int monitoringPort = DEFAULT_MONITORING_PORT;

	private Path metaPath = Paths.get(System.getProperty("user.home"), ".cache", "metaloom", "cortex", "meta");

	/** Mandatory for the online server; must be unique per worker and stable across restarts. */
	private String nodeId;

	/** How long a shutdown lets running tasks finish before handing them back to Loom. */
	private long drainTimeoutMs = 30_000;

	/** Null means "announce everything this worker can run". */
	private Set<String> nodeWhitelist;

	/** Null/empty means "refuse nothing". */
	private Set<String> nodeBlacklist;

	/**
	 * S3 connection, cache and event settings.
	 *
	 * <p>Collected as flags rather than pipeline-node parameters so that credentials never enter a
	 * pipeline definition: those are stored in Postgres and rendered in the pipeline editor, and
	 * the editor's parameter model has no secret type.</p>
	 */
	private final S3ClientOptions s3 = new S3ClientOptions();

	/**
	 * Google Drive credentials, connection and cache settings. Flags rather than pipeline-node
	 * parameters for the same reason as {@link #s3}: a service-account key placed on a node would
	 * be stored in Postgres and rendered in the editor, which has no secret parameter type.
	 */
	private final GDriveClientOptions gdrive = new GDriveClientOptions();

	/** OneDrive / SharePoint credentials, connection and cache settings. See {@link #gdrive}. */
	private final OneDriveClientOptions onedrive = new OneDriveClientOptions();

	@Spec
	CommandSpec spec;

	@Inject
	public CortexCLI() {
	}

	@Option(names = "-v", scope = ScopeType.INHERIT)
	public void setVerbose(boolean[] verbose) {
		Level level = Level.INFO;
		if (verbose.length > 0) {
			level = Level.DEBUG;
		}
		if (verbose.length >= 1) {
			level = Level.TRACE;
		}
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
		root.setLevel(level);
	}

	public String getHostname() {
		return hostname;
	}

	@Option(names = { "-h", "--hostname" }, description = "Loom server hostname. Env: LOOM_HOST", defaultValue = DEFAULT_HOSTNAME, scope = ScopeType.INHERIT)
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public int getPort() {
		return port;
	}

	@Option(names = { "-p", "--port" }, description = "Loom server HTTP port. Env: LOOM_PORT", defaultValue = DEFAULT_PORT_STR, scope = ScopeType.INHERIT)
	public void setPort(int port) {
		this.port = port;
	}

	public int getMonitoringPort() {
		return monitoringPort;
	}

	@Option(names = { "--monitoring-port" }, description = "Monitoring HTTP port. Env: CORTEX_MONITORING_PORT", defaultValue = DEFAULT_MONITORING_PORT_STR, scope = ScopeType.INHERIT)
	public void setMonitoringPort(int monitoringPort) {
		this.monitoringPort = monitoringPort;
	}

	public Path getMetaPath() {
		return metaPath;
	}

	@Option(names = { "--meta-path" }, description = "Base path for metadata storage. Env: CORTEX_META_PATH", defaultValue = DEFAULT_META_PATH, scope = ScopeType.INHERIT)
	public void setMetaPath(Path metaPath) {
		this.metaPath = metaPath;
	}

	public String getNodeId() {
		return nodeId;
	}

	/**
	 * @return true when a non-blank node id has been configured.
	 */
	public boolean hasNodeId() {
		return nodeId != null && !nodeId.isBlank();
	}

	@Option(names = { "--node-id" }, description = "Stable identity this worker registers under. "
		+ "Required to run the server. Must be unique per worker and stable across restarts - Loom keys registration, "
		+ "node-kind restrictions and run attribution on it and rejects a duplicate. Env: CORTEX_NODE_ID", scope = ScopeType.INHERIT)
	public void setNodeId(String nodeId) {
		this.nodeId = nodeId;
	}

	public long getDrainTimeoutMs() {
		return drainTimeoutMs;
	}

	@Option(names = { "--drain-timeout-ms" }, description = "How long a shutdown lets running tasks finish before "
		+ "handing them back to Loom for re-placement. Raise it alongside the orchestrator's termination grace period "
		+ "when running minutes-long nodes; killing the process mid-drain falls back to lease expiry. "
		+ "Env: CORTEX_DRAIN_TIMEOUT_MS", defaultValue = "30000", scope = ScopeType.INHERIT)
	public void setDrainTimeoutMs(long drainTimeoutMs) {
		this.drainTimeoutMs = drainTimeoutMs;
	}

	public Set<String> getNodeWhitelist() {
		return nodeWhitelist;
	}

	@Option(names = { "--node-whitelist" }, split = ",", description = "Node kinds this worker will execute, comma separated. Announces everything it can run when unset. Env: CORTEX_NODE_WHITELIST", scope = ScopeType.INHERIT)
	public void setNodeWhitelist(Set<String> nodeWhitelist) {
		this.nodeWhitelist = nodeWhitelist;
	}

	public Set<String> getNodeBlacklist() {
		return nodeBlacklist;
	}

	@Option(names = { "--node-blacklist" }, split = ",", description = "Node kinds this worker refuses, comma separated. Takes precedence over the whitelist. Refuses nothing when unset. Env: CORTEX_NODE_BLACKLIST", scope = ScopeType.INHERIT)
	public void setNodeBlacklist(Set<String> nodeBlacklist) {
		this.nodeBlacklist = nodeBlacklist;
	}

	public S3ClientOptions getS3() {
		return s3;
	}

	@Option(names = { "--s3-endpoint" }, description = "S3 endpoint override, e.g. http://minio:9000. "
		+ "Leave unset for real AWS. Env: CORTEX_S3_ENDPOINT", scope = ScopeType.INHERIT)
	public void setS3Endpoint(String endpoint) {
		s3.setEndpoint(endpoint);
	}

	@Option(names = { "--s3-region" }, description = "S3 region. Env: CORTEX_S3_REGION", scope = ScopeType.INHERIT)
	public void setS3Region(String region) {
		s3.setRegion(region);
	}

	@Option(names = { "--s3-access-key" }, description = "S3 access key. Falls back to the AWS default "
		+ "credentials chain (env, profile, instance role, IRSA) when unset. Env: CORTEX_S3_ACCESS_KEY", scope = ScopeType.INHERIT)
	public void setS3AccessKey(String accessKey) {
		s3.setAccessKey(accessKey);
	}

	@Option(names = { "--s3-secret-key" }, description = "S3 secret key. Env: CORTEX_S3_SECRET_KEY", scope = ScopeType.INHERIT)
	public void setS3SecretKey(String secretKey) {
		s3.setSecretKey(secretKey);
	}

	@Option(names = { "--s3-path-style" }, description = "Use path-style bucket addressing. Defaults to true "
		+ "whenever an endpoint is set, which is what MinIO and most gateways need. Env: CORTEX_S3_PATH_STYLE", scope = ScopeType.INHERIT)
	public void setS3PathStyle(Boolean pathStyle) {
		s3.setPathStyleAccess(pathStyle);
	}

	@Option(names = { "--s3-cache-path" }, description = "Directory for materialized S3 objects. "
		+ "Defaults to <meta-path>/s3_bin. Env: CORTEX_S3_CACHE_PATH", scope = ScopeType.INHERIT)
	public void setS3CachePath(String cachePath) {
		s3.setCachePath(cachePath);
	}

	@Option(names = { "--s3-index-path" }, description = "Directory for persisted per-bucket object indexes. "
		+ "Defaults to <meta-path>/s3-index. Env: CORTEX_S3_INDEX_PATH", scope = ScopeType.INHERIT)
	public void setS3IndexPath(String indexPath) {
		s3.setIndexPath(indexPath);
	}

	@Option(names = { "--s3-max-cache-bytes" }, description = "Size budget for the S3 media cache; oldest "
		+ "entries are evicted past it. 0 disables eviction. Env: CORTEX_S3_MAX_CACHE_BYTES", scope = ScopeType.INHERIT)
	public void setS3MaxCacheBytes(long maxCacheBytes) {
		s3.setMaxCacheBytes(maxCacheBytes);
	}

	@Option(names = { "--s3-max-object-size" }, description = "Largest object to materialize, in bytes. "
		+ "0 means unbounded. Env: CORTEX_S3_MAX_OBJECT_SIZE", scope = ScopeType.INHERIT)
	public void setS3MaxObjectSize(long maxObjectSize) {
		s3.setMaxObjectSize(maxObjectSize);
	}

	@Option(names = { "--s3-reconcile-interval-ms" }, description = "How long the event fast path may be "
		+ "trusted before a full listing is forced. Bucket notifications can be lost, so this reconcile pass is "
		+ "what keeps event-driven scanning correct. Env: CORTEX_S3_RECONCILE_INTERVAL_MS", scope = ScopeType.INHERIT)
	public void setS3ReconcileIntervalMs(long reconcileIntervalMs) {
		s3.setReconcileIntervalMs(reconcileIntervalMs);
	}

	@Option(names = { "--s3-events-enabled" }, description = "Accept S3 bucket notifications so a run can skip "
		+ "listing the bucket. Env: CORTEX_S3_EVENTS_ENABLED", scope = ScopeType.INHERIT)
	public void setS3EventsEnabled(boolean enabled) {
		s3.getEvents().setEnabled(enabled);
	}

	@Option(names = { "--s3-events-mode" }, description = "How change hints arrive: WEBHOOK (MinIO notify_webhook, "
		+ "posted to the monitoring port) or SQS. Env: CORTEX_S3_EVENTS_MODE", scope = ScopeType.INHERIT)
	public void setS3EventsMode(S3EventOptions.Mode mode) {
		s3.getEvents().setMode(mode);
	}

	@Option(names = { "--s3-events-webhook-path" }, description = "Route the webhook listener registers on the "
		+ "monitoring server. Env: CORTEX_S3_EVENTS_WEBHOOK_PATH", scope = ScopeType.INHERIT)
	public void setS3EventsWebhookPath(String webhookPath) {
		s3.getEvents().setWebhookPath(webhookPath);
	}

	@Option(names = { "--s3-events-webhook-secret" }, description = "Shared secret required in the X-Cortex-S3-Token "
		+ "header. Required when webhook events are enabled. Env: CORTEX_S3_EVENTS_WEBHOOK_SECRET", scope = ScopeType.INHERIT)
	public void setS3EventsWebhookSecret(String webhookSecret) {
		s3.getEvents().setWebhookSecret(webhookSecret);
	}

	@Option(names = { "--s3-events-queue-url" }, description = "SQS queue URL fed by S3 notifications. "
		+ "Env: CORTEX_S3_EVENTS_QUEUE_URL", scope = ScopeType.INHERIT)
	public void setS3EventsQueueUrl(String queueUrl) {
		s3.getEvents().setQueueUrl(queueUrl);
	}

	@Option(names = { "--s3-events-max-buffered-keys" }, description = "Ceiling on buffered change hints; on "
		+ "overflow the next run falls back to a full listing instead of dropping hints. "
		+ "Env: CORTEX_S3_EVENTS_MAX_BUFFERED_KEYS", scope = ScopeType.INHERIT)
	public void setS3EventsMaxBufferedKeys(int maxBufferedKeys) {
		s3.getEvents().setMaxBufferedKeys(maxBufferedKeys);
	}

	// --- Google Drive -------------------------------------------------------------------

	public GDriveClientOptions getGdrive() {
		return gdrive;
	}

	@Option(names = { "--gdrive-service-account-json" }, description = "Google service account key as inline JSON. "
		+ "The production credential: it does not expire and needs no interactive consent. "
		+ "Env: CORTEX_GDRIVE_SERVICE_ACCOUNT_JSON", scope = ScopeType.INHERIT)
	public void setGdriveServiceAccountJson(String json) {
		gdrive.setServiceAccountJson(json);
	}

	@Option(names = { "--gdrive-service-account-file" }, description = "Path to a Google service account key file. "
		+ "An alternative to the inline form, not a fallback for it. Env: CORTEX_GDRIVE_SERVICE_ACCOUNT_FILE", scope = ScopeType.INHERIT)
	public void setGdriveServiceAccountFile(String file) {
		gdrive.setServiceAccountFile(file);
	}

	@Option(names = { "--gdrive-impersonate-subject" }, description = "User to impersonate through domain-wide "
		+ "delegation, e.g. archive@example.com. Required to read a specific person's My Drive rather than the "
		+ "service account's own. Env: CORTEX_GDRIVE_IMPERSONATE_SUBJECT", scope = ScopeType.INHERIT)
	public void setGdriveImpersonateSubject(String subject) {
		gdrive.setImpersonateSubject(subject);
	}

	@Option(names = { "--gdrive-client-id" }, description = "OAuth client id, for refresh-token authentication. "
		+ "A development convenience - prefer a service account. Env: CORTEX_GDRIVE_CLIENT_ID", scope = ScopeType.INHERIT)
	public void setGdriveClientId(String clientId) {
		gdrive.setClientId(clientId);
	}

	@Option(names = { "--gdrive-client-secret" }, description = "OAuth client secret. Env: CORTEX_GDRIVE_CLIENT_SECRET", scope = ScopeType.INHERIT)
	public void setGdriveClientSecret(String clientSecret) {
		gdrive.setClientSecret(clientSecret);
	}

	@Option(names = { "--gdrive-refresh-token" }, description = "OAuth refresh token. Note that tokens issued by an "
		+ "app in Google's 'Testing' publishing status expire after seven days and cannot be renewed unattended. "
		+ "Env: CORTEX_GDRIVE_REFRESH_TOKEN", scope = ScopeType.INHERIT)
	public void setGdriveRefreshToken(String refreshToken) {
		gdrive.setRefreshToken(refreshToken);
	}

	@Option(names = { "--gdrive-scopes" }, description = "Space separated OAuth scopes. Env: CORTEX_GDRIVE_SCOPES", scope = ScopeType.INHERIT)
	public void setGdriveScopes(String scopes) {
		gdrive.setScopes(scopes);
	}

	@Option(names = { "--gdrive-api-base-url" }, description = "Google API base URL. Overridable so the client can be "
		+ "pointed at a stub server in tests. Env: CORTEX_GDRIVE_API_BASE_URL", scope = ScopeType.INHERIT)
	public void setGdriveApiBaseUrl(String url) {
		gdrive.setApiBaseUrl(url);
	}

	@Option(names = { "--gdrive-token-url" }, description = "Google OAuth token endpoint. Env: CORTEX_GDRIVE_TOKEN_URL", scope = ScopeType.INHERIT)
	public void setGdriveTokenUrl(String url) {
		gdrive.setTokenUrl(url);
	}

	@Option(names = { "--gdrive-default-drive-id" }, description = "Shared drive used when a node names none. "
		+ "Leave unset for the credential's own My Drive. Env: CORTEX_GDRIVE_DEFAULT_DRIVE_ID", scope = ScopeType.INHERIT)
	public void setGdriveDefaultDriveId(String driveId) {
		gdrive.setDefaultDriveId(driveId);
	}

	@Option(names = { "--gdrive-cache-path" }, description = "Directory for materialized Google Drive files. "
		+ "Defaults to <meta-path>/gdrive_bin. Env: CORTEX_GDRIVE_CACHE_PATH", scope = ScopeType.INHERIT)
	public void setGdriveCachePath(String cachePath) {
		gdrive.setCachePath(cachePath);
	}

	@Option(names = { "--gdrive-index-path" }, description = "Directory for persisted scan indexes. "
		+ "Defaults to <meta-path>/gdrive-index. Env: CORTEX_GDRIVE_INDEX_PATH", scope = ScopeType.INHERIT)
	public void setGdriveIndexPath(String indexPath) {
		gdrive.setIndexPath(indexPath);
	}

	@Option(names = { "--gdrive-max-cache-bytes" }, description = "Cache budget in bytes; 0 disables eviction. "
		+ "Env: CORTEX_GDRIVE_MAX_CACHE_BYTES", scope = ScopeType.INHERIT)
	public void setGdriveMaxCacheBytes(long maxCacheBytes) {
		gdrive.setMaxCacheBytes(maxCacheBytes);
	}

	@Option(names = { "--gdrive-max-object-size" }, description = "Largest file to materialize in bytes; 0 is "
		+ "unbounded. Env: CORTEX_GDRIVE_MAX_OBJECT_SIZE", scope = ScopeType.INHERIT)
	public void setGdriveMaxObjectSize(long maxObjectSize) {
		gdrive.setMaxObjectSize(maxObjectSize);
	}

	@Option(names = { "--gdrive-reconcile-interval-ms" }, description = "How long the change feed may be trusted "
		+ "before a full folder walk is forced. Env: CORTEX_GDRIVE_RECONCILE_INTERVAL_MS", scope = ScopeType.INHERIT)
	public void setGdriveReconcileIntervalMs(long intervalMs) {
		gdrive.setReconcileIntervalMs(intervalMs);
	}

	@Option(names = { "--gdrive-request-timeout-ms" }, description = "Per-request timeout. Env: CORTEX_GDRIVE_REQUEST_TIMEOUT_MS", scope = ScopeType.INHERIT)
	public void setGdriveRequestTimeoutMs(long timeoutMs) {
		gdrive.setRequestTimeoutMs(timeoutMs);
	}

	@Option(names = { "--gdrive-max-retries" }, description = "How often a throttled request is retried. "
		+ "Env: CORTEX_GDRIVE_MAX_RETRIES", scope = ScopeType.INHERIT)
	public void setGdriveMaxRetries(int maxRetries) {
		gdrive.setMaxRetries(maxRetries);
	}

	@Option(names = { "--gdrive-export-native-docs" }, description = "Export Google Docs, Sheets and Slides instead "
		+ "of skipping them. Off by default: exporting is lossy and capped at 10 MB. "
		+ "Env: CORTEX_GDRIVE_EXPORT_NATIVE_DOCS", scope = ScopeType.INHERIT)
	public void setGdriveExportNativeDocs(boolean exportNativeDocs) {
		gdrive.setExportNativeDocs(exportNativeDocs);
	}

	// --- OneDrive / SharePoint ----------------------------------------------------------

	public OneDriveClientOptions getOnedrive() {
		return onedrive;
	}

	@Option(names = { "--onedrive-tenant-id" }, description = "Microsoft Entra tenant id. Required for app-only "
		+ "access; 'common' only works with a delegated refresh token. Env: CORTEX_ONEDRIVE_TENANT_ID", scope = ScopeType.INHERIT)
	public void setOnedriveTenantId(String tenantId) {
		onedrive.setTenantId(tenantId);
	}

	@Option(names = { "--onedrive-client-id" }, description = "Application (client) id. Env: CORTEX_ONEDRIVE_CLIENT_ID", scope = ScopeType.INHERIT)
	public void setOnedriveClientId(String clientId) {
		onedrive.setClientId(clientId);
	}

	@Option(names = { "--onedrive-client-secret" }, description = "Application client secret. Env: CORTEX_ONEDRIVE_CLIENT_SECRET", scope = ScopeType.INHERIT)
	public void setOnedriveClientSecret(String clientSecret) {
		onedrive.setClientSecret(clientSecret);
	}

	@Option(names = { "--onedrive-refresh-token" }, description = "Delegated OAuth refresh token. A development "
		+ "convenience: Microsoft rotates it on every use and this worker cannot persist the replacement. "
		+ "Env: CORTEX_ONEDRIVE_REFRESH_TOKEN", scope = ScopeType.INHERIT)
	public void setOnedriveRefreshToken(String refreshToken) {
		onedrive.setRefreshToken(refreshToken);
	}

	@Option(names = { "--onedrive-scopes" }, description = "Space separated OAuth scopes. Defaults per "
		+ "authentication mode. Env: CORTEX_ONEDRIVE_SCOPES", scope = ScopeType.INHERIT)
	public void setOnedriveScopes(String scopes) {
		onedrive.setScopes(scopes);
	}

	@Option(names = { "--onedrive-api-base-url" }, description = "Microsoft Graph base URL. Overridable so the client "
		+ "can be pointed at a stub server in tests. Env: CORTEX_ONEDRIVE_API_BASE_URL", scope = ScopeType.INHERIT)
	public void setOnedriveApiBaseUrl(String url) {
		onedrive.setApiBaseUrl(url);
	}

	@Option(names = { "--onedrive-authority-url" }, description = "Microsoft identity platform base URL. "
		+ "Env: CORTEX_ONEDRIVE_AUTHORITY_URL", scope = ScopeType.INHERIT)
	public void setOnedriveAuthorityUrl(String url) {
		onedrive.setAuthorityUrl(url);
	}

	@Option(names = { "--onedrive-default-drive-id" }, description = "Drive used when a node names none. Effectively "
		+ "required for app-only access, which has no /me to fall back on. Env: CORTEX_ONEDRIVE_DEFAULT_DRIVE_ID", scope = ScopeType.INHERIT)
	public void setOnedriveDefaultDriveId(String driveId) {
		onedrive.setDefaultDriveId(driveId);
	}

	@Option(names = { "--onedrive-cache-path" }, description = "Directory for materialized OneDrive files. "
		+ "Defaults to <meta-path>/onedrive_bin. Env: CORTEX_ONEDRIVE_CACHE_PATH", scope = ScopeType.INHERIT)
	public void setOnedriveCachePath(String cachePath) {
		onedrive.setCachePath(cachePath);
	}

	@Option(names = { "--onedrive-index-path" }, description = "Directory for persisted scan indexes. "
		+ "Defaults to <meta-path>/onedrive-index. Env: CORTEX_ONEDRIVE_INDEX_PATH", scope = ScopeType.INHERIT)
	public void setOnedriveIndexPath(String indexPath) {
		onedrive.setIndexPath(indexPath);
	}

	@Option(names = { "--onedrive-max-cache-bytes" }, description = "Cache budget in bytes; 0 disables eviction. "
		+ "Env: CORTEX_ONEDRIVE_MAX_CACHE_BYTES", scope = ScopeType.INHERIT)
	public void setOnedriveMaxCacheBytes(long maxCacheBytes) {
		onedrive.setMaxCacheBytes(maxCacheBytes);
	}

	@Option(names = { "--onedrive-max-object-size" }, description = "Largest file to materialize in bytes; 0 is "
		+ "unbounded. Env: CORTEX_ONEDRIVE_MAX_OBJECT_SIZE", scope = ScopeType.INHERIT)
	public void setOnedriveMaxObjectSize(long maxObjectSize) {
		onedrive.setMaxObjectSize(maxObjectSize);
	}

	@Option(names = { "--onedrive-reconcile-interval-ms" }, description = "How long the change feed may be trusted "
		+ "before a full folder walk is forced. Env: CORTEX_ONEDRIVE_RECONCILE_INTERVAL_MS", scope = ScopeType.INHERIT)
	public void setOnedriveReconcileIntervalMs(long intervalMs) {
		onedrive.setReconcileIntervalMs(intervalMs);
	}

	@Option(names = { "--onedrive-request-timeout-ms" }, description = "Per-request timeout. "
		+ "Env: CORTEX_ONEDRIVE_REQUEST_TIMEOUT_MS", scope = ScopeType.INHERIT)
	public void setOnedriveRequestTimeoutMs(long timeoutMs) {
		onedrive.setRequestTimeoutMs(timeoutMs);
	}

	@Option(names = { "--onedrive-max-retries" }, description = "How often a throttled request is retried. "
		+ "Env: CORTEX_ONEDRIVE_MAX_RETRIES", scope = ScopeType.INHERIT)
	public void setOnedriveMaxRetries(int maxRetries) {
		onedrive.setMaxRetries(maxRetries);
	}

	/**
	 * Build {@link CortexOptions} from the parsed CLI values.
	 */
	public CortexOptions toCortexOptions() {
		CortexOptions options = new CortexOptions();
		LoomClientOptions loom = new LoomClientOptions();
		loom.setHostname(hostname);
		loom.setPort(port);
		options.setLoom(loom);
		options.setMonitoringPort(monitoringPort);
		options.setMetaPath(metaPath);
		// Left null when unset - CortexOptions treats null as "no restriction", and
		// passing an empty set instead would read as "run nothing".
		options.setNodeId(nodeId);
		options.setDrainTimeoutMs(drainTimeoutMs);
		options.setNodeWhitelist(nodeWhitelist);
		options.setNodeBlacklist(nodeBlacklist);
		options.setS3(s3);
		options.setGdrive(gdrive);
		options.setOnedrive(onedrive);
		return options;
	}

	@Override
	public void run() {
		spec.commandLine().usage(System.out);
	}

}
