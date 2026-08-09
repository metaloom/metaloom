package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.searchindex.IndexJobCreateRequest;
import io.metaloom.loom.rest.model.searchindex.IndexJobResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexListResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexResponse;

/**
 * {@code /api/v1/search-indices} — the operator surface over the lexical, vector and fingerprint indices.
 *
 * <p>
 * The default test configuration binds no vector index and disables similarity, which is the interesting case rather than a limitation: it is what a
 * fresh install looks like, and it is exactly when an operator opens this screen to find out why search is not doing what they expected. So the
 * assertions here are mostly about <b>degrading honestly</b> — the list is served with per-index reasons instead of a 503, and a job against a
 * disabled index is refused with the reason rather than reporting a successful rebuild of nothing.
 * </p>
 */
public class SearchIndexEndpointTest extends AbstractEndpointTest {

	/** The one index guaranteed to be present and usable in the test configuration. */
	private static final String LEXICAL = "lexical";

	private SearchIndexListResponse list(LoomHttpClient client) throws LoomClientException {
		return client.listSearchIndices().sync().body();
	}

	private SearchIndexResponse index(SearchIndexListResponse response, String id) {
		return response.getData().stream().filter(index -> id.equals(index.getId())).findFirst().orElseThrow();
	}

	@Test
	public void testListReportsEveryIndexAndItsBackends() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			SearchIndexListResponse response = list(client);

			// The lexical index and the fingerprint index always exist; vector spaces come and go with
			// the data, so their absence in a fixture instance is correct rather than a gap.
			assertThat(response.getData()).extracting(SearchIndexResponse::getId).contains(LEXICAL, "fingerprint");
			assertThat(response.getBackends()).extracting(backend -> backend.getId())
				.containsExactlyInAnyOrder("lexical", "vector", "fingerprint");

			SearchIndexResponse lexical = index(response, LEXICAL);
			assertThat(lexical.getKind()).isEqualTo("LEXICAL");
			assertThat(lexical.isAvailable()).as("the lexical index is the search_document table itself").isTrue();
			// Triggers maintain it inside the writing transaction, so it cannot be behind. A non-zero
			// backlog here would mean the freshness model had silently changed.
			assertThat(lexical.getPendingCount()).isZero();
		}
	}

	@Test
	public void testTheLexicalIndexAcceptsOnlyAReindex() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			SearchIndexResponse lexical = index(list(client), LEXICAL);
			assertThat(lexical.getSupportedActions()).containsExactly("REINDEX");
		}
	}

	/**
	 * Rejecting the unsupported action server-side, not only in the UI.
	 *
	 * <p>
	 * Hiding a button is a courtesy; this is the actual control. Dropping the lexical index would leave search answering nothing until something wrote
	 * to every asset again, and one curl should not be able to do that.
	 * </p>
	 */
	@Test
	public void testDroppingTheLexicalIndexIsRefused() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.createSearchIndexJob(LEXICAL, new IndexJobCreateRequest().setAction("DROP")));
		}
	}

	@Test
	public void testAnUnknownActionIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.createSearchIndexJob(LEXICAL, new IndexJobCreateRequest().setAction("VACUUM")));
		}
	}

	@Test
	public void testAnUnknownIndexIs404() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.loadSearchIndex("vector-face-nonexistent-model-512"));
		}
	}

	/**
	 * A reindex is accepted with 202 and a job to poll, rather than run inside the request.
	 *
	 * <p>
	 * The lexical rebuild is one SQL call with no intermediate progress, so its {@code total} stays null — a client has to draw an indeterminate bar
	 * for it. That is asserted here because it is the property most likely to be "tidied up" into a fabricated number later.
	 * </p>
	 */
	@Test
	public void testAReindexIsAcceptedAsAJob() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			IndexJobResponse job = client.createSearchIndexJob(LEXICAL, new IndexJobCreateRequest().setAction("REINDEX")).sync().body();

			assertThat(job.getUuid()).isNotNull();
			assertThat(job.getIndexId()).isEqualTo(LEXICAL);
			assertThat(job.getAction()).isEqualTo("REINDEX");
			assertThat(job.getTotal()).as("the lexical rebuild cannot report a total").isNull();

			// Readable straight back: the client polls this uuid, so it must resolve the instant the
			// 202 lands rather than once the worker picks the job up.
			assertThat(client.loadSearchIndexJob(LEXICAL, job.getUuid()).sync().body().getUuid()).isEqualTo(job.getUuid());
			assertThat(client.listSearchIndexJobs(LEXICAL).sync().body().getData())
				.extracting(IndexJobResponse::getUuid).contains(job.getUuid());
		}
	}

	@Test
	public void testAJobOfAnotherIndexIsNotVisibleUnderThisOne() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			IndexJobResponse job = client.createSearchIndexJob(LEXICAL, new IndexJobCreateRequest().setAction("REINDEX")).sync().body();
			// The pair has to agree, or a client is polling something it did not start.
			expect(404, "Not Found", client.loadSearchIndexJob("fingerprint", job.getUuid()));
		}
	}

	@Test
	public void testAnUnknownJobIs404() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.loadSearchIndexJob(LEXICAL, UUID.randomUUID()));
		}
	}

	/**
	 * A disabled index refuses work with 503 and names the reason.
	 *
	 * <p>
	 * The alternative — accepting the job and reporting a successful rebuild of nothing — is the failure this whole area is written to avoid: "the
	 * index is off" and "the index is empty" are opposite answers and must never look the same.
	 * </p>
	 */
	@Test
	public void testAJobAgainstADisabledIndexIsRefusedWithItsReason() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			SearchIndexResponse fingerprint = index(list(client), "fingerprint");
			// LOOM_SIMILARITY_ENABLED defaults to false, so the fixture instance has no fingerprint index.
			assertThat(fingerprint.isAvailable()).isFalse();
			assertThat(fingerprint.getReason()).isNotBlank();
			expect(503, "Service Unavailable",
				client.createSearchIndexJob("fingerprint", new IndexJobCreateRequest().setAction("REINDEX")));
		}
	}

	// ── Permissions ───────────────────────────────────────────────────────

	@Test
	public void testAPermissionlessUserIsDenied() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listSearchIndices());
		}
	}

	@Test
	public void testReadSearchIndexAloneGrantsTheListing() throws Exception {
		try (LoomHttpClient client = loginClientWith("index-reader", Permission.READ_SEARCH_INDEX)) {
			assertThat(list(client).getData()).isNotEmpty();
		}
	}

	/**
	 * Reading does not imply acting. This is the split the change exists to introduce: the routes used to be gated on UPDATE_ASSET, so anyone who
	 * could retag a photo could also empty the face index.
	 */
	@Test
	public void testReadSearchIndexDoesNotGrantStartingAJob() throws Exception {
		try (LoomHttpClient client = loginClientWith("index-reader-only", Permission.READ_SEARCH_INDEX)) {
			expect(403, "Forbidden", client.createSearchIndexJob(LEXICAL, new IndexJobCreateRequest().setAction("REINDEX")));
		}
	}

	/** And the inverse: holding the asset permission that used to gate a rebuild no longer does. */
	@Test
	public void testUpdateAssetNoLongerGrantsARebuild() throws Exception {
		try (LoomHttpClient client = loginClientWith("asset-editor", Permission.UPDATE_ASSET)) {
			expect(403, "Forbidden", client.createSearchIndexJob(LEXICAL, new IndexJobCreateRequest().setAction("REINDEX")));
		}
	}

	@Test
	public void testAnAnonymousCallerIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			expect(401, "Unauthorized", client.listSearchIndices());
		}
	}
}
