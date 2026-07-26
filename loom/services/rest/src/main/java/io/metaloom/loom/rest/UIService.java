package io.metaloom.loom.rest;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;

@Singleton
public class UIService {

	private static final Logger log = LoggerFactory.getLogger(UIService.class);

	private static final String UI_FS_PATH = "/loom/ui";

	private static final String GRAPHIQL_PATH = "/graphiql";

	private final HttpServer server;
	private final ApiRouter router;

	@Inject
	public UIService(HttpServer server, @Named("restApiRouter") ApiRouter router) {
		this.server = server;
		this.router = router;
	}

	public void start() {
		log.info("Registering UI static file handler at /ui -> {}", UI_FS_PATH);
		router.getDelegate()
			.route("/ui/*")
			.handler(StaticHandler.create(FileSystemAccess.ROOT, UI_FS_PATH));

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

	public HttpServer getServer() {
		return server;
	}
}
