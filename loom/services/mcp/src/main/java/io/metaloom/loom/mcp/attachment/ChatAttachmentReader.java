package io.metaloom.loom.mcp.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver;
import io.metaloom.loom.storage.BinaryStorage;

/**
 * Resolves a chat attachment, authorizes the caller for it, and reads its bytes.
 *
 * <p>
 * One place rather than two, because both consumers have the same three things to get right and
 * exactly one of them is a security boundary: the {@code read_attachment} tool and MCP
 * {@code resources/read}. A second copy of the authorization rule is a second chance to leave it
 * out.
 * </p>
 *
 * <h2>The authorization rule</h2>
 *
 * <p>
 * A chat attachment belongs to the <em>creator of its chat</em> and to nobody else. Permissions get
 * a caller as far as the door — {@code READ_ATTACHMENT} is checked by the registry before any of
 * this runs — but they cannot express "your own conversations", and a chat is private
 * correspondence. So every path through this class ends at
 * {@link #resolve(UUID, UUID, UUID)}, which insists the chat's creator is the caller.
 * </p>
 *
 * <p>
 * The chat uuid may arrive from the caller context (the in-process agent, where the server put it
 * there) or as a tool argument (an external MCP client, where the model put it there). This class
 * treats both identically and re-checks ownership either way, which is what makes the second case
 * safe: a model-supplied scope narrows what is returned and can never widen it.
 * </p>
 *
 * <p>
 * Absent and forbidden are the same answer. A caller naming somebody else's attachment uuid gets
 * {@code null}, exactly as if the row did not exist — the same reason {@code loadOwnedChat} answers
 * 404 rather than 403 for a foreign chat. Distinguishing them would confirm that a uuid is real.
 * </p>
 */
@Singleton
public class ChatAttachmentReader {

	private final DaoCollection daos;
	private final BinaryStorageResolver storageResolver;
	private final AttachmentTextExtractor extractor;

	@Inject
	public ChatAttachmentReader(DaoCollection daos, BinaryStorageResolver storageResolver, AttachmentTextExtractor extractor) {
		this.daos = daos;
		this.storageResolver = storageResolver;
		this.extractor = extractor;
	}

	/**
	 * The attachment, if it exists, is a chat file, and the caller owns the chat it hangs off.
	 *
	 * @param attachmentUuid the attachment being asked for
	 * @param chatUuid the chat it must belong to, or null to accept any chat the caller owns
	 * @param callerUuid the user asking
	 * @return the attachment, or null when it does not exist or is not the caller's
	 */
	public Attachment resolve(UUID attachmentUuid, UUID chatUuid, UUID callerUuid) {
		if (attachmentUuid == null || callerUuid == null) {
			return null;
		}
		Attachment attachment = daos.attachmentDao().load(attachmentUuid);
		if (attachment == null || attachment.getType() != AttachmentType.CHAT_FILE || attachment.getChatUuid() == null) {
			return null;
		}
		// A named chat narrows; it never widens, because ownership is still checked below.
		if (chatUuid != null && !chatUuid.equals(attachment.getChatUuid())) {
			return null;
		}
		return ownsChat(attachment.getChatUuid(), callerUuid) ? attachment : null;
	}

	/** Every file on a chat the caller owns, newest first. Empty for a chat that is not theirs. */
	public List<Attachment> list(UUID chatUuid, UUID callerUuid) {
		if (chatUuid == null || callerUuid == null || !ownsChat(chatUuid, callerUuid)) {
			return List.of();
		}
		return daos.attachmentDao().listByChat(chatUuid);
	}

	/**
	 * Whether {@link #text(Attachment, int, int)} will return content for this attachment.
	 */
	public boolean isReadableAsText(Attachment attachment) {
		return attachment != null && extractor.supports(attachment.getMimeType());
	}

	/** Whether this attachment is a picture, and therefore something the image tools can work on. */
	public static boolean isImage(Attachment attachment) {
		String mimeType = attachment == null ? null : PlainTextExtractor.normalize(attachment.getMimeType());
		return mimeType != null && mimeType.startsWith("image/");
	}

	/**
	 * A window of the attachment's text.
	 *
	 * <p>
	 * Offsets are in characters, not bytes, because that is the unit the model reasons in when it
	 * asks for the next page — and the unit the truncation notice is written in.
	 * </p>
	 *
	 * @return the slice and how much there was in total
	 * @throws IllegalStateException when the attachment is not readable as text; callers check
	 *             {@link #isReadableAsText} first and answer in words
	 */
	public TextSlice text(Attachment attachment, int offset, int limit) throws IOException {
		if (!isReadableAsText(attachment)) {
			throw new IllegalStateException("Not readable as text: " + attachment.getMimeType());
		}
		String full;
		try (InputStream in = open(attachment)) {
			full = extractor.extract(in, attachment.getMimeType());
		}
		int from = Math.min(Math.max(offset, 0), full.length());
		int to = limit <= 0 ? full.length() : Math.min(from + limit, full.length());
		return new TextSlice(full.substring(from, to), from, to, full.length());
	}

	/** The raw bytes. The caller closes the stream. */
	public InputStream open(Attachment attachment) {
		BinaryStorage storage = storageResolver.forPool(attachment.getPoolUuid());
		String locator = storage.locatorFor(attachment.getSha512sum());
		return storage.read(locator, 0, -1);
	}

	/** Whether the bytes are actually on disk. A row can outlive its file; the caller says so in words. */
	public boolean exists(Attachment attachment) {
		BinaryStorage storage = storageResolver.forPool(attachment.getPoolUuid());
		return storage.exists(storage.locatorFor(attachment.getSha512sum()));
	}

	/** The title of the chat an attachment hangs off, for listings that show where a file came from. */
	public String chatTitle(Attachment attachment) {
		if (attachment == null || attachment.getChatUuid() == null) {
			return null;
		}
		Chat chat = daos.chatDao().load(attachment.getChatUuid());
		return chat == null ? null : chat.getTitle();
	}

	private boolean ownsChat(UUID chatUuid, UUID callerUuid) {
		Chat chat = daos.chatDao().load(chatUuid);
		return chat != null && callerUuid.equals(chat.getCreatorUuid());
	}

	/**
	 * A window of an attachment's text, and the size of the whole.
	 *
	 * @param text the slice
	 * @param from first character index included
	 * @param to first character index not included
	 * @param total characters in the whole document
	 */
	public record TextSlice(String text, int from, int to, int total) {

		public boolean truncated() {
			return from > 0 || to < total;
		}
	}

}
