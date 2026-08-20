package io.metaloom.loom.common.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the packaging of the built-in skills.
 *
 * <p>
 * A built-in skill is a classpath resource, which means a rename or a missing {@code resources} entry breaks it silently at runtime — the agent simply
 * answers without the guidance, which looks like a model that chose not to load it. This test turns that into a build failure.
 * </p>
 */
public class BuiltinSkillsTest {

	@Test
	public void testSkillsLoad() {
		assertFalse(BuiltinSkills.list().isEmpty(), "At least the pipeline authoring skill must ship with Loom");
		for (BuiltinSkill skill : BuiltinSkills.list()) {
			assertNotNull(skill.name());
			assertFalse(skill.name().isBlank(), "A skill name is what load_skill matches on");
			assertFalse(skill.description().isBlank(), "The description is all the model sees before loading the body");
			assertFalse(skill.content().isBlank(), "The body is the whole point");
		}
	}

	@Test
	public void testPipelineAuthoringContent() {
		BuiltinSkill skill = BuiltinSkills.byName(BuiltinSkills.PIPELINE_AUTHORING);
		assertNotNull(skill);
		// The tool names are the contract between the guide and the MCP server; a rename on either
		// side leaves the agent following instructions for tools that no longer exist.
		assertTrue(skill.content().contains("list_node_descriptors"));
		assertTrue(skill.content().contains("get_node_descriptor"));
		assertTrue(skill.content().contains("validate_pipeline"));
		assertTrue(skill.content().contains("create_pipeline"));
		assertTrue(skill.content().contains("update_pipeline"));
		assertTrue(skill.content().contains("sourcePort"));
		assertTrue(skill.content().contains("targetPort"));
	}

	@Test
	public void testAssetSearchContent() {
		BuiltinSkill skill = BuiltinSkills.byName(BuiltinSkills.ASSET_SEARCH);
		assertNotNull(skill);
		// The tool name is the contract between the guide and the MCP server. A rename on either side
		// leaves the agent following instructions for a tool that no longer exists.
		assertTrue(skill.content().contains("find_assets"));
		// The field names have to match the tool's schema, or the guide teaches a call that is refused.
		for (String field : new String[] { "creator", "collection", "library", "space", "tags", "when", "mimeType", "text" }) {
			assertTrue(skill.content().contains(field), "The guide should name the " + field + " field");
		}
		// The two rules the whole design rests on: filters alone are a query, and a filter that finds
		// nothing is never widened to produce results.
		assertTrue(skill.content().contains("no search term"));
		assertTrue(skill.content().contains("Never widen a filter"));
	}

	@Test
	public void testSkillNamesAreUnique() {
		// load_skill matches on the name; two skills sharing one would make which body loads arbitrary.
		long distinct = BuiltinSkills.list().stream().map(BuiltinSkill::name).distinct().count();
		assertTrue(distinct == BuiltinSkills.list().size(), "Built-in skill names must be unique");
	}

	@Test
	public void testByNameUnknown() {
		assertNull(BuiltinSkills.byName("no-such-skill"));
		assertNull(BuiltinSkills.byName(null));
	}

	@Test
	public void testContentIsCached() {
		assertSame(BuiltinSkills.list(), BuiltinSkills.list());
	}

}
