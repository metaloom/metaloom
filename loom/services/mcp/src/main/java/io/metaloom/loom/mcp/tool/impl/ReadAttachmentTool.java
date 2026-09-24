package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.ChatAttachmentOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader.TextSlice;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Read a file the user dropped into the conversation.
 *
 * <h2>Why this tool exists rather than the file simply being in the prompt</h2>
 *
 * <p>
 * An attached file costs one manifest line per turn — id, name, type, size — and nothing more until
 * something asks for it. Inlining the content instead would charge the whole file on every message
 * of the conversation, and a brief plus three photographs would evict the transcript before the
 * second question. This is MCP's resource idea applied in-process: advertise cheaply, fetch on
 * demand. It is the same shape {@code load_skill} already has, and the manifest is built by
 * {@code AttachmentPromptBuilder}.
 * </p>
 *
 * <h2>Scope is resolved on the server, never from the arguments</h2>
 *
 * <p>
 * The chat normally comes from {@link MCPCallerContext#chatUuid()}, which only the in-process agent
 * loop populates. An external MCP client has no chat, so it may name one — and that argument is
 * re-authorized against the chat's creator by {@link ChatAttachmentReader}, so it can only narrow
 * what is visible. A caller naming somebody else's attachment is told it does not exist, because
 * saying "forbidden" would confirm that the uuid is real.
 * </p>
 *
 * <h2>Everything that can go wrong is a sentence, not a failure</h2>
 *
 * <p>
 * An unknown id, a picture, a PDF, a file whose bytes have gone missing: all of these come back as
 * text the model can act on. A failed future ends the turn and tells the user nothing.
 * </p>
 */
@Singleton
public class ReadAttachmentTool implements MCPTool {

	private static final Logger log = LoggerFactory.getLogger(ReadAttachmentTool.class);

	public static final String NAME = "read_attachment";

	private final ChatAttachmentReader reader;
	private final ChatAttachmentOptions options;

	@Inject
	public ReadAttachmentTool(ChatAttachmentReader reader, LoomOptions loomOptions) {
		this.reader = reader;
		this.options = loomOptions.getChatAttachment();
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(NAME,
			"Read a file the user attached to this conversation, listed under <attachments>. Text files - notes, briefs, "
				+ "transcripts, CSV, JSON, code - come back as text. An image cannot be read as text: pass its id to "
				+ "generate_image instead, to edit it or combine it with others. Long files are truncated and the reply says "
				+ "so; read the rest by calling again with offset set past what you already have, rather than repeating the "
				+ "same call.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("attachmentId", "string",
					"The id of the file, as given in the <attachments> list.", true),
				new MCPToolParam("offset", "integer",
					"Character to start reading at. Defaults to 0, the beginning of the file.", false),
				new MCPToolParam("limit", "integer",
					"How many characters to return. Defaults to " + options.getMaxReadChars() + ".", false),
				new MCPToolParam("chatUuid", "string",
					"Which conversation the file belongs to. Only needed by clients outside a chat; inside one it is ignored "
						+ "and the current conversation is used.",
					false))),
			List.of("READ_CHAT", "READ_ATTACHMENT"),
			// The chat scope has to come from the server. Without an identity there is nothing to scope to.
			true);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		if (!ctx.isAuthenticated()) {
			return Future.failedFuture(NAME + " requires an authenticated caller.");
		}
		try {
			UUID attachmentUuid = parseUuid(arguments.getString("attachmentId"));
			if (attachmentUuid == null) {
				return Future.succeededFuture(mcpTextResult(
					"'" + arguments.getString("attachmentId") + "' is not one of the attachment ids. Use an id exactly as it "
						+ "appears in the <attachments> list."));
			}

			// The context's chat wins; the argument is only for callers that have none. Either way
			// ChatAttachmentReader re-checks that the caller owns the chat.
			UUID chatUuid = ctx.chatUuid() != null ? ctx.chatUuid() : parseUuid(arguments.getString("chatUuid"));

			Attachment attachment = reader.resolve(attachmentUuid, chatUuid, ctx.userUuid());
			if (attachment == null) {
				return Future.succeededFuture(mcpTextResult(
					"There is no attachment " + attachmentUuid + " in this conversation."));
			}

			if (!reader.isReadableAsText(attachment)) {
				return Future.succeededFuture(mcpTextResult(unreadable(attachment)));
			}
			if (!reader.exists(attachment)) {
				return Future.succeededFuture(mcpTextResult(
					"The stored file for " + attachment.getFilename() + " is missing, so it cannot be read."));
			}

			int offset = Math.max(orZero(arguments.getInteger("offset")), 0);
			int limit = positiveOr(arguments.getInteger("limit"), options.getMaxReadChars());
			TextSlice slice = reader.text(attachment, offset, limit);

			log.info("read_attachment served {} characters of {} ({}) for user {}",
				slice.text().length(), attachment.getFilename(), attachment.getUuid(), ctx.userUuid());

			return Future.succeededFuture(render(attachment, slice));
		} catch (Exception e) {
			log.error("read_attachment failed", e);
			return Future.succeededFuture(mcpTextResult("The attachment could not be read: " + rootMessage(e)));
		}
	}

	private JsonObject render(Attachment attachment, TextSlice slice) {
		StringBuilder text = new StringBuilder();
		text.append(attachment.getFilename()).append(":\n\n").append(slice.text());
		if (slice.truncated()) {
			// Stated in the same units the offset argument takes, so the next call writes itself.
			text.append("\n\n[Showing characters ").append(slice.from()).append("-").append(slice.to())
				.append(" of ").append(slice.total()).append(". ");
			if (slice.to() < slice.total()) {
				text.append("Call read_attachment again with offset ").append(slice.to()).append(" to continue.]");
			} else {
				text.append("That is the end of the file.]");
			}
		}
		JsonArray references = new JsonArray()
			.add(reference("attachment", attachment.getUuid().toString(), attachment.getFilename()));
		return mcpResultWithReferences(text.toString(), references);
	}

	/**
	 * What to say about a file this tool cannot turn into text.
	 *
	 * <p>
	 * For an image that is not a failure at all — it is a redirect to the tool that <em>can</em> use
	 * it, which is the whole point of attaching a picture. For anything else it names the type, so
	 * the user is told what is missing rather than that "it did not work".
	 * </p>
	 */
	private String unreadable(Attachment attachment) {
		if (ChatAttachmentReader.isImage(attachment)) {
			return attachment.getFilename() + " is an image, so it has no text to read. Pass the id "
				+ attachment.getUuid() + " to generate_image to edit it or combine it with other pictures.";
		}
		return attachment.getFilename() + " is " + attachment.getMimeType()
			+ ". Reading text out of that format is not available in this deployment - only text files "
			+ "(including markdown, CSV, JSON, XML and source code) can be read, and images can be used by generate_image.";
	}

	private static UUID parseUuid(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

	private static int positiveOr(Integer value, int fallback) {
		return value == null || value <= 0 ? fallback : value;
	}

	private static String rootMessage(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
	}

}
