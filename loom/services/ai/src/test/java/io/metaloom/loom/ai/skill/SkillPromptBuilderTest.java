package io.metaloom.loom.ai.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.skill.Skill;
import io.vertx.core.json.JsonObject;

public class SkillPromptBuilderTest {

	private static Skill skill(String name, String description, String content, JsonObject meta) {
		Skill skill = mock(Skill.class);
		when(skill.getName()).thenReturn(name);
		when(skill.getDescription()).thenReturn(description);
		when(skill.getContent()).thenReturn(content);
		when(skill.getMeta()).thenReturn(meta);
		return skill;
	}

	@Test
	public void testNoSkills() {
		String prompt = SkillPromptBuilder.build(List.of());
		assertFalse(prompt.contains("<available_skills>"));
		assertTrue(prompt.contains("Loom assistant"));
	}

	@Test
	public void testProgressiveDisclosure() {
		String prompt = SkillPromptBuilder.build(List.of(
			skill("summarizer", "Summarize transcripts", "# Full instructions", null)));

		assertTrue(prompt.contains("<available_skills>"));
		assertTrue(prompt.contains("- summarizer: Summarize transcripts"));
		assertTrue(prompt.contains(SkillPromptBuilder.LOAD_SKILL_TOOL));
		assertFalse(prompt.contains("# Full instructions"), "Content must only be available via load_skill");
	}

	@Test
	public void testInjectFullEscapeHatch() {
		String prompt = SkillPromptBuilder.build(List.of(
			skill("inline-skill", "Inlined skill", "# Inlined instructions", new JsonObject().put("injectFull", true)),
			skill("lazy-skill", "Lazy skill", "# Lazy instructions", null)));

		assertTrue(prompt.contains("<skill name=\"inline-skill\">"));
		assertTrue(prompt.contains("# Inlined instructions"));
		assertTrue(prompt.contains("- lazy-skill: Lazy skill"));
		assertFalse(prompt.contains("# Lazy instructions"));
	}

}
