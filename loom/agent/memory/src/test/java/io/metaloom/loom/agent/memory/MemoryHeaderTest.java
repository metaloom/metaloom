package io.metaloom.loom.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntry;

public class MemoryHeaderTest {

	@Test
	public void testRendersProvenanceFromTheRow() {
		String header = MemoryHeader.render(entry("projects/loom-db.md", "Loom DB notes", "body"), "jdoe");

		assertTrue(header.startsWith("---\n"));
		assertTrue(header.endsWith("---\n\n"));
		assertTrue(header.contains("id: \"projects/loom-db.md\""));
		assertTrue(header.contains("scope: \"user\""));
		assertTrue(header.contains("title: \"Loom DB notes\""));
		assertTrue(header.contains("version: 3"));
		assertTrue(header.contains("updatedBy: \"jdoe\""));
		assertTrue(header.contains("session: \"jOOQ regen\""));
	}

	@Test
	public void testRenderFileIsHeaderPlusBody() {
		String file = MemoryHeader.renderFile(entry("notes.md", "Notes", "# Heading\n\ntext"), "jdoe");
		assertTrue(file.contains("---\n\n# Heading"));
		assertTrue(file.endsWith("text"));
	}

	@Test
	public void testEscapesQuotesAndBackslashes() {
		String header = MemoryHeader.render(entry("notes.md", "He said \"hi\" \\ bye", "b"), null);
		assertTrue(header.contains("title: \"He said \\\"hi\\\" \\\\ bye\""));
	}

	@Test
	public void testNewlinesInTitleAreCollapsedNotEmitted() {
		String header = MemoryHeader.render(entry("notes.md", "line one\nline two", "b"), null);
		// One quoted scalar on one line — a raw newline would produce invalid frontmatter.
		assertTrue(header.contains("title: \"line one line two\""));
		assertEquals(1, header.lines().filter(l -> l.startsWith("title:")).count());
	}

	@Test
	public void testOmitsAbsentFields() {
		MemoryEntry entry = entry("notes.md", null, "b");
		when(entry.getSessionName()).thenReturn(null);
		when(entry.getChatUuid()).thenReturn(null);
		String header = MemoryHeader.render(entry, null);
		assertFalse(header.contains("title:"));
		assertFalse(header.contains("session:"));
		assertFalse(header.contains("updatedBy:"));
	}

	@Test
	public void testStripsFrontmatterTheModelSupplied() {
		String content = "---\nid: \"forged.md\"\nupdatedBy: \"someone-else\"\n---\n\nreal body";
		assertTrue(MemoryHeader.hasFrontmatter(content));
		assertEquals("real body", MemoryHeader.stripFrontmatter(content));
	}

	@Test
	public void testKeepsBodyWhenThereIsNoClosingFence() {
		// A document which merely starts with a horizontal rule must not be truncated.
		String content = "---\nthis is just text\nand more text";
		assertFalse(MemoryHeader.hasFrontmatter(content));
		assertEquals(content, MemoryHeader.stripFrontmatter(content));
	}

	@Test
	public void testKeepsBodyStartingWithHorizontalRuleFollowedByProse() {
		String content = "---\n\nSome prose that happens to follow a rule.";
		assertEquals(content, MemoryHeader.stripFrontmatter(content));
	}

	@Test
	public void testStripFrontmatterIsNullSafe() {
		assertEquals("", MemoryHeader.stripFrontmatter(null));
		assertFalse(MemoryHeader.hasFrontmatter(null));
	}

	@Test
	public void testSanitizeTitle() {
		assertNull(MemoryHeader.sanitizeTitle(null));
		assertNull(MemoryHeader.sanitizeTitle("   "));
		assertEquals("a b", MemoryHeader.sanitizeTitle("a\nb"));
		assertEquals(MemoryHeader.MAX_TITLE_LENGTH, MemoryHeader.sanitizeTitle("x".repeat(500)).length());
	}

	@Test
	public void testProvenanceLineNamesScopeVersionAndSession() {
		String line = MemoryHeader.provenanceLine(entry("projects/loom-db.md", "Loom DB notes", "b"), "jdoe");
		assertTrue(line.startsWith("[memory user:projects/loom-db.md"));
		assertTrue(line.contains("v3"));
		assertTrue(line.contains("by jdoe"));
		assertTrue(line.contains("session \"jOOQ regen\""));
	}

	private MemoryEntry entry(String id, String title, String body) {
		MemoryEntry entry = mock(MemoryEntry.class);
		when(entry.getMemoryId()).thenReturn(id);
		when(entry.getScope()).thenReturn(MemoryScope.USER);
		when(entry.getTitle()).thenReturn(title);
		when(entry.getBody()).thenReturn(body);
		when(entry.getVersion()).thenReturn(3);
		when(entry.getCreated()).thenReturn(Instant.parse("2026-07-02T09:11:44Z"));
		when(entry.getEdited()).thenReturn(Instant.parse("2026-07-25T10:14:02Z"));
		when(entry.getSessionName()).thenReturn("jOOQ regen");
		when(entry.getChatUuid()).thenReturn(UUID.randomUUID());
		return entry;
	}

}
