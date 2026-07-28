package io.metaloom.cortex.node.color;

/**
 * Where a region came from. Emitted in the payload so a consumer can tell "the whole photo is
 * blue" from "this one detected face is blue" without inspecting the box.
 */
public enum RegionKind {

	/** The whole decoded frame. */
	IMAGE,

	/** A region fixed in the node's own configuration. */
	CONFIG,

	/** A bounding box supplied by an upstream detector. */
	DETECTION
}
