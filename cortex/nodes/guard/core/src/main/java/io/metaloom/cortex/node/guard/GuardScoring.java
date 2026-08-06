package io.metaloom.cortex.node.guard;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a backend answer into P(unsafe).
 *
 * <p>
 * Every family reduces to the same two-token decision — {@code unsafe} vs {@code safe} for Llama
 * Guard, {@code Yes} vs {@code No} for ShieldGemma and Granite Guardian — so one routine covers all
 * three. It renormalises over just those two tokens, which is what Google's model card describes for
 * ShieldGemma ("extract the logits for the Yes and No tokens ... convert to a probability with
 * softmax") and what IBM's cookbook does for Granite Guardian. Renormalising matters: the raw
 * probability of {@code Yes} is depressed by whatever mass the model puts on unrelated tokens, so
 * reading it directly makes every model look systematically safer than it is.
 * </p>
 *
 * <p>
 * When the backend reports no probabilities at all the score falls back to the argmax of the
 * generated text — 1.0 or 0.0 — and is flagged {@code exact = false}, which travels all the way into
 * the stored payload. A fabricated 0.87 would be worse than an honest 1.0: a threshold tuned against
 * it would be meaningless.
 * </p>
 */
public final class GuardScoring {

	/**
	 * How many generated positions are searched for the decision token before giving up. Three
	 * covers a leading newline and a {@code <score>} wrapper without letting the search wander into
	 * an explanation that happens to contain the word "safe".
	 */
	private static final int MAX_DECISION_POSITION = 3;

	/** The first run of letters in the generated text — see {@link #argmaxFromText}. */
	private static final Pattern FIRST_WORD = Pattern.compile("[A-Za-z]+");

	/** A wrapper tag such as Granite Guardian's {@code <score>}, removed before the first word is read. */
	private static final Pattern TAG = Pattern.compile("<[^>]*>");

	/**
	 * The scored decision.
	 *
	 * @param value P(unsafe) in {@code [0,1]}
	 * @param exact false when {@code value} is the argmax fallback rather than a real probability
	 */
	public record Score(double value, boolean exact) {
	}

	private GuardScoring() {
	}

	/**
	 * Score one completion.
	 *
	 * @param completion    the backend answer
	 * @param unsafeTokens  the token(s) that mean "this is unsafe", most specific first
	 * @param safeTokens    the token(s) that mean "this is safe"
	 * @return the score, never null
	 */
	public static Score score(GuardCompletion completion, List<String> unsafeTokens, List<String> safeTokens) {
		for (Map<String, Double> position : completion.tokenProbs().stream().limit(MAX_DECISION_POSITION).toList()) {
			Double unsafe = bestMatch(position, unsafeTokens);
			Double safe = bestMatch(position, safeTokens);
			if (unsafe == null && safe == null) {
				// Not the decision position - a leading newline or a wrapper tag. Keep looking.
				continue;
			}
			if (unsafe != null && safe != null) {
				double total = unsafe + safe;
				return new Score(total <= 0d ? 0d : unsafe / total, true);
			}
			// Only one side made the top-N list, which happens when the model is very confident.
			// The other side's mass is then at most (1 - this one), so this is a lower bound on the
			// renormalised value rather than a guess.
			return unsafe != null ? new Score(unsafe, true) : new Score(1d - safe, true);
		}
		return new Score(argmaxFromText(completion.text(), unsafeTokens) ? 1d : 0d, false);
	}

	/**
	 * The highest probability among the tokens in this position that match one of the candidates.
	 *
	 * <p>
	 * Matching is on the stripped token, case-insensitively and by equality — never by
	 * {@code contains}, because {@code "unsafe"} contains {@code "safe"} and a substring match would
	 * score every unsafe verdict as safe.
	 * </p>
	 */
	private static Double bestMatch(Map<String, Double> position, List<String> candidates) {
		Double best = null;
		for (Map.Entry<String, Double> entry : position.entrySet()) {
			String token = entry.getKey() == null ? "" : entry.getKey().strip();
			for (String candidate : candidates) {
				if (token.equalsIgnoreCase(candidate) && (best == null || entry.getValue() > best)) {
					best = entry.getValue();
				}
			}
		}
		return best;
	}

	/**
	 * The fallback: is the first word of the generated text an unsafe token?
	 *
	 * <p>
	 * The <em>first</em> word, matched by equality, for the same reason the token match is:
	 * {@code "unsafe\nS1"} and {@code "safe"} differ by a prefix, so anything looser gets the two
	 * answers backwards. Wrapper tags are stripped before the first word is taken, because Granite
	 * Guardian's thinking mode answers {@code <score>yes</score>} and the first run of letters in
	 * that is {@code score}.
	 * </p>
	 */
	private static boolean argmaxFromText(String text, List<String> unsafeTokens) {
		if (text == null) {
			return false;
		}
		Matcher matcher = FIRST_WORD.matcher(TAG.matcher(text).replaceAll(" "));
		if (!matcher.find()) {
			return false;
		}
		String firstWord = matcher.group();
		return unsafeTokens.stream().anyMatch(firstWord::equalsIgnoreCase);
	}
}
