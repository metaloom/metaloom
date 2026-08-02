package io.metaloom.cortex.cloud.gdrive;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudUri;
import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.metaloom.cortex.cloud.http.CloudApiException;
import io.metaloom.cortex.cloud.http.CloudHttp;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link CloudFileStore} over the Google Drive v3 REST API.
 *
 * <p>Hand-rolled rather than built on {@code google-api-services-drive}: the surface used here is
 * five endpoints, and the SDK would add Guava, the Google HTTP client stack and a
 * {@code ServiceLoader}-based transport chain to the shaded cortex jar for no benefit.</p>
 *
 * <h2>Shared drives</h2>
 * <p>Every call carries {@code supportsAllDrives}. When a concrete drive is selected the listing
 * additionally scopes itself with {@code corpora=drive&driveId=...}; without that a query returns
 * only the caller's own My Drive, silently and with a 200.</p>
 */
public class GoogleDriveFileStore implements CloudFileStore {

	private static final Logger log = LoggerFactory.getLogger(GoogleDriveFileStore.class);

	/** Drive's own alias for the root of whichever drive the query is scoped to. */
	static final String ROOT_ALIAS = "root";

	private static final int PAGE_SIZE = 1000;

	private final CloudHttp http;
	private final CloudTokenSource tokenSource;
	private final String apiBaseUrl;
	private final boolean exportNativeDocs;

	public GoogleDriveFileStore(GDriveClientOptions options, CloudTokenSource tokenSource) {
		this(options, tokenSource, new CloudHttp(tokenSource, options.getRequestTimeoutMs(), options.getMaxRetries()));
	}

	GoogleDriveFileStore(GDriveClientOptions options, CloudTokenSource tokenSource, CloudHttp http) {
		this.tokenSource = tokenSource;
		this.http = http;
		this.apiBaseUrl = options.getApiBaseUrl();
		this.exportNativeDocs = options.isExportNativeDocs();
	}

	@Override
	public CloudProviderId provider() {
		return CloudProviderId.GDRIVE;
	}

	@Override
	public String accountId() {
		return tokenSource.accountId();
	}

	@Override
	public String resolveDriveId(String configuredDriveId) {
		// Google needs no drive id: without one the credential's own My Drive is addressed. The
		// placeholder keeps every reference three-segment so parsing stays uniform.
		return configuredDriveId == null || configuredDriveId.isBlank() ? CloudUri.MY_DRIVE : configuredDriveId.trim();
	}

	@Override
	public CloudPage list(String driveId, String folderId, String pageToken, boolean includeTrashed) throws IOException {
		String parent = folderId == null || folderId.isBlank()
			? sharedDrive(driveId) ? driveId : ROOT_ALIAS
			: folderId;

		StringBuilder query = new StringBuilder("'").append(escapeQueryLiteral(parent)).append("' in parents");
		if (!includeTrashed) {
			query.append(" and trashed = false");
		}

		StringBuilder url = new StringBuilder(apiBaseUrl).append("/drive/v3/files")
			.append("?q=").append(encode(query.toString()))
			.append("&fields=").append(encode(GoogleDriveJson.LIST_FIELDS))
			.append("&pageSize=").append(PAGE_SIZE)
			.append("&supportsAllDrives=true&includeItemsFromAllDrives=true");
		if (sharedDrive(driveId)) {
			url.append("&corpora=drive&driveId=").append(encode(driveId));
		}
		if (pageToken != null && !pageToken.isBlank()) {
			url.append("&pageToken=").append(encode(pageToken));
		}

		JsonObject response = http.getJson(url.toString());
		List<CloudFileRef> entries = new ArrayList<>();
		JsonArray files = response.getJsonArray("files");
		if (files != null) {
			for (int i = 0; i < files.size(); i++) {
				CloudFileRef ref = GoogleDriveJson.toRef(files.getJsonObject(i), driveId, exportNativeDocs);
				if (ref != null) {
					entries.add(ref);
				}
			}
		}
		return new CloudPage(entries, response.getString("nextPageToken"));
	}

	@Override
	public CloudFileRef get(String driveId, String fileId) throws IOException {
		String url = apiBaseUrl + "/drive/v3/files/" + encodePath(fileId)
			+ "?fields=" + encode(GoogleDriveJson.FILE_FIELDS)
			+ "&supportsAllDrives=true";
		try {
			return GoogleDriveJson.toRef(http.getJson(url), driveId, exportNativeDocs);
		} catch (CloudApiException e) {
			if (e.status() == 404) {
				return null;
			}
			throw e;
		}
	}

	@Override
	public String startDeltaToken(String driveId) throws IOException {
		StringBuilder url = new StringBuilder(apiBaseUrl)
			.append("/drive/v3/changes/startPageToken?supportsAllDrives=true");
		if (sharedDrive(driveId)) {
			url.append("&driveId=").append(encode(driveId));
		}
		return http.getJson(url.toString()).getString("startPageToken");
	}

