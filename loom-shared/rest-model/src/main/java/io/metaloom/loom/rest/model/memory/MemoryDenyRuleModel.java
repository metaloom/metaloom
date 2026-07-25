package io.metaloom.loom.rest.model.memory;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

/**
 * One entry of the memory denylist: a regular expression which must never appear in an agent memory note.
 */
public interface MemoryDenyRuleModel<T extends MemoryDenyRuleModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	/**
	 * The regular expression. A single rule covers several phrases through alternation, e.g. {@code (?i)\b(one|two|three)\b}.
	 */
	String getPattern();

	T setPattern(String pattern);

	/**
	 * The message returned to the agent when this rule rejects a write. It must not echo the matched text.
	 */
	String getMessage();

	T setMessage(String message);

	Boolean getEnabled();

	T setEnabled(Boolean enabled);

}
