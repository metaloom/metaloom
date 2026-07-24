package io.metaloom.loom.db.model.asset;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

/**
 * Base interface for all asset component types.
 *
 * <p>
 * Every component carries the provenance of the node that produced it. Together with the per-type discriminators (stream index, page number, language,
 * ...) that provenance forms the component's identity: each table has a <code>UNIQUE (asset_uuid, node_kind, &lt;discriminators&gt;)</code> key, so a
 * node that runs again replaces its own row rather than appending a duplicate.
 * </p>
 *
 * <p>
 * {@link #getProducerVersion()} is deliberately <b>not</b> part of that key: a model upgrade replaces the row and rewrites the version, and
 * <code>WHERE node_kind = ? AND producer_version &lt;&gt; ?</code> finds everything that needs recomputing.
 * </p>
 */
public interface AssetComponent<SELF extends AssetComponent<SELF>> extends CUDElement<SELF> {

	/**
	 * Node kind recorded for components that were written through the API by a user rather than produced by a pipeline node.
	 */
	String NODE_KIND_MANUAL = "manual";

	UUID getAssetUuid();

	SELF setAssetUuid(UUID assetUuid);

	/**
	 * Return the kind of node that produced this component (e.g. "whisper", "ocr", "tika", "manual").
	 */
	String getNodeKind();

	SELF setNodeKind(String nodeKind);

	/**
	 * Return the graph-local id of the node instance that produced this component, or null when it was not produced by a pipeline.
	 */
	String getNodeId();

	SELF setNodeId(String nodeId);

	/**
	 * Return the model or algorithm version of the producer (e.g. "whisper-large-v3"). Never null - an unknown version is the empty string.
	 */
	String getProducerVersion();

	SELF setProducerVersion(String producerVersion);

	/**
	 * Return the pipeline run that produced this component, or null.
	 */
	UUID getRunUuid();

	SELF setRunUuid(UUID runUuid);

	/**
	 * Return the pipeline node task that produced this component, or null.
	 */
	UUID getTaskUuid();

	SELF setTaskUuid(UUID taskUuid);

	/**
	 * Return the extraction confidence, where the producer reports one.
	 */
	Float getConfidence();

	SELF setConfidence(Float confidence);
}