	@Override
	public CloudDelta delta(String driveId, String token, boolean includeTrashed) throws IOException {
		if (token == null || token.isBlank()) {
			return CloudDelta.expired();
		}

		List<CloudChange> changes = new ArrayList<>();
		String pageToken = token;
		String newStartPageToken = null;

		while (pageToken != null && !pageToken.isBlank()) {
			StringBuilder url = new StringBuilder(apiBaseUrl).append("/drive/v3/changes")
				.append("?pageToken=").append(encode(pageToken))
				.append("&fields=").append(encode(GoogleDriveJson.CHANGES_FIELDS))
				.append("&pageSize=").append(PAGE_SIZE)
				.append("&supportsAllDrives=true&includeItemsFromAllDrives=true");
			if (sharedDrive(driveId)) {
				url.append("&corpora=drive&driveId=").append(encode(driveId));
			}

			JsonObject response;
			try {
				response = http.getJson(url.toString());
			} catch (CloudApiException e) {
				// Drive answers a page token that has aged out with a 404. That is not an error to
				// surface: it means "start over", and the scanner falls back to a full walk.
				if (e.status() == 404 || e.isDeltaTokenExpired()) {
					log.info("The Google Drive change cursor for drive '{}' has expired; a full walk is needed", driveId);
					return CloudDelta.expired();
				}
				throw e;
			}

			JsonArray entries = response.getJsonArray("changes");
			if (entries != null) {
				for (int i = 0; i < entries.size(); i++) {
					CloudChange change = toChange(entries.getJsonObject(i), driveId, includeTrashed);
					if (change != null) {
						changes.add(change);
					}
				}
			}
			newStartPageToken = response.getString("newStartPageToken");
			pageToken = response.getString("nextPageToken");
		}

		return new CloudDelta(changes, newStartPageToken, false);
	}

	private CloudChange toChange(JsonObject entry, String driveId, boolean includeTrashed) {
		if (entry == null) {
			return null;
		}
		String fileId = entry.getString("fileId");
		if (fileId == null) {
			return null;
		}
		if (Boolean.TRUE.equals(entry.getBoolean("removed"))) {
			return CloudChange.removed(fileId);
		}
		CloudFileRef ref = GoogleDriveJson.toRef(entry.getJsonObject("file"), driveId, exportNativeDocs);
		if (ref == null) {
			// Removed files sometimes arrive without the removed flag but also without a resource
			// (the caller lost access). Treating that as a removal is the safe reading.
			return CloudChange.removed(fileId);
		}
		if (ref.trashed() && !includeTrashed) {
			return CloudChange.removed(fileId);
		}
		return CloudChange.changed(ref);
	}

	@Override
	public void download(CloudFileRef ref, Path target) throws IOException {
		String url;
		if (ref.requiresExport()) {
			// Native documents have no bytes of their own; export is the only way to read one, and
			// it is capped at 10 MB of output.
			url = apiBaseUrl + "/drive/v3/files/" + encodePath(ref.fileId()) + "/export"
				+ "?mimeType=" + encode(ref.exportMimeType());
		} else {
			url = apiBaseUrl + "/drive/v3/files/" + encodePath(ref.fileId())
				+ "?alt=media&supportsAllDrives=true";
		}
		try {
			http.download(url, target, true);
		} catch (CloudApiException e) {
			if (e.isNotDownloadable()) {
				throw new IOException("Google Drive file " + ref.reference() + " has no downloadable content. "
					+ "It is a native Google document; enable the node's exportNativeDocs option to export it.", e);
			}
			if (CloudApiException.EXPORT_SIZE_LIMIT_EXCEEDED.equalsIgnoreCase(e.errorCode())) {
				throw new IOException("Google Drive refused to export " + ref.reference()
					+ ": the exported document exceeds Google's 10 MB export limit.", e);
			}
			throw e;
		}
	}

	/**
	 * Whether the drive id names a real shared drive rather than the credential's My Drive.
	 */
	private static boolean sharedDrive(String driveId) {
		return driveId != null && !driveId.isBlank() && !CloudUri.MY_DRIVE.equals(driveId);
	}

	/**
	 * Drive's query language quotes literals with single quotes and escapes with a backslash, so a
	 * folder id (or a name, should one ever be queried) has to be escaped rather than interpolated.
	 */
	static String escapeQueryLiteral(String value) {
		return value.replace("\\", "\\\\").replace("'", "\\'");
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Path segments must not turn a space into a {@code +}, which form encoding would do. */
	private static String encodePath(String value) {
		return encode(value).replace("+", "%20");
	}
}
