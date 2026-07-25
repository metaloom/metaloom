package io.metaloom.loom.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The id becomes a real filesystem path inside the session container, so everything that is not obviously a safe relative markdown path must be rejected
 * here rather than relied upon to be caught later.
 */
public class MemoryIdTest {

	private static final int MAX_DEPTH = 4;

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
		"   ",
		"..",
		"../x.md",
		"a/../../b.md",
		"a/./b.md",
		"/etc/passwd",
		"/notes.md",
		"notes.txt",
		"notes",
		".md",
		"a//b.md",
		"a\\b.md",
		"a:b.md",
		"~/notes.md",
		"note*.md",
		"note?.md",
		"note\"s.md",
		"no<te>.md",
		"pipe|d.md",
		"deep/er/still/way/too.md",
		"naïve.md",
		"note .md",
		"-leading.md",
		"trailing/",
		"_underscore.md"
	})
	public void testRejectedIds(String id) {
		assertThrows(MemoryException.class, () -> MemoryId.parse(id, MAX_DEPTH));
	}

	@Test
	public void testRejectsOverlongId() {
		String id = "a".repeat(MemoryId.MAX_LENGTH) + ".md";
		assertThrows(MemoryException.class, () -> MemoryId.parse(id, MAX_DEPTH));
	}

	@Test
	public void testRejectsControlCharacters() {
		assertThrows(MemoryException.class, () -> MemoryId.parse("no\ttab.md", MAX_DEPTH));
		assertThrows(MemoryException.class, () -> MemoryId.parse("no\nnewline.md", MAX_DEPTH));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"notes.md",
		"memory.md",
		"projects/loom-db.md",
		"a/b/c/d.md",
		"with_underscore.md",
		"with-dash.md",
		"with.dots.md",
		"9lives.md"
	})
	public void testAcceptedIds(String id) {
		assertEquals(id, MemoryId.parse(id, MAX_DEPTH));
	}

	@Test
	public void testNormalizesCaseAndLeadingDotSlash() {
		assertEquals("projects/loom-db.md", MemoryId.parse("Projects/Loom-DB.MD", MAX_DEPTH));
		assertEquals("notes.md", MemoryId.parse("./notes.md", MAX_DEPTH));
		assertEquals("notes.md", MemoryId.parse("  notes.md  ", MAX_DEPTH));
	}

	@Test
	public void testDepthLimitIsConfigurable() {
		assertEquals("a/b.md", MemoryId.parse("a/b.md", 2));
		assertThrows(MemoryException.class, () -> MemoryId.parse("a/b/c.md", 2));
	}

	@Test
	public void testDefaultTitle() {
		assertEquals("loom-db", MemoryId.defaultTitle("projects/loom-db.md"));
		assertEquals("notes", MemoryId.defaultTitle("notes.md"));
	}

}
