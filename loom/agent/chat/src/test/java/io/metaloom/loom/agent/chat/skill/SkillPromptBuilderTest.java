package io.metaloom.loom.agent.chat.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.common.skill.BuiltinSkill;
import io.metaloom.loom.common.skill.BuiltinSkills;
import io.metaloom.loom.db.model.skill.Skill;
import io.vertx.core.json.JsonObject;

public class SkillPromptBuilderTest {

	private static AgentSkill skill(String name, String description, String content, JsonObject meta) {
		Skill skill = mock(Skill.class);
		when(skill.getName()).thenReturn(name);
		when(skill.getDescription()).thenReturn(description);
		when(skill.getContent()).thenReturn(content);
		when(skill.getMeta()).thenReturn(meta);
		return AgentSkill.of(skill);
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

	/**
	 * A built-in skill is disclosed exactly like a stored one: the whole point of {@link AgentSkill} is that the prompt cannot tell them apart.
	 */
	@Test
	public void testBuiltinSkillIsDisclosed() {
		BuiltinSkill builtin = BuiltinSkills.byName(BuiltinSkills.PIPELINE_AUTHORING);
		assertNotNull(builtin, "The pipeline authoring skill must ship with Loom");

		String prompt = SkillPromptBuilder.build(List.of(AgentSkill.of(builtin)));
		assertTrue(prompt.contains("- " + BuiltinSkills.PIPELINE_AUTHORING + ": " + builtin.description()));
		assertFalse(prompt.contains("## The workflow"),
			"A built-in is disclosed progressively — its body belongs in load_skill, not in every system prompt");
	}

	/**
	 * Built-ins are never inlined, whatever a same-named stored skill asks for. The two are separate objects; an owned skill cannot reach into one that
	 * ships with Loom.
	 */
	@Test
	public void testBuiltinIsNeverInlined() {
		AgentSkill builtin = AgentSkill.of(BuiltinSkills.byName(BuiltinSkills.PIPELINE_AUTHORING));
		assertFalse(builtin.injectFull());

		String prompt = SkillPromptBuilder.build(List.of(
			builtin,
			skill(BuiltinSkills.PIPELINE_AUTHORING, "A user's own take", "# Shadowing content", new JsonObject().put("injectFull", true))));

		// The user's copy still behaves as its own skill; what must not happen is the built-in body
		// being dragged into the prompt with it.
		assertTrue(prompt.contains("# Shadowing content"));
		assertFalse(prompt.contains("## The workflow"));
	}

	/**
	 * {@code load_skill} resolves by name over the same list, first match wins, and the loop puts built-ins first — so a stored skill that borrows the
	 * name of a built-in cannot replace the instructions Loom ships.
	 */
	@Test
	public void testBuiltinWinsNameCollision() {
		List<AgentSkill> active = List.of(
			AgentSkill.of(BuiltinSkills.byName(BuiltinSkills.PIPELINE_AUTHORING)),
			skill(BuiltinSkills.PIPELINE_AUTHORING, "A user's own take", "# Shadowing content", null));

		AgentSkill resolved = active.stream().filter(s -> s.name().equals(BuiltinSkills.PIPELINE_AUTHORING)).findFirst().orElseThrow();
		assertEquals(BuiltinSkills.byName(BuiltinSkills.PIPELINE_AUTHORING).content(), resolved.content());
	}

}
