package io.metaloom.loom.rest;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;

@Singleton
public class UIService {

	private static final Logger log = LoggerFactory.getLogger(UIService.class);

	private static final String UI_FS_PATH = "/loom/ui";

	private static final String UI_PATH = "/ui";

	private static final String UI_ROOT = UI_PATH + "/";

	private static final String GRAPHIQL_PATH = "/graphiql";

	private final HttpServer server;
	private final ApiRouter router;

	@Inject
	public UIService(HttpServer server, @Named("restApiRouter") ApiRouter router) {
		this.server = server;
		this.router = router;
	}

	public void start() {
		log.info("Registering UI static file handler at {} -> {}", UI_PATH, UI_FS_PATH);
		registerUiRoutes(router.getDelegate(), UI_FS_PATH);

		// Serve the bundled GraphiQL IDE (classpath resources under graphiql/) at /graphiql/*.
		// This route is intentionally NOT secured: the IDE shell is a static HTML page. It POSTs
		// queries to the secured /api/v1/graphql endpoint with the browser session cookie, so only
		// authenticated users can introspect or execute queries.
		log.info("Registering GraphiQL static file handler at /graphiql");
		router.getDelegate()
			.route(GRAPHIQL_PATH)
			.handler(rc -> rc.response().setStatusCode(302).putHeader("Location", "/graphiql/").end());
		router.getDelegate()
			.route(GRAPHIQL_PATH + "/*")
			.handler(StaticHandler.create("graphiql"));
	}

	/**
	 * Registers the routes that serve the single-page UI out of <code>uiFsPath</code>. Split out of {@link #start()} so it can be exercised against a
	 * throwaway directory in tests.
	 */
	static void registerUiRoutes(Router router, String uiFsPath) {
		// The SPA lives under /ui/, so the bare host and the prefix without its trailing
		// slash have to be sent there — otherwise http://host/ is a 404 and http://host/ui
		// resolves relative URLs one level too high.
		router.route("/")
			.handler(rc -> redirect(rc, UI_ROOT));
		// Regex, not route(UI_PATH): a plain path route also matches the trailing-slash form,
		// which would make /ui/ redirect to itself forever.
		router.routeWithRegex(UI_PATH)
			.handler(rc -> redirect(rc, UI_ROOT));

		// SPA fallback, registered *before* the static handler: a client-side route such as
		// /ui/memory has no file behind it, and letting the static handler answer would 404
		// on reload and on every deep link. Anything whose last segment carries an extension
		// is a real asset and is passed through.
		router.route(UI_ROOT + "*")
			.handler(rc -> {
				if (isClientRoute(rc.normalizedPath())) {
					sendIndex(rc, uiFsPath + "/index.html");
				} else {
					rc.next();
				}
			});
		router.route(UI_ROOT + "*")
			.handler(StaticHandler.create(FileSystemAccess.ROOT, uiFsPath));
	}

	/**
	 * Whether the path addresses a client-side route rather than a file shipped with the bundle. Every asset emitted by the build carries an extension
	 * in its last segment, so the absence of one is what separates <code>/ui/chat/sessions/abc</code> from <code>/ui/assets/index-4f2a.js</code>.
	 */
	private static boolean isClientRoute(String path) {
		int lastSlash = path.lastIndexOf('/');
		String lastSegment = lastSlash < 0 ? path : path.substring(lastSlash + 1);
		return lastSegment.indexOf('.') < 0;
	}

	private static void sendIndex(RoutingContext rc, String indexPath) {
		rc.response()
			.putHeader("Content-Type", "text/html; charset=utf-8")
			// The index references hashed bundles; a cached copy would keep pointing at
			// files that a redeploy has already removed.
			.putHeader("Cache-Control", "no-cache")
			.sendFile(indexPath);
	}

	private static void redirect(RoutingContext rc, String location) {
		rc.response()
			.setStatusCode(302)
			.putHeader("Location", location)
			.end();
	}

	public HttpServer getServer() {
		return server;
	}
}
