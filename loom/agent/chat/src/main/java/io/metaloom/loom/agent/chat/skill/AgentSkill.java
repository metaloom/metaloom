package io.metaloom.loom.agent.chat.skill;

import io.metaloom.loom.common.skill.BuiltinSkill;
import io.metaloom.loom.db.model.skill.Skill;

/**
 * One skill as the agent loop sees it.
 *
 * <p>
 * A run draws its instructions from two places that are the same thing to the model and different things to the database: the skills that ship with
 * Loom ({@link BuiltinSkill}, no owner, no uuid, always available) and the skills a user wrote ({@link Skill}, owned, versioned, activated per chat).
 * The prompt builder and the {@code load_skill} handler care about exactly four fields, none of which is identity, so they work against this view and
 * the two sources collapse into one list.
 * </p>
 *
 * @param name
 *            what the model passes to {@code load_skill}
 * @param description
 *            the single line that goes into {@code <available_skills>}
 * @param content
 *            the full markdown body, disclosed on demand
 * @param injectFull
 *            inline the body in the system prompt instead of disclosing it progressively
 */
public record AgentSkill(String name, String description, String content, boolean injectFull) {

	/**
	 * Adapt a stored skill. {@code meta.injectFull} is the escape hatch for small models that ignore progressive disclosure.
	 */
	public static AgentSkill of(Skill skill) {
		boolean injectFull = skill.getMeta() != null && Boolean.TRUE.equals(skill.getMeta().getBoolean("injectFull"));
		return new AgentSkill(skill.getName(), skill.getDescription(), skill.getContent(), injectFull);
	}

	/**
	 * Adapt a built-in skill. Never inlined: built-ins are always active, so inlining one would spend its whole body on every run of every chat, which
	 * is the cost progressive disclosure exists to avoid.
	 */
	public static AgentSkill of(BuiltinSkill skill) {
		return new AgentSkill(skill.name(), skill.description(), skill.content(), false);
	}

}
