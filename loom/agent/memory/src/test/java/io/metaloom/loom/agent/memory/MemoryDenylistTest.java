package io.metaloom.loom.agent.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;

public class MemoryDenylistTest {

	private MemoryDenyRuleDao dao;
	private MemoryDenylist denylist;

	@BeforeEach
	public void setup() {
		dao = mock(MemoryDenyRuleDao.class);
		when(dao.loadEnabled()).thenReturn(List.of());
		DaoCollection daos = mock(DaoCollection.class);
		when(daos.memoryDenyRuleDao()).thenReturn(dao);
		denylist = new MemoryDenylist(daos);
	}

	// -- matching ------------------------------------------------------------

	@Test
	public void testEmptyListAllowsEverything() {
		assertDoesNotThrow(() -> denylist.check("title", "AKIAIOSFODNN7EXAMPLE"));
	}

	@Test
	public void testAwsAccessKeyIsRejected() {
		rules(rule("aws", "AKIA[0-9A-Z]{16}", "Never store credentials in memory."));
		MemoryException e = assertThrows(MemoryException.class, () -> denylist.check(null, "key: AKIAIOSFODNN7EXAMPLE"));
		assertEquals("Never store credentials in memory.", e.getMessage());
	}

	@Test
	public void testAwsKeyShapeIsNotOverEager() {
		rules(rule("aws", "AKIA[0-9A-Z]{16}", "denied"));
		// Too short, and lowercase — neither is an access key id.
		assertDoesNotThrow(() -> denylist.check(null, "AKIASHORT"));
		assertDoesNotThrow(() -> denylist.check(null, "akiaiosfodnn7example"));
	}

	@Test
	public void testAlternationCoversSeveralPhrasesInOneRule() {
		rules(rule("codenames", "(?i)\\b(project bluebird|operation nightfall|codename raven)\\b", "denied"));
		for (String hit : List.of("Project Bluebird", "operation nightfall", "CODENAME RAVEN")) {
			assertThrows(MemoryException.class, () -> denylist.check(null, "notes about " + hit), hit);
		}
		assertDoesNotThrow(() -> denylist.check(null, "bluebirds and ravens"));
	}

	@Test
	public void testTitleIsCheckedAsWellAsBody() {
		rules(rule("aws", "AKIA[0-9A-Z]{16}", "denied"));
		assertThrows(MemoryException.class, () -> denylist.check("AKIAIOSFODNN7EXAMPLE", "clean"));
	}

	@Test
	public void testFirstMatchingRuleWins() {
		rules(
			rule("a-first", "alpha", "message from the first rule"),
			rule("b-second", "beta", "message from the second rule"));
		assertEquals("message from the first rule",
			assertThrows(MemoryException.class, () -> denylist.check(null, "alpha and beta")).getMessage());
	}

	@Test
	public void testNullAndEmptyInputsAreSafe() {
		rules(rule("aws", "AKIA[0-9A-Z]{16}", "denied"));
		assertDoesNotThrow(() -> denylist.check(null, null));
		assertDoesNotThrow(() -> denylist.check("", ""));
	}

	@Test
	public void testInvalidPatternIsIgnoredRatherThanBlockingWrites() {
		rules(rule("broken", "([unclosed", "denied"));
		assertDoesNotThrow(() -> denylist.check(null, "anything"));
	}

	@Test
	public void testLookupFailureFailsOpen() {
		when(dao.loadEnabled()).thenThrow(new RuntimeException("db down"));
		// The denylist is a safety net, not an authorization gate — it must not take the feature down with it.
		assertDoesNotThrow(() -> denylist.check(null, "anything"));
	}

	// -- catastrophic backtracking -------------------------------------------

	@Test
	public void testPathologicalPatternIsBoundedRatherThanHangingTheThread() {
		// (a+)+$ against a long non-matching run backtracks exponentially; without the step budget
		// this call would not return in any practical time.
		rules(rule("evil", "(a+)+$", "denied"));
		String body = "a".repeat(40) + "!";

		assertTimeoutPreemptively(Duration.ofSeconds(10),
			() -> assertDoesNotThrow(() -> denylist.check(null, body),
				"A rule that exceeds its budget is skipped, so the write proceeds"));
	}

	// -- pattern validation --------------------------------------------------

	@Test
	public void testValidatePatternRejectsEmptyAndOverlong() {
		assertThrows(MemoryException.class, () -> MemoryDenylist.validatePattern(null));
		assertThrows(MemoryException.class, () -> MemoryDenylist.validatePattern("  "));
		assertThrows(MemoryException.class, () -> MemoryDenylist.validatePattern("x".repeat(MemoryDenylist.MAX_PATTERN_LENGTH + 1)));
	}

	@Test
	public void testValidatePatternRejectsBrokenRegexWithADescription() {
		MemoryException e = assertThrows(MemoryException.class, () -> MemoryDenylist.validatePattern("([unclosed"));
		assertTrue(e.getMessage().startsWith("The pattern is not a valid regular expression"));
	}

	@Test
	public void testValidatePatternAcceptsRealRules() {
		assertDoesNotThrow(() -> MemoryDenylist.validatePattern("AKIA[0-9A-Z]{16}"));
		assertDoesNotThrow(() -> MemoryDenylist.validatePattern("(?i)\\b(one|two)\\b"));
	}

	private void rules(MemoryDenyRule... rules) {
		when(dao.loadEnabled()).thenReturn(List.of(rules));
	}

	private MemoryDenyRule rule(String name, String pattern, String message) {
		MemoryDenyRule rule = mock(MemoryDenyRule.class);
		when(rule.getName()).thenReturn(name);
		when(rule.getPattern()).thenReturn(pattern);
		when(rule.getMessage()).thenReturn(message);
		return rule;
	}

}
