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
	}

	public HttpServer getServer() {
		return server;
	}
}
