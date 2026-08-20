package io.metaloom.loom.common.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The instruction packages that ship with Loom, loaded from the classpath.
 *
 * <p>
 * User skills are rows in {@code skill}: owned, versioned, and opt-in per chat through {@code skillUuids}. That is the right model for a house
 * convention somebody wrote, and the wrong one for knowledge the agent needs in order to use a Loom feature at all — nobody should have to author, and
 * then remember to tick, the document that explains how a pipeline definition is shaped. Built-ins are therefore always available and are not rows.
 * </p>
 *
 * <p>
 * They are also the one place that knowledge lives. The {@code pipeline_authoring_guide} MCP tool serves the same text to external MCP clients, which
 * have no notion of a skill at all, so an agent gets the identical guidance whichever door it came in through.
 * </p>
 *
 * <p>
 * Name and description are declared here rather than parsed out of the markdown: Loom skills have never had frontmatter — {@code name} and
 * {@code description} are columns — and inventing a parser for two fields would create a second, subtly different skill format.
 * </p>
 */
public final class BuiltinSkills {

	/** How to design, validate and store a pipeline definition. */
	public static final String PIPELINE_AUTHORING = "pipeline-authoring";

	/** How to turn a question about the catalogue into a {@code find_assets} call. */
	public static final String ASSET_SEARCH = "asset-search";

	private record Definition(String name, String description, String resource) {
	}

	private static final List<Definition> DEFINITIONS = List.of(
		new Definition(PIPELINE_AUTHORING,
			"Design, validate and store a Loom processing pipeline: the shape of the definition JSON, how nodes are wired port-to-port, "
				+ "and which tools to call in which order. Load this before creating or changing any pipeline.",
			"/skills/pipeline-authoring.md"),
		new Definition(ASSET_SEARCH,
			"Find assets in the catalogue: which search tool to use, how to express who/when/where as filters rather than as "
				+ "search words, and why a name is passed through rather than looked up first. Load this before answering any "
				+ "question about which assets exist.",
			"/skills/asset-search.md"));

	/**
	 * Loaded once on first use. A missing resource is a packaging fault, not a runtime condition, so it fails loudly here rather than leaving the agent
	 * to answer pipeline questions from memory — a silently absent guide looks exactly like a model that has decided not to use it.
	 */
	private static final class Holder {

		private static final List<BuiltinSkill> SKILLS = load();

		private static List<BuiltinSkill> load() {
			List<BuiltinSkill> skills = new ArrayList<>();
			for (Definition definition : DEFINITIONS) {
				skills.add(new BuiltinSkill(definition.name(), definition.description(), read(definition.resource())));
			}
			return List.copyOf(skills);
		}

		private static String read(String resource) {
			try (InputStream in = BuiltinSkills.class.getResourceAsStream(resource)) {
				if (in == null) {
					throw new IllegalStateException("Built-in skill resource not found on the classpath: " + resource);
				}
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalStateException("Could not read built-in skill resource: " + resource, e);
			}
		}
	}

	private BuiltinSkills() {
	}

	/**
	 * Every built-in skill, in declaration order.
	 */
	public static List<BuiltinSkill> list() {
		return Holder.SKILLS;
	}

	/**
	 * @param name
	 *            the skill name
	 * @return the skill, or {@code null} when no built-in carries that name
	 */
	public static BuiltinSkill byName(String name) {
		if (name == null) {
			return null;
		}
		for (BuiltinSkill skill : Holder.SKILLS) {
			if (skill.name().equals(name)) {
				return skill;
			}
		}
		return null;
	}

}
