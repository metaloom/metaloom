package io.metaloom.loom.test.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.MetaLoomCLIMain;
import io.metaloom.loom.api.Loom;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Drives the real CLI against a real Loom server, in process.
 *
 * <p>Everything below the CLI is genuine: HTTP over OkHttp, the REST endpoints, the DAOs and
 * a leased Postgres. Only the process boundary is skipped -
 * {@link MetaLoomCLIMain#execute(PrintWriter, PrintWriter, String...)} returns the exit code
 * instead of calling {@code System.exit}, and the writers capture what a terminal would see.</p>
 *
 * <p>Fixtures are built through the REST API rather than the DAOs, because the DAO handles
 * are not reachable from this module. That bounds what can be set up here: a run cannot be
 * forced into an arbitrary status, so the pause/resume state machine is covered by
 * {@code PipelineRunPauseEndpointTest} in {@code loom/core} instead. What this test owns is
 * the CLI's own behaviour - argument handling, output formats, exit codes and the resolution
 * of names to UUIDs - against a server that really answers.</p>
 */
public class CliIntegrationTest extends AbstractIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path tempDir;

	private Loom server;
	private String token;
	private String serverUrl;
	private LoomHttpClient client;

	@BeforeEach
	public void setup() throws Exception {
		server = loomServer();
		server.run(false);
		serverUrl = "http://localhost:" + server.actualRestPort();

		client = httpClient(server);
		AuthLoginResponse login = client.login("admin", "finger").sync().body();
		token = login.getToken();
		client.setToken(token);
	}

	@AfterEach
	public void teardown() {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.shutdown();
		}
	}

	/** Result of one CLI invocation. */
	private record Cli(int exitCode, String stdout, String stderr) {

		JsonNode json() throws Exception {
			return MAPPER.readTree(stdout);
		}
	}

	/** Run the CLI with the server, token and an isolated config already supplied. */
	private Cli cli(String... args) {
		return cliWithToken(token, args);
	}

	private Cli cliWithToken(String bearer, String... args) {
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		List<String> full = new ArrayList<>(List.of(
			"--server", serverUrl,
			"--token", bearer,
			// Never read or write the developer's own ~/.config/metaloom.
			"--config", tempDir.resolve("cli.yml").toString(),
			"--no-color"));
		full.addAll(List.of(args));

		int code;
		try (PrintWriter outWriter = new PrintWriter(out); PrintWriter errWriter = new PrintWriter(err)) {
			code = MetaLoomCLIMain.execute(outWriter, errWriter, full.toArray(new String[0]));
		}
		return new Cli(code, out.toString(), err.toString());
	}

	// ── Basics ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("version reports both the CLI and the server it is talking to")
	void testVersion() throws Exception {
		Cli result = cli("-o", "json", "version");

		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
		JsonNode json = result.json();
		assertThat(json.get("client").asText()).isNotBlank();
		assertThat(json.has("server")).as("the server version is reported").isTrue();
		assertThat(json.get("server").asText()).isNotEqualTo("unreachable");
	}

	@Test
	@DisplayName("whoami resolves the token to the admin user")
	void testWhoami() throws Exception {
		Cli result = cli("-o", "json", "whoami");

		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
		assertThat(result.json().get("username").asText()).isEqualTo("admin");
	}

	@Test
	@DisplayName("health passes against a running server")
	void testHealth() {
		Cli result = cli("health");

		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
	}

	@Test
	@DisplayName("a bad token exits with the auth code, not a generic error")
	void testUnauthenticated() {
		Cli result = cliWithToken("not-a-valid-token", "user", "list");

		assertEquals(ExitCode.AUTH_REQUIRED, result.exitCode(), result.stderr());
	}

	// ── Listing ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("user list renders a table, JSON and quiet identifiers from the same data")
	void testUserListFormats() throws Exception {
		Cli table = cli("user", "list");
		assertEquals(ExitCode.OK, table.exitCode(), table.stderr());
		assertThat(table.stdout()).contains("USERNAME").contains("admin");

		Cli json = cli("-o", "json", "user", "list");
		assertEquals(ExitCode.OK, json.exitCode(), json.stderr());
		assertThat(json.json().isArray()).isTrue();
		assertThat(json.stdout()).contains("admin");

		Cli quiet = cli("-q", "user", "list");
		assertEquals(ExitCode.OK, quiet.exitCode(), quiet.stderr());
		// Quiet mode is the scripting contract: bare identifiers, no header, one per line.
		assertThat(quiet.stdout()).doesNotContain("USERNAME");
		for (String line : quiet.stdout().strip().split("\\R")) {
			assertThat(UUID.fromString(line.strip())).isNotNull();
		}
	}

	@Test
	@DisplayName("the other listing commands all reach their endpoints")
	void testOtherListings() {
		assertEquals(ExitCode.OK, cli("group", "list").exitCode());
		assertEquals(ExitCode.OK, cli("role", "list").exitCode());
		assertEquals(ExitCode.OK, cli("space", "list").exitCode());
		assertEquals(ExitCode.OK, cli("library", "list").exitCode());
		assertEquals(ExitCode.OK, cli("pool", "list").exitCode());
	}

	// ── Pipelines ────────────────────────────────────────────────────────

	@Test
	@DisplayName("pipeline list finds a pipeline created over REST")
	void testPipelineList() throws Exception {
		createPipeline("cli-list-test");

		Cli result = cli("-o", "json", "pipeline", "list");

		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
		assertThat(result.stdout()).contains("cli-list-test");
	}

	@Test
	@DisplayName("a pipeline can be addressed by name as well as by UUID")
	void testPipelineResolveByName() throws Exception {
		PipelineResponse pipeline = createPipeline("cli-by-name");

		Cli byName = cli("-o", "json", "pipeline", "get", "cli-by-name");
		assertEquals(ExitCode.OK, byName.exitCode(), byName.stderr());
		assertThat(byName.json().get("uuid").asText()).isEqualTo(pipeline.getUuid().toString());

		Cli byUuid = cli("-o", "json", "pipeline", "get", pipeline.getUuid().toString());
		assertEquals(ExitCode.OK, byUuid.exitCode(), byUuid.stderr());
		assertThat(byUuid.json().get("name").asText()).isEqualTo("cli-by-name");
	}

	@Test
	@DisplayName("an unknown pipeline name exits with the not-found code")
	void testUnknownPipeline() {
		Cli result = cli("pipeline", "get", "no-such-pipeline");

		assertEquals(ExitCode.NOT_FOUND, result.exitCode());
		assertThat(result.stderr()).contains("no-such-pipeline");
	}

	@Test
	@DisplayName("pipeline get --definition prints the raw graph, ready to redirect to a file")
	void testPipelineDefinitionOutput() throws Exception {
		createPipeline("cli-definition");

		Cli result = cli("pipeline", "get", "cli-definition", "--definition");

		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
		// Must be parseable JSON on its own - that is the point of the flag.
		JsonNode definition = MAPPER.readTree(result.stdout());
		assertThat(definition.get("nodes").isArray()).isTrue();
	}

	@Test
	@DisplayName("deleting without --yes and without a terminal is refused rather than assumed")
	void testDeleteRequiresConfirmation() throws Exception {
		PipelineResponse pipeline = createPipeline("cli-delete-guard");

		// No console in a test JVM, so this exercises the non-interactive guard.
		Cli refused = cli("pipeline", "delete", "cli-delete-guard");
		assertEquals(ExitCode.USAGE, refused.exitCode());
		assertThat(refused.stderr()).contains("--yes");

		// Still there.
		assertEquals(ExitCode.OK, cli("pipeline", "get", pipeline.getUuid().toString()).exitCode());

		Cli deleted = cli("pipeline", "delete", "cli-delete-guard", "--yes");
		assertEquals(ExitCode.OK, deleted.exitCode(), deleted.stderr());
		assertEquals(ExitCode.NOT_FOUND, cli("pipeline", "get", pipeline.getUuid().toString()).exitCode());
	}

	// ── Run control ──────────────────────────────────────────────────────

	@Test
	@DisplayName("running a pipeline with no processor registered reports the server's refusal")
	void testRunWithoutProcessor() throws Exception {
		PipelineResponse pipeline = createPipeline("cli-run-no-processor");

		// No Cortex is registered, so the dispatch cannot be served. The value of this case is
		// that it exercises the whole real path - name resolution, --dir mapping onto
		// PipelineRunRequest.path, POST /run - and asserts the 503 surfaces as a clean exit
		// code rather than a stack trace.
		Cli result = cli("pipeline", "run", "cli-run-no-processor", "--dir", tempDir.toString());

		assertEquals(ExitCode.SERVER_FAILURE, result.exitCode(), result.stderr());
		assertThat(result.stderr()).isNotBlank();
		assertThat(pipeline.getUuid()).isNotNull();
	}

	@Test
	@DisplayName("run list is empty for a fresh pipeline and accepts a status filter")
	void testRunList() throws Exception {
		PipelineResponse pipeline = createPipeline("cli-run-list");

		Cli result = cli("-o", "json", "run", "list", "-p", pipeline.getUuid().toString());
		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
		assertThat(result.json().isArray()).isTrue();
		assertThat(result.json()).isEmpty();

		Cli filtered = cli("-o", "json", "run", "list", "-p", "cli-run-list", "--status", "FAILED");
		assertEquals(ExitCode.OK, filtered.exitCode(), filtered.stderr());
	}

	@Test
	@DisplayName("acting on an unknown run exits not-found rather than crashing")
	void testUnknownRun() {
		Cli result = cli("run", "pause", UUID.randomUUID().toString());

		assertEquals(ExitCode.NOT_FOUND, result.exitCode(), result.stderr());
	}

	@Test
	@DisplayName("run stats returns the daily buckets")
	void testRunStats() {
		Cli result = cli("-o", "json", "run", "stats");

		assertEquals(ExitCode.OK, result.exitCode(), result.stderr());
	}

	// ── Config ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("config set persists to the file and config get reads it back")
	void testConfigRoundTrip() throws Exception {
		Path configFile = tempDir.resolve("roundtrip.yml");

		StringWriter out = new StringWriter();
		try (PrintWriter writer = new PrintWriter(out)) {
			int code = MetaLoomCLIMain.execute(writer, writer,
				"--config", configFile.toString(), "config", "set", "server", "http://example.com:1234");
			assertEquals(ExitCode.OK, code, out.toString());
		}
		assertThat(Files.readString(configFile)).contains("http://example.com:1234");

		StringWriter readBack = new StringWriter();
		try (PrintWriter writer = new PrintWriter(readBack)) {
			int code = MetaLoomCLIMain.execute(writer, writer,
				"--config", configFile.toString(), "config", "get", "server");
			assertEquals(ExitCode.OK, code);
		}
		assertThat(readBack.toString().strip()).isEqualTo("http://example.com:1234");
	}

	@Test
	@DisplayName("a rejected config value fails before anything is written")
	void testConfigValidation() {
		Path configFile = tempDir.resolve("invalid.yml");

		StringWriter out = new StringWriter();
		int code;
		try (PrintWriter writer = new PrintWriter(out)) {
			code = MetaLoomCLIMain.execute(writer, writer,
				"--config", configFile.toString(), "config", "set", "timeout", "not-a-duration");
		}
		assertEquals(ExitCode.USAGE, code);
		assertThat(Files.exists(configFile)).as("nothing is written when validation fails").isFalse();
	}

	@Test
	@DisplayName("the config file supplies the server when no flag is given")
	void testServerFromConfigFile() throws Exception {
		Path configFile = tempDir.resolve("profile.yml");
		Files.writeString(configFile, """
			currentProfile: it
			profiles:
			  it:
			    server: %s
			""".formatted(serverUrl));

		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		int code;
		try (PrintWriter outWriter = new PrintWriter(out); PrintWriter errWriter = new PrintWriter(err)) {
			// No --server: it has to come from the profile in the file.
			code = MetaLoomCLIMain.execute(outWriter, errWriter,
				"--config", configFile.toString(), "--token", token, "-o", "json", "whoami");
		}
		assertEquals(ExitCode.OK, code, err.toString());
		assertThat(MAPPER.readTree(out.toString()).get("username").asText()).isEqualTo("admin");
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/** A minimal but valid pipeline: exactly one source node, as the parser requires. */
	private PipelineResponse createPipeline(String name) throws Exception {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash")));

		PipelineCreateRequest request = new PipelineCreateRequest();
		request.setName(name);
		request.setDefinition(definition);
		request.setEnabled(true);
		return client.createPipeline(request).sync().body();
	}
}
