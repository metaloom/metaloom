package io.metaloom.loom.mcp.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.ChatAttachmentOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader.TextSlice;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP resources, backed by the files users have dropped into their chats.
 *
 * <h2>Why these are the resources</h2>
 *
 * <p>
 * {@code resources/list} and {@code resources/read} were stubs — an empty array and a
 * method-not-found. The question they left open was <em>which</em> of Loom's data should be a
 * resource, and a chat attachment answers it well: it is already exactly what MCP resources are
 * for — content the host offers cheaply and the client pulls only when it needs it. The in-chat
 * agent gets the same thing through the {@code <attachments>} manifest and {@code read_attachment};
 * this is that idea spoken over the wire, so a file dropped into Loom is readable from an external
 * MCP client too.
 * </p>
 *
 * <p>
 * Assets are deliberately <em>not</em> resources. A catalog of millions is not a list, and the
 * search tools already answer questions about it far better than an enumeration would.
 * </p>
 *
 * <h2>Scoping</h2>
 *
 * <p>
 * An external MCP client has no chat — {@code MCPJsonRpcHandler} leaves {@code chatUuid} null on
 * purpose — so the listing is scoped by <b>creator</b> instead: your own files, across your own
 * conversations. That is why {@code AttachmentDao} grew {@code listChatFilesByCreator} rather than
 * this going through {@code ChatDao}, which has no query by creator at all.
 * </p>
 *
 * <p>
 * Reads go through {@link ChatAttachmentReader}, which re-checks that the caller owns the chat the
 * attachment hangs off. So a client that guesses or copies a URI gets nothing, and the listing being
 * creator-scoped is a convenience rather than the security boundary.
 * </p>
 */
@Singleton
public class ChatAttachmentResourceProvider {

	private static final Logger log = LoggerFactory.getLogger(ChatAttachmentResourceProvider.class);

	/** Scheme and path of an attachment resource: {@code loom://attachment/<uuid>}. */
	public static final String URI_PREFIX = "loom://attachment/";

	/** What a caller must hold to see or read any of this. */
	public static final List<String> REQUIRED_PERMISSIONS = List.of("READ_ATTACHMENT");

	/**
	 * How many resources one listing returns.
	 *
	 * <p>
	 * There is no cursor in this implementation, so this is the whole answer rather than a page. A
	 * client that wants a specific older file has the chat UI to find it in; a protocol listing is
	 * for discovering what is recent.
	 * </p>
	 */
	public static final int LIST_LIMIT = 100;

	private final DaoCollection daos;
	private final ChatAttachmentReader reader;
	private final ChatAttachmentOptions options;

	@Inject
	public ChatAttachmentResourceProvider(DaoCollection daos, ChatAttachmentReader reader, LoomOptions loomOptions) {
		this.daos = daos;
		this.reader = reader;
		this.options = loomOptions.getChatAttachment();
	}

	/** The {@code resources} array of a {@code resources/list} result. */
	public JsonArray list(UUID callerUuid) {
		JsonArray resources = new JsonArray();
		if (!options.isEnabled() || callerUuid == null) {
			return resources;
		}
		for (Attachment attachment : daos.attachmentDao().listChatFilesByCreator(callerUuid, LIST_LIMIT)) {
			String chatTitle = reader.chatTitle(attachment);
			JsonObject resource = new JsonObject()
				.put("uri", URI_PREFIX + attachment.getUuid())
				.put("name", attachment.getFilename());
			if (attachment.getMimeType() != null) {
				resource.put("mimeType", attachment.getMimeType());
			}
			// The chat title is the only thing that tells two files of the same name apart, and it is
			// what a person would use to recognise one.
			resource.put("description", chatTitle == null
				? "Attached to a chat"
				: "Attached to the chat \"" + chatTitle + "\"");
			resources.add(resource);
		}
		return resources;
	}

	/**
	 * The {@code contents} array of a {@code resources/read} result.
	 *
	 * <p>
	 * Text comes back as {@code text}, anything else as a base64 {@code blob} — which is the one
	 * place in this feature where a picture's bytes travel inline. That is allowed here and not in a
	 * chat visual because this is a protocol read with an explicit request behind it, rather than
	 * something pushed into a conversation; it is still capped by
	 * {@code LOOM_CHAT_ATTACHMENT_MAX_READ_BYTES}.
	 * </p>
	 *
	 * @return the contents array, or null when the uri names nothing the caller may read
	 */
	public JsonArray read(String uri, UUID callerUuid) throws IOException {
		if (!options.isEnabled()) {
			return null;
		}
		UUID attachmentUuid = parse(uri);
		if (attachmentUuid == null) {
			return null;
		}
		// Null chat: any chat of the caller's. resolve() still insists the caller owns it.
		Attachment attachment = reader.resolve(attachmentUuid, null, callerUuid);
		if (attachment == null || !reader.exists(attachment)) {
			return null;
		}

		JsonObject content = new JsonObject().put("uri", uri);
		if (attachment.getMimeType() != null) {
			content.put("mimeType", attachment.getMimeType());
		}

		if (reader.isReadableAsText(attachment)) {
			TextSlice slice = reader.text(attachment, 0, Integer.MAX_VALUE);
			content.put("text", slice.text());
			return new JsonArray().add(content);
		}

		if (attachment.getSize() > options.getMaxReadBytes()) {
			log.info("Refusing to inline attachment {} of {} bytes into a resources/read", attachment.getUuid(), attachment.getSize());
			return null;
		}
		try (InputStream in = reader.open(attachment)) {
			content.put("blob", Base64.getEncoder().encodeToString(in.readAllBytes()));
		}
		return new JsonArray().add(content);
	}

	/** Whether this uri is one of ours, so an unknown scheme is reported as unknown rather than as absent. */
	public static boolean handles(String uri) {
		return uri != null && uri.startsWith(URI_PREFIX);
	}

	private static UUID parse(String uri) {
		if (!handles(uri)) {
			return null;
		}
		try {
			return UUID.fromString(uri.substring(URI_PREFIX.length()).trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

}
