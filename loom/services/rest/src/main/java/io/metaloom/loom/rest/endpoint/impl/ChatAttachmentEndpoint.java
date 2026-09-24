package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.ChatAttachmentEndpointService;

/**
 * Files attached to a chat: {@code /api/v1/chats/:uuid/attachments}.
 *
 * <p>
 * A sub-resource of the chat rather than a filter on {@code /attachments}, because the chat is what
 * owns the file and what authorizes the caller. Hanging it off the chat means the ownership check is
 * on the path rather than something every handler has to remember to apply.
 * </p>
 *
 * <p>
 * It secures its own subtree. Registration order across endpoints is undefined, so relying on the
 * auth handler that {@link ChatEndpoint} installs on {@code /chats} would be a race — the same
 * reason {@code ChatStreamEndpoint} and {@code SessionFsEndpoint} secure theirs.
 * </p>
 */
public class ChatAttachmentEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ChatAttachmentEndpoint.class);

	private final ChatAttachmentEndpointService service;
	private final ModelExamples examples;

	@Inject
	public ChatAttachmentEndpoint(ChatAttachmentEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "chat-attachment";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/chats";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "/:uuid/attachments");
		secure(basePath() + "/:uuid/attachments/*");

		addUploadRoute(basePath() + "/:uuid/attachments",
			"Attach a file to a chat. Expects a multipart request with one file part named 'file'. Optional form field: 'poolUuid'. "
				+ "The file belongs to the conversation, not to the media library - it is deleted with the chat, and is not "
				+ "indexed, thumbnailed or processed by pipelines. Use the /asset route to keep one.",
			examples.attachmentResponseExample(),
			lrc -> {
				service.create(lrc, lrc.pathParamUUID("uuid"));
			});

		addListRoute(basePath() + "/:uuid/attachments", GET,
			"List the files attached to a chat, newest first.",
			examples.attachmentListResponseExample(),
			lrc -> {
				service.list(lrc, lrc.pathParamUUID("uuid"));
			});

		// Literal segments before the wildcard-ish ones, and the longest path first: '/data' would
		// otherwise be swallowed by the ':attachmentUuid' route above it.
		addDownloadRoute(basePath() + "/:uuid/attachments/:attachmentUuid/data",
			"Download the raw bytes of a chat attachment.",
			lrc -> {
				service.download(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("attachmentUuid"));
			});

		// A plain POST with no body: the only input is the optional target library, which travels as a
		// query parameter so the route does not have to consume multipart for the sake of one field.
		addRoute(basePath() + "/:uuid/attachments/:attachmentUuid/asset", POST,
			"Save a chat attachment into the media library as a real asset. Optional query parameter: 'libraryUuid', which falls "
				+ "back to LOOM_CHAT_ATTACHMENT_LIBRARY. The attachment itself stays on the chat.",
			null,
			examples.assetResponseExample(),
			lrc -> {
				service.promote(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("attachmentUuid"));
			});

		addRoute(basePath() + "/:uuid/attachments/:attachmentUuid", DELETE,
			"Detach a file from a chat.",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("attachmentUuid"));
			});
	}

}
