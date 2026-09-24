package io.metaloom.loom.agent.chat.prompt;

import java.util.List;

import io.metaloom.loom.agent.chat.skill.AgentSkill;
import io.metaloom.loom.agent.chat.skill.SkillPromptBuilder;
import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.agent.memory.prompt.MemoryPromptBuilder;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.mcp.attachment.AttachmentTextExtractor;

/**
 * Assembles the full system prompt of the chat agent from its parts.
 *
 * <p>All three parts follow the same progressive-disclosure rule: skills contribute name + description, memory contributes its index, and attachments
 * contribute a manifest line each; the bodies of any of them are fetched on demand with {@code load_skill} / {@code get_memory} /
 * {@code read_attachment}.</p>
 *
 * <p>That rule is what keeps this prompt a fixed cost. It is rebuilt on <em>every</em> turn, so anything inlined here is paid for again with each
 * message the user sends — which is why a forty-megabyte attachment contributes the same one line as a text note.</p>
 */
public final class SystemPromptBuilder {

	private SystemPromptBuilder() {
	}

	/**
	 * @param memory
	 *            The memory service, or {@code null} when the memory bank is disabled
	 * @param scopes
	 *            The caller's memory scopes (empty when memory is disabled)
	 * @param index
	 *            Header-only memory entries, newest first
	 * @param sandboxEnabled
	 *            Whether a session container exists — only then is the read-only memory folder mentioned
	 * @param attachments
	 *            Files the user dropped into this chat, newest first (empty when there are none)
	 * @param extractor
	 *            Decides which attachments can be read as text, or null when attachments are disabled
	 * @param maxAttachments
	 *            How many attachments to list
	 */
	public static String build(List<AgentSkill> activeSkills, MemoryService memory, List<MemoryScopeRef> scopes, List<MemoryEntry> index,
		boolean sandboxEnabled, List<Attachment> attachments, AttachmentTextExtractor extractor, int maxAttachments) {
		String prompt = SkillPromptBuilder.build(activeSkills);
		if (memory != null && memory.isEnabled()) {
			prompt = prompt + MemoryPromptBuilder.build(memory, scopes, index, sandboxEnabled);
		}
		// Last, and deliberately: the attachments are the most concrete thing in the prompt and the
		// part most likely to be acted on this turn, so it sits closest to the conversation.
		return prompt + AttachmentPromptBuilder.build(attachments, extractor, maxAttachments);
	}

}
