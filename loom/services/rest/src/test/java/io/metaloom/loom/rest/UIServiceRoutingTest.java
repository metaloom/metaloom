package io.metaloom.loom.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

/**
 * The UI is a single-page app: React Router owns everything below <code>/ui/</code>, but only the browser knows that. Every path the router can produce
 * has to come back as <code>index.html</code>, or a reload of <code>/ui/memory</code> (or any deep link, or a bookmark) dies on a 404 before the router
 * ever runs. These tests pin that fallback together with the two redirects that funnel bare <code>/</code> and <code>/ui</code> into the app, and — just
 * as importantly — that a genuinely missing bundle file still 404s instead of being masked by an HTML page.
 */
public class UIServiceRoutingTest {

	@TempDir
	static Path uiDir;

	private static Vertx vertx;
	private static HttpServer server;
	private static HttpClient client;
	private static String base;

	@BeforeAll
	static void startServer() throws Exception {
		Files.writeString(uiDir.resolve("index.html"), "<!DOCTYPE html><html><body>INDEX</body></html>");
		Files.createDirectories(uiDir.resolve("assets"));
		Files.writeString(uiDir.resolve("assets/index-abc123.js"), "console.log('bundle');");

		vertx = Vertx.vertx();
		Router router = Router.router(vertx);
		UIService.registerUiRoutes(router, uiDir.toAbsolutePath().toString());

		server = vertx.createHttpServer()
			.requestHandler(router)
			.listen(0)
			.toCompletionStage()
			.toCompletableFuture()
			.get();

		// Redirects are asserted on, not followed.
		client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		base = "http://localhost:" + server.actualPort();
	}

	@AfterAll
	static void stopServer() {
		if (server != null) {
			server.close();
		}
		if (vertx != null) {
			vertx.close();
		}
	}

	private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	public void testRootRedirectsToUi() throws Exception {
		HttpResponse<String> response = get("/");
		assertEquals(302, response.statusCode());
		assertEquals("/ui/", response.headers().firstValue("Location").orElse(null));
	}

	@Test
	public void testUiWithoutTrailingSlashRedirects() throws Exception {
		// Without this the browser resolves the bundle URLs one level too high.
		HttpResponse<String> response = get("/ui");
		assertEquals(302, response.statusCode());
		assertEquals("/ui/", response.headers().firstValue("Location").orElse(null));
	}

	@Test
	public void testUiRootServesIndex() throws Exception {
		HttpResponse<String> response = get("/ui/");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("INDEX"), "Expected the SPA index, got: " + response.body());
	}

	@Test
	public void testClientRouteServesIndex() throws Exception {
		HttpResponse<String> response = get("/ui/memory");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("INDEX"), "Expected the SPA index, got: " + response.body());
		assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(null));
	}

	@Test
	public void testNestedClientRouteServesIndex() throws Exception {
		HttpResponse<String> response = get("/ui/chat/sessions/6f1b0a3c-0000-4000-8000-000000000001");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("INDEX"), "Expected the SPA index, got: " + response.body());
	}

	@Test
	public void testBundleIsServedFromDisk() throws Exception {
		HttpResponse<String> response = get("/ui/assets/index-abc123.js");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("console.log"), "Expected the real bundle, got: " + response.body());
	}

	@Test
	public void testMissingAssetStillReturnsNotFound() throws Exception {
		// A stale index pointing at a removed hashed bundle must fail loudly rather than
		// receive an HTML page that the browser would then try to parse as JavaScript.
		HttpResponse<String> response = get("/ui/assets/index-gone.js");
		assertEquals(404, response.statusCode());
	}
}
