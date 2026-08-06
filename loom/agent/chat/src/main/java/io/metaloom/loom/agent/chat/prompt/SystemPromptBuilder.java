package io.metaloom.loom.agent.chat.prompt;

import java.util.List;

import io.metaloom.loom.agent.chat.skill.AgentSkill;
import io.metaloom.loom.agent.chat.skill.SkillPromptBuilder;
import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.agent.memory.prompt.MemoryPromptBuilder;
import io.metaloom.loom.db.model.memory.MemoryEntry;

/**
 * Assembles the full system prompt of the chat agent from its parts.
 *
 * <p>Both parts follow the same progressive-disclosure rule: skills contribute name + description and memory contributes its index; the bodies of either
 * are fetched on demand with {@code load_skill} / {@code get_memory}.</p>
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
	 */
	public static String build(List<AgentSkill> activeSkills, MemoryService memory, List<MemoryScopeRef> scopes, List<MemoryEntry> index,
		boolean sandboxEnabled) {
		String prompt = SkillPromptBuilder.build(activeSkills);
		if (memory == null || !memory.isEnabled()) {
			return prompt;
		}
		return prompt + MemoryPromptBuilder.build(memory, scopes, index, sandboxEnabled);
	}

}
