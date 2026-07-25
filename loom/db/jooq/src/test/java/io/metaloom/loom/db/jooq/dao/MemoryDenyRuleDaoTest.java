package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;
import io.metaloom.loom.db.model.user.User;

public class MemoryDenyRuleDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<MemoryDenyRuleDao, MemoryDenyRule> {

	@Override
	public MemoryDenyRuleDao getDao() {
		return memoryDenyRuleDao();
	}

	@Override
	public MemoryDenyRule createElement(User user, int i) {
		return getDao().createMemoryDenyRule(user.getUuid(), "rule-" + i, "pattern-" + i, "Rejected by rule " + i);
	}

	@Override
	public void assertCreate(MemoryDenyRule created) {
		assertEquals("rule-0", created.getName());
		assertEquals("pattern-0", created.getPattern());
		assertEquals("Rejected by rule 0", created.getMessage());
		assertTrue(created.isEnabled(), "A new rule is enabled by default");
	}

	@Override
	public void updateElement(MemoryDenyRule rule) {
		rule.setName("updated-rule");
		rule.setPattern("AKIA[0-9A-Z]{16}");
		rule.setMessage("Never store credentials in memory.");
		rule.setEnabled(false);
	}

	@Override
	public void assertUpdate(MemoryDenyRule updated) {
		assertEquals("updated-rule", updated.getName());
		assertEquals("AKIA[0-9A-Z]{16}", updated.getPattern());
		assertEquals("Never store credentials in memory.", updated.getMessage());
		assertFalse(updated.isEnabled(), "The enabled flag must round-trip through the DB");
	}

	@Test
	public void testLoadByName() {
		User user = dummyUser();
		store(user, "aws-access-key", "AKIA[0-9A-Z]{16}", "denied", true);

		MemoryDenyRule loaded = getDao().loadByName("aws-access-key");
		assertNotNull(loaded);
		assertEquals("AKIA[0-9A-Z]{16}", loaded.getPattern());
		assertNull(getDao().loadByName("does-not-exist"));
	}

	@Test
	public void testNameIsUnique() {
		User user = dummyUser();
		store(user, "duplicate", "a", "denied", true);

		assertThrows(Exception.class, () -> store(user, "duplicate", "b", "denied", true),
			"The unique name constraint must reject a second rule with the same name");
	}

	@Test
	public void testLoadEnabledSkipsDisabledRules() {
		User user = dummyUser();
		store(user, "active", "a", "denied", true);
		store(user, "retired", "b", "denied", false);

		List<MemoryDenyRule> enabled = getDao().loadEnabled();
		assertEquals(1, enabled.size());
		assertEquals("active", enabled.get(0).getName());
	}

	@Test
	public void testLoadEnabledIsOrderedByNameSoRejectionIsDeterministic() {
		User user = dummyUser();
		store(user, "c-third", "c", "third", true);
		store(user, "a-first", "a", "first", true);
		store(user, "b-second", "b", "second", true);

		List<MemoryDenyRule> enabled = getDao().loadEnabled();
		assertEquals(List.of("a-first", "b-second", "c-third"), enabled.stream().map(MemoryDenyRule::getName).toList());
	}

	@Test
	public void testLoadEnabledIsEmptyWithoutRules() {
		assertTrue(getDao().loadEnabled().isEmpty());
	}

	@Test
	public void testRegexPatternSurvivesTheRoundTripVerbatim() {
		User user = dummyUser();
		// Backslashes, quantifiers and alternation must come back byte-identical — a mangled pattern
		// would silently stop matching.
		String pattern = "(?i)\\b(project bluebird|operation nightfall)\\b";
		store(user, "codenames", pattern, "denied", true);

		assertEquals(pattern, getDao().loadByName("codenames").getPattern());
	}

	private MemoryDenyRule store(User user, String name, String pattern, String message, boolean enabled) {
		MemoryDenyRule rule = getDao().createMemoryDenyRule(user.getUuid(), name, pattern, message);
		rule.setEnabled(enabled);
		getDao().store(rule);
		return rule;
	}

}
