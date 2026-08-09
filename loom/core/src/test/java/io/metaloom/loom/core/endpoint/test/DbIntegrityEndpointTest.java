package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityCheckListResponse;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityCheckResultModel;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityReportResponse;

/**
 * {@code GET /api/v1/db-integrity} and {@code /db-integrity/checks}.
 *
 * <p>
 * The checks themselves are covered where they can be exercised cheaply, in
 * {@code DbIntegrityServiceTest} (module {@code loom/db/jooq}), which breaks the database in
 * nineteen different ways and asserts each one is noticed. What is asserted here is the route: that
 * it is served, gated, filtered, shaped as documented - and, in
 * {@link #testABrokenRowIsReportedThroughTheEndpoint()}, that a real defect travels all the way from
 * the table to the JSON body.
 * </p>
 */
public class DbIntegrityEndpointTest extends AbstractEndpointTest {

	private DbIntegrityReportResponse loadReport(LoomHttpClient client) throws LoomClientException {
		return client.loadDbIntegrityReport().sync().body();
	}


	@Test
	public void testReadReturnsEveryCheck() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			DbIntegrityReportResponse response = loadReport(client);

			assertThat(response.getTimestamp()).as("a report must say when it was taken").isNotBlank();
			assertThat(response.getResults()).as("the catalogue is not empty").isNotEmpty();
			assertThat(response.getChecksRun()).isEqualTo(response.getResults().size());

			// Clean checks are included on purpose: "29 ran, nothing found" is the useful answer.
			assertThat(response.getResults())
				.allSatisfy(result -> {
					assertThat(result.getCheck().getCode()).isNotBlank();
					assertThat(result.getCheck().getName()).isNotBlank();
					assertThat(result.getCheck().getCategory()).isNotBlank();
					assertThat(result.getCheck().getSeverity()).isNotBlank();
					assertThat(result.getCheck().getDescription()).isNotBlank();
				});

