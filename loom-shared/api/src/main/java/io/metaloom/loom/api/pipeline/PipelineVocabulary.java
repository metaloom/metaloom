package io.metaloom.loom.api.pipeline;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Shared parsing for the pipeline status and state vocabularies.
 *
 * <p>
 * All three columns are {@code VARCHAR}, so nothing in the database stops a typo being written. The
 * only defence is refusing to hand one on: a value that is not in the vocabulary is rejected here,
 * naming the column, the value and what was allowed, rather than travelling to the UI as a status
 * nothing can switch on.
 * </p>
 */
final class PipelineVocabulary {

	private PipelineVocabulary() {
	}

	/**
	 * @param type   the vocabulary
	 * @param column the column or field the value came from, e.g. {@code pipeline_run.status}
	 * @param value  the raw value; {@code null} and blank both parse to {@code null}
	 */
	static <E extends Enum<E>> E parse(Class<E> type, String column, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown " + column + " value '" + value + "'. Expected one of "
				+ Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")) + ".", e);
		}
	}
}
