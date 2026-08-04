package io.metaloom.cortex.node.tag;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.vertx.core.json.JsonArray;

/**
 * The comparisons a {@link TagCondition} can make.
 *
 * <p>
 * A closed set on purpose. The expressive alternative — a JavaScript predicate, which this repository
 * already has an engine for in the {@code script} node — cannot be rendered as a form, cannot be
 * checked when the pipeline is saved, and would put a sandbox on the critical path of an operation
 * that is a handful of comparisons. When a rule genuinely needs more than this, the escape hatch is
 * to compute the value in a {@code script} node and wire its output into the {@code number} or
 * {@code flag} port.
 * </p>
 */
public enum TagOp {

	EQ(true),
	NEQ(true),
	GT(true),
	GTE(true),
	LT(true),
	LTE(true),
	CONTAINS(true),
	STARTS_WITH(true),
	/** Java regex, matched anywhere in the subject's string form. */
	MATCHES(true),
	/** The subject equals one of the entries of an array {@code value}. */
	IN(true),
	/** The subject is present at all - a wired port carrying something, a path that resolves. */
	EXISTS(false),
	/** Present, and not an empty or whitespace-only string. */
	NOT_BLANK(false);

	private final boolean needsValue;

	TagOp(boolean needsValue) {
		this.needsValue = needsValue;
	}

	/** Whether a rule using this operator must supply a {@code value}. */
	public boolean needsValue() {
		return needsValue;
	}

	/**
	 * Apply this operator.
	 *
	 * <p>
	 * A null subject is <em>false</em> for every operator except {@link #NEQ}, and never an error: an
	 * unwired optional port is a configuration, not a fault. That is the same choice the filter node
	 * makes for its optional text port — a node that visibly tags nothing is far easier to diagnose
	 * than one that refuses to start.
	 * </p>
	 *
	 * @param subject  the value read off the wired port, possibly null
	 * @param expected the literal from the rule, possibly null
	 */
	public boolean test(Object subject, Object expected) {
		switch (this) {
		case EXISTS:
			return subject != null;
		case NOT_BLANK:
			return subject != null && !String.valueOf(subject).isBlank();
		case NEQ:
			// The negation of EQ, including for an absent subject: "the language is not German" is
			// true of an item that has no language at all.
			return !EQ.test(subject, expected);
		default:
			break;
		}
		if (subject == null) {
			return false;
		}
		switch (this) {
		case EQ:
			return equal(subject, expected);
		case GT:
		case GTE:
		case LT:
		case LTE:
			return compare(subject, expected);
		case CONTAINS:
			return text(subject).contains(text(expected));
		case STARTS_WITH:
			return text(subject).startsWith(text(expected));
		case MATCHES:
			return pattern(expected).matcher(String.valueOf(subject)).find();
		case IN:
			return in(subject, expected);
		default:
			return false;
		}
	}

	/** The reason this operator cannot be used as configured, or null when it is fine. */
	public String validate(Object expected) {
		if (needsValue && expected == null) {
			return name() + " needs a value";
		}
		if (this == MATCHES) {
			try {
				Pattern.compile(String.valueOf(expected));
			} catch (PatternSyntaxException e) {
				return "'" + expected + "' is not a valid regular expression: " + e.getDescription();
			}
		}
		if (this == IN && !(expected instanceof JsonArray) && !(expected instanceof List)) {
			return "IN needs an array value";
		}
		return null;
	}

	private static boolean equal(Object subject, Object expected) {
		Double a = number(subject);
		Double b = number(expected);
		if (a != null && b != null) {
			// 3 and 3.0 are the same threshold; a JSON document does not distinguish them reliably.
			return a.doubleValue() == b.doubleValue();
		}
		if (subject instanceof Boolean || expected instanceof Boolean) {
			return String.valueOf(subject).equalsIgnoreCase(String.valueOf(expected));
		}
		return String.valueOf(subject).equals(String.valueOf(expected));
	}

	private boolean compare(Object subject, Object expected) {
		Double a = number(subject);
		Double b = number(expected);
		int cmp;
		if (a != null && b != null) {
			cmp = Double.compare(a, b);
		} else {
			// Ordering non-numbers is still useful - dates and versions as ISO strings sort correctly.
			cmp = String.valueOf(subject).compareTo(String.valueOf(expected));
		}
		return switch (this) {
		case GT -> cmp > 0;
		case GTE -> cmp >= 0;
		case LT -> cmp < 0;
		case LTE -> cmp <= 0;
		default -> false;
		};
	}

	private static boolean in(Object subject, Object expected) {
		Iterable<?> values;
		if (expected instanceof JsonArray array) {
			values = array;
		} else if (expected instanceof Iterable<?> iterable) {
			values = iterable;
		} else {
			return false;
		}
		for (Object candidate : values) {
			if (equal(subject, candidate)) {
				return true;
			}
		}
		return false;
	}

	/** Lower-cased string form, so CONTAINS and STARTS_WITH are case-insensitive like a person expects. */
	private static String text(Object value) {
		return String.valueOf(value).toLowerCase(Locale.ROOT);
	}

	/**
	 * Compiled patterns, keyed by the expression.
	 *
	 * <p>
	 * A rule is evaluated once per item, so compiling the same expression for every asset in a library
	 * would dominate the cost of a node that otherwise does a handful of comparisons. The map is bounded
	 * by the number of distinct expressions an author writes.
	 * </p>
	 */
	private static final java.util.Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

	private static Pattern pattern(Object expected) {
		return PATTERNS.computeIfAbsent(String.valueOf(expected), Pattern::compile);
	}

	/** The value as a double, or null when it is not a number. Strings that look like numbers count. */
	private static Double number(Object value) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		if (value instanceof String s) {
			try {
				return Double.valueOf(s.trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}
}
