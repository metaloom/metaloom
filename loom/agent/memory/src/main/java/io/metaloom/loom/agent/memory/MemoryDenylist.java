package io.metaloom.loom.agent.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;

/**
 * Applies the instance-wide memory denylist to a write.
 *
 * <p>Rules are admin-curated regular expressions; a match rejects the write with the rule's own message. The message is used verbatim and the matched text
 * is never echoed — an agent that has just been stopped from storing a secret must not paste it into the chat transcript instead.</p>
 */
@Singleton
public class MemoryDenylist {

	private static final Logger log = LoggerFactory.getLogger(MemoryDenylist.class);

	/**
	 * Upper bound on the character reads one rule may perform against one note.
	 *
	 * <p>A pathological pattern (nested quantifiers over alternation) can backtrack exponentially. The patterns come from administrators rather than from
	 * the agent, so this is not an attacker-controlled input, but a mistyped rule would otherwise wedge a worker thread on every write. Exceeding the
	 * budget aborts that one rule and lets the write through — failing open on an admin typo is better than blocking every write in the instance.</p>
	 */
	static final int MATCH_STEP_BUDGET = 2_000_000;

	/** Longest accepted pattern. Keeps a pasted blob out of the regex compiler. */
	public static final int MAX_PATTERN_LENGTH = 512;

	private final DaoCollection daos;

	/** Compiled patterns, keyed by the pattern text so an edit naturally produces a new entry. */
	private final Map<String, Pattern> compiled = new ConcurrentHashMap<>();

	@Inject
	public MemoryDenylist(DaoCollection daos) {
		this.daos = daos;
	}

	/**
	 * Reject the write when any enabled rule matches the note.
	 *
	 * <p>Both the body and the title are checked: a title is stored, rendered into the materialized file and shown in listings, so it is just as capable of
	 * carrying a secret as the body.</p>
	 *
	 * @throws MemoryException
	 *             carrying the matching rule's message
	 */
	public void check(String title, String body) {
		List<MemoryDenyRule> rules;
		try {
			rules = daos.memoryDenyRuleDao().loadEnabled();
		} catch (Exception e) {
			// The denylist is a safety net, not an authorization gate; a lookup failure must not block every write.
			log.warn("Could not load the memory denylist — writes proceed unchecked for this call", e);
			return;
		}
		for (MemoryDenyRule rule : rules) {
			if (matches(rule, body) || matches(rule, title)) {
				log.info("memory write rejected by deny rule '{}'", rule.getName());
				throw new MemoryException(rule.getMessage());
			}
		}
	}

	/**
	 * Validate a pattern before it is stored, so a broken rule is rejected at the admin API rather than at write time.
	 *
	 * @throws MemoryException
	 *             when the pattern is empty, too long, or not a valid regular expression
	 */
	public static void validatePattern(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			throw new MemoryException("A deny rule needs a pattern.");
		}
		if (pattern.length() > MAX_PATTERN_LENGTH) {
			throw new MemoryException("The pattern must not be longer than " + MAX_PATTERN_LENGTH + " characters.");
		}
		try {
			Pattern.compile(pattern);
		} catch (PatternSyntaxException e) {
			throw new MemoryException("The pattern is not a valid regular expression: " + e.getDescription());
		}
	}

	private boolean matches(MemoryDenyRule rule, String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		Pattern pattern = compiled.computeIfAbsent(rule.getPattern(), p -> {
			try {
				return Pattern.compile(p);
			} catch (PatternSyntaxException e) {
				log.warn("Deny rule '{}' has an invalid pattern and is ignored: {}", rule.getName(), e.getDescription());
				return null;
			}
		});
		if (pattern == null) {
			return false;
		}
		try {
			Matcher matcher = pattern.matcher(new BoundedCharSequence(text, MATCH_STEP_BUDGET));
			return matcher.find();
		} catch (BudgetExceededException e) {
			log.warn("Deny rule '{}' exceeded its matching budget and was skipped for this note — review the pattern", rule.getName());
			return false;
		}
	}

	/**
	 * A {@link CharSequence} that counts reads and aborts once the budget is spent.
	 *
	 * <p>{@code java.util.regex} has no timeout, and every backtracking step reads a character, so counting {@code charAt} is the standard way to bound a
	 * match without a watchdog thread.</p>
	 */
	static final class BoundedCharSequence implements CharSequence {

		private final CharSequence delegate;
		private final int[] budget;

		BoundedCharSequence(CharSequence delegate, int budget) {
			this(delegate, new int[] { budget });
		}

		private BoundedCharSequence(CharSequence delegate, int[] budget) {
			this.delegate = delegate;
			this.budget = budget;
		}

		@Override
		public int length() {
			return delegate.length();
		}

		@Override
		public char charAt(int index) {
			if (--budget[0] < 0) {
				throw new BudgetExceededException();
			}
			return delegate.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			// The remaining budget is shared with the parent, so a subSequence cannot reset it.
			return new BoundedCharSequence(delegate.subSequence(start, end), budget);
		}

		@Override
		public String toString() {
			return delegate.toString();
		}
	}

	/** Raised inside the regex engine when a match burns through its step budget. */
	static final class BudgetExceededException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		BudgetExceededException() {
			super(null, null, false, false);
		}
	}

}
