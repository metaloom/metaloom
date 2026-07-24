package io.metaloom.loom.agent.chat.skill;

import java.util.List;

import io.metaloom.loom.db.model.skill.Skill;

/**
 * Assembles the system prompt of the chat agent.
 *
 * <p>Skills follow the pi-style progressive disclosure model: only name + description of the active skills are injected up front; the model fetches the full
 * instructions on demand via the {@code load_skill} tool. Skills flagged with {@code meta.injectFull=true} get their content inlined directly instead — an
 * escape hatch for small models which ignore progressive disclosure.</p>
 */
public final class SkillPromptBuilder {

	public static final String LOAD_SKILL_TOOL = "load_skill";

	private static final String BASE_PROMPT = """
		You are the Loom assistant. You help users work with their media library: find assets, inspect collections, \
		summarize transcripts, review tasks and comments. You MUST use the provided tools to answer questions about \
		the library — do NOT invent assets, collections or statistics. Answer in concise markdown.""";

	private SkillPromptBuilder() {
	}

	/**
	 * Build the system prompt for the given active skills.
	 */
	public static String build(List<Skill> activeSkills) {
		StringBuilder prompt = new StringBuilder(BASE_PROMPT);
		if (activeSkills == null || activeSkills.isEmpty()) {
			return prompt.toString();
		}

		StringBuilder disclosed = new StringBuilder();
		StringBuilder inlined = new StringBuilder();
		for (Skill skill : activeSkills) {
			if (isInjectFull(skill)) {
				inlined.append("\n<skill name=\"").append(skill.getName()).append("\">\n")
					.append(skill.getContent())
					.append("\n</skill>\n");
			} else {
				disclosed.append("- ").append(skill.getName()).append(": ").append(skill.getDescription()).append("\n");
			}
		}

		if (!disclosed.isEmpty()) {
			prompt.append("\n\n<available_skills>\n")
				.append(disclosed)
				.append("</available_skills>\n")
				.append("Use the ").append(LOAD_SKILL_TOOL)
				.append(" tool to read a skill's full instructions before applying it.");
		}
		if (!inlined.isEmpty()) {
			prompt.append("\n\nThe following skill instructions apply to this conversation:\n").append(inlined);
		}
		return prompt.toString();
	}

	private static boolean isInjectFull(Skill skill) {
		return skill.getMeta() != null && Boolean.TRUE.equals(skill.getMeta().getBoolean("injectFull"));
	}

}
