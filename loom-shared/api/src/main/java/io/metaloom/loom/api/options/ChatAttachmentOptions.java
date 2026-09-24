package io.metaloom.loom.api.options;

/**
 * Options for chat file attachments — files a user drops onto the chat window.
 *
 * <p>
 * An attachment is <em>not</em> a library asset. It is a {@code CHAT_FILE} row on the shared
 * {@code attachment} table, owned by the chat and deleted with it (V2.112/V2.113). The design goal
 * these options exist to bound is that a dropped file must cost almost nothing until it is used: the
 * agent's system prompt carries only a manifest line per file — id, name, type, size — and the
 * content is pulled on demand by the {@code read_attachment} tool or handed to {@code generate_image}
 * as bytes.
 * </p>
 *
 * <p>
 * That is why {@link #getMaxFiles()} matters more than it looks: the manifest is paid on <b>every
 * turn</b> of the conversation, so it is the one part of this feature with a recurring context cost.
 * The read caps below bound the one-off cost instead.
 * </p>
 *
 * <p>
 * See {@code spec/chat/LOOM_UI_CHAT.md} and {@code spec/loom/MCP.md}.
 * </p>
 */
public class ChatAttachmentOptions implements Option {

	/**
	 * Ten files, because the manifest is a per-turn cost.
	 *
	 * <p>
	 * At roughly 25 tokens a line that is ~250 tokens carried through every message of the chat,
	 * which is affordable. A hundred files would not be, and the failure would be invisible: the
	 * transcript would simply start being evicted earlier.
	 * </p>
	 */
	public static final int DEFAULT_MAX_FILES = 10;

	/** 25 MB — comfortably above a phone photo and below anything that belongs in the library instead. */
	public static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;

	/**
	 * Characters returned by one {@code read_attachment} call when the model names no limit.
	 *
	 * <p>
	 * Roughly 5k tokens. Large enough that a brief or a CSV arrives whole, small enough that a
	 * 400-page export does not evict the conversation that asked for it — the tool reports the
	 * truncation and the model pages on with an offset.
	 * </p>
	 */
	public static final int DEFAULT_MAX_READ_CHARS = 20_000;

	/**
	 * Bytes an MCP {@code resources/read} may return for a binary attachment.
	 *
	 * <p>
	 * Separate from the character cap because this path base64-encodes a picture, so the number is
	 * about wire size rather than context. 8 MB of source is ~10.7 MB encoded.
	 * </p>
	 */
	public static final long DEFAULT_MAX_READ_BYTES = 8L * 1024 * 1024;

	@EnvironmentVariable(name = "LOOM_CHAT_ATTACHMENT_ENABLED", description = "Allow files to be attached to a chat. Off hides the attach control, refuses the routes and omits the attachment manifest from the agent prompt.")
	private boolean enabled = true;

	@EnvironmentVariable(name = "LOOM_CHAT_ATTACHMENT_MAX_FILES", description = "How many files one chat may carry. This is a per-turn context cost: every file contributes a line to the agent's system prompt on every message.")
	private int maxFiles = DEFAULT_MAX_FILES;

	@EnvironmentVariable(name = "LOOM_CHAT_ATTACHMENT_MAX_BYTES", description = "Largest single file that may be attached to a chat, in bytes. LOOM_STORAGE_MAX_UPLOAD_SIZE still applies on top of this.")
	private long maxBytes = DEFAULT_MAX_BYTES;

	@EnvironmentVariable(name = "LOOM_CHAT_ATTACHMENT_MAX_READ_CHARS", description = "Characters one read_attachment call returns when the caller names no limit. Longer files are truncated and the model pages on with an offset.")
	private int maxReadChars = DEFAULT_MAX_READ_CHARS;

	@EnvironmentVariable(name = "LOOM_CHAT_ATTACHMENT_MAX_READ_BYTES", description = "Largest attachment MCP resources/read will base64-encode into a response.")
	private long maxReadBytes = DEFAULT_MAX_READ_BYTES;

	@EnvironmentVariable(name = "LOOM_CHAT_ATTACHMENT_LIBRARY", description = "UUID of the library an attachment is promoted into when 'Save to library' names none. Empty means the caller must choose one.")
	private String libraryUuid = "";

	public boolean isEnabled() {
		return enabled;
	}

	public ChatAttachmentOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public int getMaxFiles() {
		return maxFiles;
	}

	public ChatAttachmentOptions setMaxFiles(int maxFiles) {
		this.maxFiles = maxFiles;
		return this;
	}

	public long getMaxBytes() {
		return maxBytes;
	}

	public ChatAttachmentOptions setMaxBytes(long maxBytes) {
		this.maxBytes = maxBytes;
		return this;
	}

	public int getMaxReadChars() {
		return maxReadChars;
	}

	public ChatAttachmentOptions setMaxReadChars(int maxReadChars) {
		this.maxReadChars = maxReadChars;
		return this;
	}

	public long getMaxReadBytes() {
		return maxReadBytes;
	}

	public ChatAttachmentOptions setMaxReadBytes(long maxReadBytes) {
		this.maxReadBytes = maxReadBytes;
		return this;
	}

	public String getLibraryUuid() {
		return libraryUuid;
	}

	public ChatAttachmentOptions setLibraryUuid(String libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}
}