			// The admin table lists checks by name, so a name that never crossed the wire would show
			// up as a column of blanks rather than as a failure anywhere.
			assertThat(response.getResults()).extracting(result -> result.getCheck().getName())
				.as("names are what the catalogue table shows, so they must be distinct")
				.doesNotHaveDuplicates();
		}
	}

	@Test
	public void testTheCatalogueMatchesTheReport() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			DbIntegrityCheckListResponse catalogue = client.loadDbIntegrityChecks().sync().body();

			assertThat(catalogue.getChecks()).isNotEmpty();
			assertThat(catalogue.getChecks()).extracting(check -> check.getCode())
				.containsExactlyElementsOf(loadReport(client).getResults().stream()
					.map(result -> result.getCheck().getCode()).toList());
		}
	}

	/**
	 * The whole loop: create a row that names an editor who does not exist, and read the finding back
	 * off the endpoint.
	 *
	 * <p>
	 * No raw SQL is needed, and that is the point. {@code V2.1__add_acl.sql} declares a foreign key on
	 * {@code token.creator_uuid} and omits the one on {@code editor_uuid}, so the ordinary DAO write
	 * path is enough to produce a token whose editor is nobody. The check is not detecting a
	 * contrived corruption; it is detecting something the schema currently permits.
	 * </p>
	 */
	@Test
	public void testABrokenRowIsReportedThroughTheEndpoint() throws Exception {
		UUID ghost = UUID.randomUUID();
		Token token = daos().tokenDao().createToken(adminUuid(), "endpoint-broken-token", "endpoint-secret");
		token.setEditorUuid(ghost);
		daos().tokenDao().store(token);

		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			DbIntegrityReportResponse response = loadReport(client);

			assertThat(response.isClean()).as("a dangling editor is not a clean database").isFalse();
			assertThat(response.getFindingCount()).isGreaterThanOrEqualTo(1);

			DbIntegrityCheckResultModel result = resultFor(response, DbIntegrityCodes.DANGLING_TOKEN_EDITOR);
			assertThat(result.getCount()).isEqualTo(1);
			assertThat(result.getSamples()).as("the finding must name the row so it can be fixed")
				.anySatisfy(sample -> assertThat(sample).contains(ghost.toString()));
			assertThat(result.getError()).isNull();
		}
	}

	@Test
	public void testCheckFilterNarrowsTheReport() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			DbIntegrityReportResponse response = client
				.loadDbIntegrityReport(DbIntegrityCodes.LOOM_SINGLETON, null, null).sync().body();

			assertThat(response.getResults()).hasSize(1);
			assertThat(response.getResults().get(0).getCheck().getCode())
				.isEqualTo(DbIntegrityCodes.LOOM_SINGLETON);
		}
	}

	@Test
	public void testCategoryFilterNarrowsTheReport() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			DbIntegrityReportResponse response = client
				.loadDbIntegrityReport(null, "CARDINALITY", null).sync().body();

			assertThat(response.getResults()).isNotEmpty();
			assertThat(response.getResults())
				.allSatisfy(result -> assertThat(result.getCheck().getCategory()).isEqualTo("CARDINALITY"));
		}
	}

	@Test
	public void testSeverityFilterDropsTheWarnings() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			DbIntegrityReportResponse errorsOnly = client
				.loadDbIntegrityReport(null, null, "ERROR").sync().body();

			assertThat(errorsOnly.getResults()).isNotEmpty();
			assertThat(errorsOnly.getResults())
				.allSatisfy(result -> assertThat(result.getCheck().getSeverity()).isEqualTo("ERROR"));
			assertThat(errorsOnly.getResults().size())
				.as("errorsOnly must actually drop the WARN checks")
				.isLessThan(loadReport(client).getResults().size());
		}
	}

	/**
	 * A mistyped filter is a 400, never an empty report. Answering "nothing found" to a question
	 * nobody asked is the one failure mode this feature cannot afford.
	 */
	@Test
	public void testAnUnknownCheckCodeIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.loadDbIntegrityReport("NO_SUCH_CHECK", null, null));
		}
	}

	@Test
	public void testAnUnknownCategoryIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.loadDbIntegrityReport(null, "SASQUATCH", null));
		}
	}

	// ── Permissions ────────────────────────────────────────────────────────

	/**
	 * READ_DB_INTEGRITY alone is enough. The report describes the database's own consistency and is
	 * not derived from any other resource, so requiring a second grant would make an operator role
	 * also a reader of assets.
	 */
	@Test
	public void testReadDbIntegrityAloneGrantsAccess() throws Exception {
		try (LoomHttpClient client = loginClientWith("integrity-reader", Permission.READ_DB_INTEGRITY)) {
			assertThat(loadReport(client).getResults()).isNotEmpty();
			assertThat(client.loadDbIntegrityChecks().sync().body().getChecks()).isNotEmpty();
		}
	}

	/**
	 * A neighbouring operator permission is not enough. READ_METRIC says "you may see how the instance
	 * is performing"; this says "you may see which rows in the catalogue are wrong", and the second is
	 * a read of the data rather than of a counter.
	 */
	@Test
	public void testANeighbouringPermissionIsNotEnough() throws Exception {
		try (LoomHttpClient client = loginClientWith("metrics-only-reader", Permission.READ_METRIC)) {
			expect(403, "Forbidden", client.loadDbIntegrityReport());
			expect(403, "Forbidden", client.loadDbIntegrityChecks());
		}
	}

	@Test
	public void testAPermissionlessUserIsRejected() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.loadDbIntegrityReport());
			expect(403, "Forbidden", client.loadDbIntegrityChecks());
		}
	}

	@Test
	public void testAnAnonymousCallerIsRejected() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			expect(401, "Unauthorized", client.loadDbIntegrityReport());
			expect(401, "Unauthorized", client.loadDbIntegrityChecks());
		}
	}

	private DbIntegrityCheckResultModel resultFor(DbIntegrityReportResponse response, String code) {
		return response.getResults().stream()
			.filter(result -> code.equals(result.getCheck().getCode()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("The report does not contain check " + code));
	}
}
