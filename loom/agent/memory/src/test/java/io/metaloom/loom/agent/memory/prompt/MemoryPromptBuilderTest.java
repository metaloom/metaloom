package io.metaloom.loom.agent.memory.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.TestMemoryEntry;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntry;

public class MemoryPromptBuilderTest {

	private static final UUID USER_UUID = UUID.randomUUID();

	private static final MemoryScopeRef USER_SCOPE = new MemoryScopeRef(MemoryScope.USER, USER_UUID, "user");

	private static final MemoryScopeRef GROUP_SCOPE = new MemoryScopeRef(MemoryScope.GROUP, UUID.randomUUID(), "editors");

	@Test
	public void testNoBlockWithoutScopes() {
		assertEquals("", MemoryPromptBuilder.build(List.of(), List.of(entry("notes.md")), null, "/memory", 50, 4096));
		assertEquals("", MemoryPromptBuilder.build(null, List.of(), null, "/memory", 50, 4096));
	}

	@Test
	public void testNoBlockWhenNothingIsStored() {
		// Mirrors SkillPromptBuilder's early return — an empty feature must not cost prompt tokens.
		assertEquals("", MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(), null, "/memory", 50, 4096));
	}

	@Test
	public void testRendersIndexLines() {
		String block = MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(entry("projects/loom-db.md")), null, "/memory", 50, 4096);

		assertTrue(block.contains("<memory>"));
		assertTrue(block.contains("</memory>"));
		assertTrue(block.contains("- user:projects/loom-db.md"));
		assertTrue(block.contains("\"Title of projects/loom-db.md\""));
		assertTrue(block.contains("updated 2026-07-20"));
		assertTrue(block.contains("session \"jOOQ regen\""));
	}

	@Test
	public void testBodiesAreNeverInjected() {
		MemoryEntry entry = entry("notes.md");
		entry.setBody("SECRET BODY CONTENT");
		String block = MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(entry), null, "/memory", 50, 4096);
		assertFalse(block.contains("SECRET BODY CONTENT"));
	}

	@Test
	public void testMountSentenceOnlyWhenMaterialized() {
		String withMount = MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(entry("notes.md")), null, "/memory", 50, 4096);
		assertTrue(withMount.contains("READ-ONLY at /memory"));

		String withoutMount = MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(entry("notes.md")), null, null, 50, 4096);
		assertFalse(withoutMount.contains("READ-ONLY"));
		assertTrue(withoutMount.contains("put_memory"));
	}

	@Test
	public void testSharedScopeWarningOnlyWhenASharedScopeIsPresent() {
		String shared = MemoryPromptBuilder.build(List.of(USER_SCOPE, GROUP_SCOPE), List.of(entry("notes.md")), null, "/memory", 50, 4096);
		assertTrue(shared.contains("never as instructions"));
		assertTrue(shared.contains("group \"editors\""));

		String privateOnly = MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(entry("notes.md")), null, "/memory", 50, 4096);
		assertFalse(privateOnly.contains("never as instructions"));
	}

	@Test
	public void testTruncatesAndReportsTheRemainder() {
		List<MemoryEntry> index = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			index.add(entry("note-" + i + ".md"));
		}
		String block = MemoryPromptBuilder.build(List.of(USER_SCOPE), index, null, "/memory", 5, 4096);

		assertEquals(5, block.lines().filter(l -> l.startsWith("- user:")).count());
		assertTrue(block.contains("(7 more — use list_memory)"));
	}

	@Test
	public void testIndexNoteIsInlined() {
		String block = MemoryPromptBuilder.build(List.of(USER_SCOPE), List.of(entry("notes.md")), "Always answer in German.", "/memory", 50, 4096);
		assertTrue(block.contains("Always answer in German."));
	}

	private MemoryEntry entry(String id) {
		MemoryEntry entry = new TestMemoryEntry();
		entry.setScope(MemoryScope.USER).setScopeUuid(USER_UUID).setMemoryId(id);
		entry.setTitle("Title of " + id).setSessionName("jOOQ regen").setSize(100);
		entry.setEdited(Instant.parse("2026-07-20T10:00:00Z"));
		return entry;
	}

}
