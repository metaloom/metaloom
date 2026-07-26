package io.metaloom.loom.test.integration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.metaloom.cli.MetaLoomCLIMain;
import io.metaloom.loom.api.Loom;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Captures real CLI output against a real server, for the documentation.
 *
 * <p>Not a test — it asserts nothing. It exists so the examples in the CLI documentation are
 * transcripts of what the tool actually prints rather than something written by hand and
 * quietly drifting out of date. Run it deliberately when the output format changes:</p>
 *
 * <pre>
 * mvn test -pl integration-test -Dtest=CliDocSampleRunner \
 *     -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
 *     -DexcludedGroups= -Dgroups= -Djunit.jupiter.conditions.deactivate=*
 * </pre>
 *
 * <p>The output lands in {@code target/cli-doc-samples/}.</p>
 */
@Disabled("Documentation sample generator; run explicitly, see the class javadoc")
public class CliDocSampleRunner extends AbstractIntegrationTest {

	private Loom server;
	private String token;
	private String serverUrl;
	private LoomHttpClient client;
	private Path outputDir;
	private Path configFile;

	@Test
	public void generateSamples() throws Exception {
		outputDir = Path.of("target", "cli-doc-samples");
		Files.createDirectories(outputDir);
		configFile = outputDir.resolve("cli.yml");
		Files.deleteIfExists(configFile);

		server = loomServer();
		server.run(false);
		serverUrl = "http://localhost:" + server.actualRestPort();
		client = httpClient(server);
		AuthLoginResponse login = client.login("admin", "finger").sync().body();
		token = login.getToken();
		client.setToken(token);

		try {
			seed();

			capture("pipeline-list", "pipeline", "list");
			capture("pipeline-list-json", "-o", "json", "pipeline", "list");
			capture("pipeline-list-quiet", "-q", "pipeline", "list");
			capture("pipeline-get", "pipeline", "get", "Ingest & Proxy");
			capture("user-list", "user", "list");
			capture("group-list", "group", "list");
			capture("role-list", "role", "list");
			capture("space-list", "space", "list");
			capture("whoami", "whoami");
			capture("whoami-json", "-o", "json", "whoami");
			capture("health", "health");
			capture("version", "version");
			capture("version-json", "-o", "json", "version");
			capture("run-stats", "run", "stats");
			capture("run-list-empty", "run", "list", "-p", "Quick Hash");
			capture("config-list", "config", "list");
			capture("error-not-found", "pipeline", "get", "no-such-pipeline");
			capture("error-unreachable", "-s", "http://127.0.0.1:1", "pipeline", "list");

			System.out.println("Samples written to " + outputDir.toAbsolutePath());
		} finally {
			client.close();
			server.shutdown();
		}
	}

	/** A couple of pipelines so the listings are not empty. */
	private void seed() throws Exception {
		createPipeline("Quick Hash", "Hashes incoming assets and stores the result.");
		createPipeline("Ingest & Proxy", "MIME filtering, hashing, fingerprinting and proxy generation.");
		createPipeline("Full Processing", "Filtering, analysis, face detection and multi-output.");
	}

	private void createPipeline(String name, String description) throws Exception {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash")));

		PipelineCreateRequest request = new PipelineCreateRequest();
		request.setName(name);
		request.setDescription(description);
		request.setDefinition(definition);
		request.setEnabled(true);
		client.createPipeline(request).sync().body();
	}

	/**
	 * Run one command and write a transcript of it.
	 *
	 * <p>The file carries the command line as a `$` prompt line followed by the combined
	 * output, so it can be pasted into the docs as-is.</p>
	 */
	private void capture(String name, String... args) throws Exception {
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();

		List<String> full = new ArrayList<>(List.of(
			"--config", configFile.toString(),
			"--no-color"));
		// A sample pointing at a throwaway test port would be noise; show the default.
		boolean overridesServer = List.of(args).contains("-s");
		if (!overridesServer) {
			full.addAll(List.of("--server", serverUrl));
		}
		full.addAll(List.of("--token", token));
		full.addAll(List.of(args));

		int code;
		try (PrintWriter outWriter = new PrintWriter(out); PrintWriter errWriter = new PrintWriter(err)) {
			code = MetaLoomCLIMain.execute(outWriter, errWriter, full.toArray(new String[0]));
		}

		StringBuilder transcript = new StringBuilder();
		transcript.append("$ metaloom ").append(String.join(" ", args)).append('\n');
		transcript.append(err);
		transcript.append(out);
		transcript.append("# exit ").append(code).append('\n');

		Files.writeString(outputDir.resolve(name + ".txt"), transcript.toString());
		System.out.println("--- " + name + " ---\n" + transcript);
	}
}
