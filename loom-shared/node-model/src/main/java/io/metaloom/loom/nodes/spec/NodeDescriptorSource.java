package io.metaloom.loom.nodes.spec;

/**
 * Where a {@link NodeDescriptor} in the registry came from.
 *
 * <p>
 * The two layers are not equal: {@link #BUILTIN} always wins. A worker announcing a spec for a
 * built-in node id has its content ignored — see the plan's §4.3 — because Loom's own compiled
 * contract is the one its engine, its validators and its checked-in snapshot agree on.
 * </p>
 */
public enum NodeDescriptorSource {

	/** Compiled into Loom and discovered through {@code ServiceLoader} at boot. Never overwritten. */
	BUILTIN,

	/** Announced by a Cortex worker over {@code NODE_REGISTRATION} and persisted by Loom. */
	ANNOUNCED;
}
