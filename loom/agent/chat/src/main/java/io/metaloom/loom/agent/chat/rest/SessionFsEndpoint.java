package io.metaloom.loom.agent.chat.rest;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;

/**
 * Read-only proxy of a chat's live coding-session filesystem into the Loom UI, keyed by the chat uuid
 * (the same id used as the sandbox session key). Lets a user browse, download and preview the files
 * the coding agent produced in its {@code /workspace}.
 *
 * <ul>
 * <li>{@code GET /session/:uuid/files?path=} — list a workspace directory</li>
 * <li>{@code GET /session/:uuid/download?path=&inline=} — download a workspace file</li>
 * <li>{@code GET /session/:uuid/preview?path=} — sandboxed inline preview (CSP: sandbox)</li>
 * </ul>
 *
 * <p>Nested paths are passed via the {@code path} query parameter so arbitrary depth works without
 * server-side wildcard routing. The Loom UI surfaces these under its own {@code /session/:uuid} view,
 * complementing the app served at {@code /ui}.</p>
 */
public class SessionFsEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(SessionFsEndpoint.class);

	private final SessionFsEndpointService service;

	@Inject
	public SessionFsEndpoint(SessionFsEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "session-fs";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/session";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// Secure our own subtree independently of endpoint registration order.
		secure(basePath() + "/:uuid/files");
		secure(basePath() + "/:uuid/download");
		secure(basePath() + "/:uuid/preview");

		addRoute(basePath() + "/:uuid/files", GET,
			"List a directory of the chat's live coding-session workspace",
			lrc -> service.listFiles(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid/download", GET,
			"Download a file from the chat's live coding-session workspace",
			lrc -> service.download(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid/preview", GET,
			"Sandboxed inline preview of a file from the chat's live coding-session workspace",
			lrc -> service.preview(lrc, lrc.pathParamUUID("uuid")));
	}

}
