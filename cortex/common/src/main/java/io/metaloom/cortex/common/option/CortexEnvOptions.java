package io.metaloom.cortex.common.option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.api.option.S3EventOptions;

/**
 * Applies environment variables onto a {@link CortexOptions} instance.
 *
 * <p>
 * Cortex ships as a container and has no command line: the environment is the only way to configure a worker at runtime, so this class replaces the
 * former picocli flag layer. Every variable listed here used to have a matching {@code --flag}; the names are unchanged so an existing deployment keeps
 * working.
 * </p>
 *
 * <p>
 * Only variables that are actually set are applied, which keeps the layering intact: {@code cortex.yml} (or the generated defaults) supplies the base
 * value and the environment overrides it.
 * </p>
 */
public final class CortexEnvOptions {

	private static final Logger log = LoggerFactory.getLogger(CortexEnvOptions.class);

	/** Swapped by tests, following the same convention as {@code OptionUtils#envLookup} on the Loom side. */
	static Function<String, String> envLookup = System::getenv;

	private CortexEnvOptions() {
	}

	/**
	 * Apply the {@code LOOM_*} / {@code CORTEX_*} environment variables onto the given options.
	 *
	 * @param options
	 *            options to modify in place
	 * @return the same instance, for chaining
	 */
	public static CortexOptions applyEnv(CortexOptions options) {
		applyLoom(options);
		applyWorker(options);
		applyS3(options.getS3());
		applyGDrive(options.getGdrive());
		applyOneDrive(options.getOnedrive());
		return options;
	}

	private static void applyLoom(CortexOptions options) {
		LoomClientOptions loom = options.getLoom();
		if (loom == null) {
			// A cortex.yml that clears the loom section selects offline mode. Re-creating the
			// section here would silently put the worker back online against localhost.
			return;
		}
		str("LOOM_HOST", loom::setHostname);
		integer("LOOM_PORT", loom::setPort);
	}

	private static void applyWorker(CortexOptions options) {
		integer("CORTEX_MONITORING_PORT", options::setMonitoringPort);
		str("CORTEX_META_PATH", value -> options.setMetaPath(path(value)));
		str("CORTEX_NODE_ID", options::setNodeId);
		// Left null when unset - CortexOptions reads null as "no restriction", whereas an empty
		// set would read as "run nothing".
		str("CORTEX_NODE_WHITELIST", value -> options.setNodeWhitelist(set(value)));
		str("CORTEX_NODE_BLACKLIST", value -> options.setNodeBlacklist(set(value)));
		// Off restores pre-announcement behaviour exactly: the worker still registers and still runs
		// every node it could before, but nothing Loom does not itself ship stays authorable.
		bool("CORTEX_NODE_SPEC_ANNOUNCE", options::setNodeSpecAnnounceEnabled);
		number("CORTEX_DRAIN_TIMEOUT_MS", options::setDrainTimeoutMs);
	}

	/**
	 * S3 settings are needed by every worker that touches {@code s3://} media, not only the one running the {@code s3-source} node: media is
	 * materialized lazily by whichever worker runs the node task.
	 */
	private static void applyS3(S3ClientOptions s3) {
		if (s3 == null) {
			return;
		}
		str("CORTEX_S3_ENDPOINT", s3::setEndpoint);
		str("CORTEX_S3_REGION", s3::setRegion);
		str("CORTEX_S3_ACCESS_KEY", s3::setAccessKey);
		str("CORTEX_S3_SECRET_KEY", s3::setSecretKey);
		bool("CORTEX_S3_PATH_STYLE", s3::setPathStyleAccess);
		str("CORTEX_S3_CACHE_PATH", s3::setCachePath);
		str("CORTEX_S3_INDEX_PATH", s3::setIndexPath);
		number("CORTEX_S3_MAX_CACHE_BYTES", s3::setMaxCacheBytes);
		number("CORTEX_S3_MAX_OBJECT_SIZE", s3::setMaxObjectSize);
		number("CORTEX_S3_RECONCILE_INTERVAL_MS", s3::setReconcileIntervalMs);

		S3EventOptions events = s3.getEvents();
		if (events == null) {
			return;
		}
		bool("CORTEX_S3_EVENTS_ENABLED", events::setEnabled);
		str("CORTEX_S3_EVENTS_MODE", value -> events.setMode(mode(value)));
		str("CORTEX_S3_EVENTS_WEBHOOK_PATH", events::setWebhookPath);
		str("CORTEX_S3_EVENTS_WEBHOOK_SECRET", events::setWebhookSecret);
		str("CORTEX_S3_EVENTS_QUEUE_URL", events::setQueueUrl);
		integer("CORTEX_S3_EVENTS_MAX_BUFFERED_KEYS", events::setMaxBufferedKeys);
	}

