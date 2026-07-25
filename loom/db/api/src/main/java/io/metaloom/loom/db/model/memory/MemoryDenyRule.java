package io.metaloom.loom.db.model.memory;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

/**
 * One entry of the instance-wide memory denylist.
 *
 * <p>Every {@code put_memory} body and title is matched against the enabled rules; a hit rejects the write with {@link #getMessage()}. Rules are curated by
 * administrators, not by the agent, which is why they live in their own table with their own permissions.</p>
 */
public interface MemoryDenyRule extends CUDElement<MemoryDenyRule>, MetaElement<MemoryDenyRule> {

	/**
	 * Human readable rule name, unique across the instance.
	 */
	String getName();

	MemoryDenyRule setName(String name);

	/**
	 * The regular expression matched against a note. A single rule can cover several phrases through alternation, e.g.
	 * {@code (?i)\b(project bluebird|operation nightfall)\b}.
	 */
	String getPattern();

	MemoryDenyRule setPattern(String pattern);

	/**
	 * The message handed back when this rule rejects a write. It must explain the problem without echoing the matched text — the agent would otherwise put
	 * the very secret it was stopped from storing into the chat transcript.
	 */
	String getMessage();

	MemoryDenyRule setMessage(String message);

	/**
	 * Disabled rules are kept for reference but never matched.
	 */
	boolean isEnabled();

	MemoryDenyRule setEnabled(boolean enabled);

}
