package io.metaloom.cortex.cloud.graph;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.metaloom.cortex.cloud.http.CloudApiException;
import io.metaloom.cortex.cloud.http.CloudHttp;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link CloudFileStore} over Microsoft Graph v1.0, covering OneDrive and SharePoint document
 * libraries (both are {@code /drives/{id}} to Graph).
 *
 * <p>Hand-rolled rather than built on the {@code microsoft-graph} SDK, which would add Azure
 * Identity and a reflective model layer to the shaded cortex jar for five endpoints' worth of
 * use.</p>
 *
 * <h2>Pagination</h2>
 * <p>Graph returns an absolute {@code @odata.nextLink} rather than a token. Since
 * {@link CloudPage#nextPageToken()} is opaque, the whole link is carried in it and used verbatim -
 * which also means the {@code $select} and {@code $top} of the first request are preserved without
 * having to rebuild them.</p>
 */
public class GraphFileStore implements CloudFileStore {

	private static final Logger log = LoggerFactory.getLogger(GraphFileStore.class);

	private static final int PAGE_SIZE = 200;

	private final CloudHttp http;
	private final CloudTokenSource tokenSource;
	private final String apiBaseUrl;

	public GraphFileStore(OneDriveClientOptions options, CloudTokenSource tokenSource) {
		this(options, tokenSource, new CloudHttp(tokenSource, options.getRequestTimeoutMs(), options.getMaxRetries()));
	}

	GraphFileStore(OneDriveClientOptions options, CloudTokenSource tokenSource, CloudHttp http) {
		this.tokenSource = tokenSource;
		this.http = http;
		this.apiBaseUrl = options.getApiBaseUrl();
	}

	@Override
	public CloudProviderId provider() {
		return CloudProviderId.ONEDRIVE;
	}

	@Override
	public String accountId() {
		return tokenSource.accountId();
	}

	@Override
	public String resolveDriveId(String configuredDriveId) throws IOException {
		if (configuredDriveId != null && !configuredDriveId.isBlank()) {
			return configuredDriveId.trim();
		}
		// There is no discovery to fall back on. An app-only token has no /me, and finding a drive
		// from nothing would mean first knowing a site, user or group - a different permission and
		// a different question. Failing here, by name, beats a 400 from Graph three calls later.
		throw new IOException("OneDrive needs a drive id: set the node's 'driveId' option or "
			+ "--onedrive-default-drive-id (CORTEX_ONEDRIVE_DEFAULT_DRIVE_ID). "
			+ "A SharePoint library id can be read from GET /sites/{hostname}:/sites/{path}:/drives.");
	}

	@Override
	public CloudPage list(String driveId, String folderId, String pageToken, boolean includeTrashed) throws IOException {
		String url;
		if (pageToken != null && !pageToken.isBlank()) {
			// Already an absolute @odata.nextLink.
			url = pageToken;
		} else {
			String container = folderId == null || folderId.isBlank()
				? "/root"
				: "/items/" + encodePath(folderId);
			url = apiBaseUrl + "/drives/" + encodePath(driveId) + container + "/children"
				+ "?$top=" + PAGE_SIZE
				+ "&$select=" + encode(GraphJson.SELECT);
		}

		JsonObject response = http.getJson(url);
		List<CloudFileRef> entries = new ArrayList<>();
		JsonArray items = response.getJsonArray("value");
		if (items != null) {
			for (int i = 0; i < items.size(); i++) {
				JsonObject item = items.getJsonObject(i);
				if (GraphJson.isDeleted(item)) {
					continue;
				}
				CloudFileRef ref = GraphJson.toRef(item, driveId);
				if (ref != null) {
					entries.add(ref);
				}
			}
		}
		return new CloudPage(entries, response.getString("@odata.nextLink"));
	}

	@Override
	public CloudFileRef get(String driveId, String fileId) throws IOException {
		String url = apiBaseUrl + "/drives/" + encodePath(driveId) + "/items/" + encodePath(fileId)
			+ "?$select=" + encode(GraphJson.SELECT);
		try {
			return GraphJson.toRef(http.getJson(url), driveId);
		} catch (CloudApiException e) {
			if (e.status() == 404) {
				return null;
			}
			throw e;
		}
	}

	@Override
	public String startDeltaToken(String driveId) throws IOException {
		// "latest" is Graph's documented shortcut for "give me a cursor without the backlog".
		String url = apiBaseUrl + "/drives/" + encodePath(driveId) + "/root/delta?token=latest";
		JsonObject response = http.getJson(url);
		return tokenFromDeltaLink(response.getString("@odata.deltaLink"));
	}

	@Override
	public CloudDelta delta(String driveId, String token, boolean includeTrashed) throws IOException {
		if (token == null || token.isBlank()) {
			return CloudDelta.expired();
		}

		List<CloudChange> changes = new ArrayList<>();
		String url = apiBaseUrl + "/drives/" + encodePath(driveId) + "/root/delta"
			+ "?token=" + encode(token)
			+ "&$select=" + encode(GraphJson.SELECT);
		String deltaLink = null;

		while (url != null && !url.isBlank()) {
			JsonObject response;
			try {
				response = http.getJson(url);
			} catch (CloudApiException e) {
				// 410 Gone with resyncRequired means the cursor is older than Graph's retention.
				if (e.isDeltaTokenExpired()) {
					log.info("The OneDrive change cursor for drive '{}' has expired; a full walk is needed", driveId);
					return CloudDelta.expired();
				}
				throw e;
			}

			JsonArray items = response.getJsonArray("value");
			if (items != null) {
				for (int i = 0; i < items.size(); i++) {
					CloudChange change = toChange(items.getJsonObject(i), driveId);
					if (change != null) {
						changes.add(change);
					}
				}
			}
			deltaLink = response.getString("@odata.deltaLink");
			url = response.getString("@odata.nextLink");
		}

		return new CloudDelta(changes, tokenFromDeltaLink(deltaLink), false);
	}

	private static CloudChange toChange(JsonObject item, String driveId) {
		if (item == null || item.getString("id") == null) {
			return null;
		}
		if (GraphJson.isDeleted(item)) {
			return CloudChange.removed(item.getString("id"));
		}
		CloudFileRef ref = GraphJson.toRef(item, driveId);
		return ref == null ? null : CloudChange.changed(ref);
	}

	@Override
	public void download(CloudFileRef ref, Path target) throws IOException {
		// Ask for the pre-authenticated URL rather than following /content's redirect. Doing it
		// explicitly means the download request carries no bearer at all, which is what the storage
		// front end expects - and it does not depend on how the JDK client treats an Authorization
		// header across a redirect.
		String metadataUrl = apiBaseUrl + "/drives/" + encodePath(ref.driveId())
			+ "/items/" + encodePath(ref.fileId())
			+ "?$select=id," + encode(GraphJson.DOWNLOAD_URL);
		String downloadUrl = GraphJson.downloadUrl(http.getJson(metadataUrl));

		if (downloadUrl != null && !downloadUrl.isBlank()) {
			http.download(downloadUrl, target, false);
			return;
		}

		// Some tenants and stub servers do not surface the annotation; /content still works and the
		// client follows its redirect.
		String contentUrl = apiBaseUrl + "/drives/" + encodePath(ref.driveId())
			+ "/items/" + encodePath(ref.fileId()) + "/content";
		http.download(contentUrl, target, true);
	}

	/**
	 * Pull the opaque cursor out of an {@code @odata.deltaLink}.
	 *
	 * <p>Storing the token rather than the whole link keeps the persisted index independent of the
	 * host, so pointing a worker at a different Graph endpoint does not invalidate it.</p>
	 *
	 * @param deltaLink the link Graph returned, or null
	 * @return the {@code token} query parameter, or null
	 */
	static String tokenFromDeltaLink(String deltaLink) {
		if (deltaLink == null || deltaLink.isBlank()) {
			return null;
		}
		int marker = deltaLink.indexOf("token=");
		if (marker < 0) {
			return null;
		}
		String value = deltaLink.substring(marker + "token=".length());
		int amp = value.indexOf('&');
		if (amp >= 0) {
			value = value.substring(0, amp);
		}
		return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodePath(String value) {
		return encode(value).replace("+", "%20");
	}
}