	/** Same deployment note as {@link #applyS3(S3ClientOptions)}: every worker that touches {@code gdrive://} media needs these. */
	private static void applyGDrive(GDriveClientOptions gdrive) {
		if (gdrive == null) {
			return;
		}
		str("CORTEX_GDRIVE_SERVICE_ACCOUNT_JSON", gdrive::setServiceAccountJson);
		str("CORTEX_GDRIVE_SERVICE_ACCOUNT_FILE", gdrive::setServiceAccountFile);
		str("CORTEX_GDRIVE_IMPERSONATE_SUBJECT", gdrive::setImpersonateSubject);
		str("CORTEX_GDRIVE_CLIENT_ID", gdrive::setClientId);
		str("CORTEX_GDRIVE_CLIENT_SECRET", gdrive::setClientSecret);
		str("CORTEX_GDRIVE_REFRESH_TOKEN", gdrive::setRefreshToken);
		str("CORTEX_GDRIVE_SCOPES", gdrive::setScopes);
		str("CORTEX_GDRIVE_API_BASE_URL", gdrive::setApiBaseUrl);
		str("CORTEX_GDRIVE_TOKEN_URL", gdrive::setTokenUrl);
		str("CORTEX_GDRIVE_DEFAULT_DRIVE_ID", gdrive::setDefaultDriveId);
		str("CORTEX_GDRIVE_CACHE_PATH", gdrive::setCachePath);
		str("CORTEX_GDRIVE_INDEX_PATH", gdrive::setIndexPath);
		number("CORTEX_GDRIVE_MAX_CACHE_BYTES", gdrive::setMaxCacheBytes);
		number("CORTEX_GDRIVE_MAX_OBJECT_SIZE", gdrive::setMaxObjectSize);
		number("CORTEX_GDRIVE_RECONCILE_INTERVAL_MS", gdrive::setReconcileIntervalMs);
		number("CORTEX_GDRIVE_REQUEST_TIMEOUT_MS", gdrive::setRequestTimeoutMs);
		integer("CORTEX_GDRIVE_MAX_RETRIES", gdrive::setMaxRetries);
		bool("CORTEX_GDRIVE_EXPORT_NATIVE_DOCS", gdrive::setExportNativeDocs);
	}

	/** OneDrive / SharePoint, over Microsoft Graph. */
	private static void applyOneDrive(OneDriveClientOptions onedrive) {
		if (onedrive == null) {
			return;
		}
		str("CORTEX_ONEDRIVE_TENANT_ID", onedrive::setTenantId);
		str("CORTEX_ONEDRIVE_CLIENT_ID", onedrive::setClientId);
		str("CORTEX_ONEDRIVE_CLIENT_SECRET", onedrive::setClientSecret);
		str("CORTEX_ONEDRIVE_REFRESH_TOKEN", onedrive::setRefreshToken);
		str("CORTEX_ONEDRIVE_SCOPES", onedrive::setScopes);
		str("CORTEX_ONEDRIVE_API_BASE_URL", onedrive::setApiBaseUrl);
		str("CORTEX_ONEDRIVE_AUTHORITY_URL", onedrive::setAuthorityUrl);
		str("CORTEX_ONEDRIVE_DEFAULT_DRIVE_ID", onedrive::setDefaultDriveId);
		str("CORTEX_ONEDRIVE_CACHE_PATH", onedrive::setCachePath);
		str("CORTEX_ONEDRIVE_INDEX_PATH", onedrive::setIndexPath);
		number("CORTEX_ONEDRIVE_MAX_CACHE_BYTES", onedrive::setMaxCacheBytes);
		number("CORTEX_ONEDRIVE_MAX_OBJECT_SIZE", onedrive::setMaxObjectSize);
		number("CORTEX_ONEDRIVE_RECONCILE_INTERVAL_MS", onedrive::setReconcileIntervalMs);
		number("CORTEX_ONEDRIVE_REQUEST_TIMEOUT_MS", onedrive::setRequestTimeoutMs);
		integer("CORTEX_ONEDRIVE_MAX_RETRIES", onedrive::setMaxRetries);
	}

	// --- value handling -----------------------------------------------------------------

	private static void str(String name, Consumer<String> setter) {
		String value = value(name);
		if (value != null) {
			setter.accept(value);
		}
	}

	private static void integer(String name, Consumer<Integer> setter) {
		str(name, value -> {
			try {
				setter.accept(Integer.parseInt(value.trim()));
			} catch (NumberFormatException e) {
				throw invalid(name, value, "an integer");
			}
		});
	}

	private static void number(String name, Consumer<Long> setter) {
		str(name, value -> {
			try {
				setter.accept(Long.parseLong(value.trim()));
			} catch (NumberFormatException e) {
				throw invalid(name, value, "a number");
			}
		});
	}

	/**
	 * Only {@code true}/{@code false} are accepted. Picocli rejected anything else and a typo must not silently read as "off" - a mistyped
	 * {@code CORTEX_S3_EVENTS_ENABLED} would otherwise turn into a full bucket listing on every run.
	 */
	private static void bool(String name, Consumer<Boolean> setter) {
		str(name, value -> {
			String flag = value.trim();
			if ("true".equalsIgnoreCase(flag)) {
				setter.accept(Boolean.TRUE);
			} else if ("false".equalsIgnoreCase(flag)) {
				setter.accept(Boolean.FALSE);
			} else {
				throw invalid(name, value, "true or false");
			}
		});
	}

	private static S3EventOptions.Mode mode(String value) {
		try {
			return S3EventOptions.Mode.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw invalid("CORTEX_S3_EVENTS_MODE", value, "one of " + Arrays.toString(S3EventOptions.Mode.values()));
		}
	}

	private static Path path(String value) {
		return Paths.get(value.trim());
	}

	private static Set<String> set(String value) {
		Set<String> parsed = Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(entry -> !entry.isEmpty())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return parsed.isEmpty() ? null : parsed;
	}

	private static String value(String name) {
		String value = envLookup.apply(name);
		if (value == null || value.isBlank()) {
			return null;
		}
		log.debug("Applying environment variable {}", name);
		return value;
	}

	private static IllegalArgumentException invalid(String name, String value, String expected) {
		return new IllegalArgumentException("Environment variable " + name + " must be " + expected + " but was {" + value + "}");
	}
}
