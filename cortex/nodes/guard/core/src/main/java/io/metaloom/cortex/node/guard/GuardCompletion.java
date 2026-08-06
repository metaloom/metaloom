package io.metaloom.cortex.node.guard;

import java.util.List;
import java.util.Map;

/**
 * One backend answer: the generated text, plus the per-position top token probabilities when the
 * backend was willing to report them.
 *
 * <p>
 * The probabilities are a <em>list</em> of positions rather than just the first one on purpose. All
 * three families document their score as "the probability of the decision token", but where that
 * token sits varies: a leading newline, a {@code <score>} wrapper, or a tokenizer that splits
 * {@code unsafe} differently all move it. Keeping the first few positions lets {@link GuardScoring}
 * find the decision wherever the model actually put it, instead of reading position 0 and quietly
 * scoring a line break.
 * </p>
 *
 * <p>
 * Values are probabilities in {@code [0,1]}, already exponentiated from the log probabilities the
 * wire format carries. {@code tokenProbs} is empty when the backend ignored the request — see
 * {@link GuardScoring} for what happens then.
 * </p>
 *
 * @param text       the generated text, stripped
 * @param tokenProbs token → probability, one map per generated position, in generation order
 */
public record GuardCompletion(String text, List<Map<String, Double>> tokenProbs) {

	/**
	 * An answer with no probabilities — what a backend that does not implement {@code logprobs}
	 * returns, and what the tests use when they only care about the text.
	 *
	 * @param text the generated text
	 * @return the completion
	 */
	public static GuardCompletion textOnly(String text) {
		return new GuardCompletion(text, List.of());
	}
}
