package io.metaloom.cortex.node.guard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The normalised answer — the whole point of putting three model families behind one node.
 *
 * <p>
 * Every family is folded into this shape, and this shape is both the {@code result} port value and
 * the {@code asset_json_comp} payload. Two properties make it comparable across families:
 * </p>
 * <ul>
 * <li>{@code score} is always <strong>P(unsafe)</strong> in {@code [0,1]}, never a raw logit and
 * never a family-specific scale. For Llama Guard that is the probability the first generated token
 * is {@code unsafe}; for ShieldGemma and Granite Guardian it is the softmax over the {@code Yes} and
 * {@code No} token probabilities. All three are documented by their authors as <em>the</em>
 * classifier score, so this is a normalisation, not an invention.</li>
 * <li>{@code categories} carries the canonical bucket <em>and</em> the family's own code, so nothing
 * is lost in translation and a consumer that wants {@code S6} can still have it.</li>
 * </ul>
 *
 * <p>
 * One asymmetry is reported rather than hidden. Llama Guard answers with a single probability for
 * the whole verdict, so every category it names carries the same score; ShieldGemma and Granite are
 * probed once per policy, so their per-category scores are genuinely independent. {@code probes}
 * tells the reader which of the two they are looking at.
 * </p>
 */
public record GuardVerdict(
	boolean safe,
	double score,
	double threshold,
	GuardFamily family,
	String model,
	String subject,
	List<Hit> categories,
	String raw,
	int probes,
	boolean scoreExact,
	Integer textChars) {

	/** Subject value for a verdict about upstream text. */
	public static final String SUBJECT_TEXT = "text";

	/** Subject value for a verdict about the asset's pixels. */
	public static final String SUBJECT_IMAGE = "image";

	/**
	 * One harm category the model flagged.
	 *
	 * @param canonical the shared vocabulary bucket
	 * @param nativeCode the family's own identifier, e.g. {@code S12} or {@code hate_speech}
	 * @param label the family's own name for it
	 * @param score P(unsafe) for this category
	 */
	public record Hit(GuardCategory canonical, String nativeCode, String label, double score) {
	}

	/**
	 * Fold every probe of one run into a verdict.
	 *
	 * <p>
	 * The overall score is the <em>maximum</em> over the probes, not the mean: an item that is
	 * emphatically safe on three policies and emphatically unsafe on the fourth is unsafe, and an
	 * average would bury exactly the signal the node exists to surface.
	 * </p>
	 *
	 * @param results   every probe's result, in the order they were issued
	 * @param options   the configuration the run used
	 * @param subject   {@link #SUBJECT_TEXT} or {@link #SUBJECT_IMAGE}
	 * @param textChars how much text was seen, or null for an image verdict
	 * @return the verdict
	 */
	public static GuardVerdict of(List<GuardProbeResult> results, GuardNodeOptions options, String subject, Integer textChars) {
		double threshold = options.getThreshold();
		double score = results.stream().mapToDouble(GuardProbeResult::score).max().orElse(0d);
		boolean scoreExact = results.stream().allMatch(GuardProbeResult::scoreExact);
		String raw = results.stream().map(GuardProbeResult::raw).collect(Collectors.joining("\n"));

		// Only the hits at or above the threshold are reported as categories - the rest are the
		// probes that came back clean, and listing them would make every verdict look alarming.
		List<Hit> flagged = new ArrayList<>(results.stream()
			.flatMap(result -> result.hits().stream())
			.filter(hit -> hit.score() >= threshold)
			.toList());
		flagged.sort(Comparator.comparingDouble(Hit::score).reversed());

		return new GuardVerdict(score < threshold, score, threshold, options.getFamily(), options.getModel(), subject,
			List.copyOf(flagged), raw, results.size(), scoreExact, textChars);
	}

	/** {@code "safe"} or {@code "unsafe"} — the {@code label} port value. */
	public String label() {
		return safe ? "safe" : "unsafe";
	}

	/** The canonical category names of every flagged hit, for the {@code categories} MANY port. */
	public List<String> canonicalCodes() {
		return categories.stream().map(hit -> hit.canonical().name()).distinct().toList();
	}

	/**
	 * The stored / emitted JSON form.
	 *
	 * @return the payload
	 */
	public JsonObject toJson() {
		JsonArray cats = new JsonArray();
		for (Hit hit : categories) {
			cats.add(new JsonObject()
				.put("canonical", hit.canonical().name())
				.put("native", hit.nativeCode())
				.put("label", hit.label())
				.put("score", round(hit.score())));
		}
		JsonObject json = new JsonObject()
			.put("safe", safe)
			.put("score", round(score))
			.put("threshold", threshold)
			.put("family", family.name())
			.put("model", model)
			.put("subject", subject)
			.put("categories", cats)
			.put("raw", raw)
			.put("probes", probes)
			.put("scoreExact", scoreExact);
		if (textChars != null) {
			json.put("textChars", textChars);
		}
		return json;
	}

	/**
	 * A small GFM table for the editor's debug view. Never throws — a preview must not be able to
	 * fail the node.
	 *
	 * @return markdown
	 */
	public String toMarkdown() {
		StringBuilder sb = new StringBuilder();
		sb.append(safe ? "**safe**" : "**unsafe**")
			.append(" — score ").append(round(score))
			.append(" (threshold ").append(threshold).append(", ").append(probes).append(" probe(s))");
		if (!scoreExact) {
			sb.append("\n\n_The backend returned no log probabilities; the score is the argmax fallback._");
		}
		if (categories.isEmpty()) {
			return sb.toString();
		}
		sb.append("\n\n| Category | Native | Score |\n|---|---|---|\n");
		for (Hit hit : categories) {
			sb.append("| ").append(hit.label())
				.append(" | ").append(hit.nativeCode())
				.append(" | ").append(round(hit.score()))
				.append(" |\n");
		}
		return sb.toString();
	}

	private static double round(double value) {
		return Math.round(value * 10_000d) / 10_000d;
	}
}
