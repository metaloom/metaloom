package io.metaloom.loom.agent.chat.prompt;

import java.util.List;
import java.util.Locale;

import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.mcp.attachment.AttachmentTextExtractor;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader;

/**
 * The {@code <attachments>} block: what the user dropped into this conversation, and nothing of what
 * is in it.
 *
 * <p>
 * This is the whole reason chat attachments do not cost what they weigh. A file contributes one line
 * — id, name, type, size, and whether it can be read — which is roughly twenty-five tokens whether
 * the file is a note or a forty-megabyte video. The content is fetched by {@code read_attachment},
 * or handed to {@code generate_image} as bytes, only when the conversation actually calls for it.
 * </p>
 *
 * <p>
 * It is the same progressive-disclosure shape {@link io.metaloom.loom.agent.chat.skill.SkillPromptBuilder}
 * uses for skills, and for the same reason. The difference worth knowing is that this cost is
 * <em>recurring</em>: the block is rebuilt into the system prompt on every turn, so the number of
 * files a chat may carry is capped ({@code LOOM_CHAT_ATTACHMENT_MAX_FILES}) where the number of
 * skills is not.
 * </p>
 *
 * <p>
 * Pure and static so the exact wording can be pinned by a test. The wording is not decoration: it is
 * what stops a model from claiming to have read a file it never opened, and what sends it to
 * {@code generate_image} for a picture instead of apologising that it cannot see images.
 * </p>
 */
public final class AttachmentPromptBuilder {

	public static final String READ_TOOL = "read_attachment";

	private AttachmentPromptBuilder() {
	}

	/**
	 * Render the block, or the empty string when there is nothing to render.
	 *
	 * <p>
	 * Empty means <em>absent</em>, not an empty block. A chat with no files must cost exactly what it
	 * cost before this feature existed, and an empty {@code <attachments>} section would also invite
	 * the model to talk about attachments nobody mentioned.
	 * </p>
	 *
	 * @param attachments the chat's files, newest first
	 * @param extractor decides which of them can be read as text — the same one the tool uses, so the
	 *            manifest cannot promise something {@code read_attachment} then refuses
	 * @param maxFiles how many to list
	 */
	public static String build(List<Attachment> attachments, AttachmentTextExtractor extractor, int maxFiles) {
		if (attachments == null || attachments.isEmpty() || maxFiles <= 0) {
			return "";
		}
		List<Attachment> listed = attachments.size() > maxFiles ? attachments.subList(0, maxFiles) : attachments;

		StringBuilder block = new StringBuilder("\n\n<attachments>\n");
		for (Attachment attachment : listed) {
			block.append("- ").append(attachment.getUuid())
				.append(" · ").append(attachment.getFilename())
				.append(" · ").append(attachment.getMimeType() == null ? "unknown type" : attachment.getMimeType())
				.append(" · ").append(humanSize(attachment.getSize()))
				.append(" · ").append(kind(attachment, extractor))
				.append("\n");
		}
		block.append("</attachments>\n");

		// Said plainly because the failure it prevents is the expensive one: a model that assumes a
		// listed file is already in front of it will answer questions about it from the filename.
		block.append("The user attached these files to this conversation. Their contents are NOT in this conversation. ")
			.append("Use the ").append(READ_TOOL).append(" tool to read one before answering questions about it. ")
			.append("Pass an image's id to generate_image to edit it or combine it with other images.");

		if (attachments.size() > listed.size()) {
			block.append(" (").append(attachments.size() - listed.size())
				.append(" older attachment(s) are not listed.)");
		}
		return block.toString();
	}

	/** What the agent can do with this file, in one word. */
	private static String kind(Attachment attachment, AttachmentTextExtractor extractor) {
		if (extractor != null && extractor.supports(attachment.getMimeType())) {
			return "readable as text";
		}
		if (ChatAttachmentReader.isImage(attachment)) {
			return "image, for generate_image";
		}
		return "cannot be read in this deployment";
	}

	/**
	 * Bytes as a person would write them.
	 *
	 * <p>
	 * Powers of two with the familiar names, matching {@code formatBytes} in the chat UI for the same
	 * file — the two numbers appearing side by side and disagreeing would be its own small bug.
	 * </p>
	 *
	 * <p>
	 * {@code Locale.ROOT} rather than the default: this string goes into a prompt, and on a server
	 * running under a German locale the default would write "1,2 MB", which reads as a different
	 * number to a model that has been shown "1.2 MB" everywhere else.
	 * </p>
	 */
	static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return Math.round(bytes / 1024.0) + " KB";
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
		}
		return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
	}

}
