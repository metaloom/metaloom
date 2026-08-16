package io.metaloom.loom.rest.model.fingerprintcomp;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

/**
 * Wire model for a perceptual fingerprint component ({@code asset_fingerprint_comp}).
 *
 * <p>
 * Identity: {@code (asset_uuid, node_kind, algorithm, window_index)}. A cortex node posts a computed fingerprint here; re-posting the same key upserts
 * the row. The table is indexed by {@code (algorithm, fingerprint)} so "which other assets share this fingerprint" is an index scan.
 * </p>
 */
public interface FingerprintCompModel<T extends FingerprintCompModel<T>> extends MetaModel<T>, RestModel {

	/**
	 * Return the kind of node that produced the fingerprint, e.g. {@code fingerprint}. Part of the component identity.
	 */
	String getNodeKind();

	T setNodeKind(String nodeKind);

	/**
	 * Return the fingerprint algorithm identifier, e.g. {@code metaloom-multisector-v1}. Part of the component identity.
	 */
	String getAlgorithm();

	T setAlgorithm(String algorithm);

	/**
	 * Return the timeline window this row covers: 0 is the whole-asset fingerprint, 1..n are windows with {@code timeFrom}/{@code timeTo} set.
	 * Unrelated to the internal sectors of the multi-sector fingerprint algorithm, which are folded into a single vector and never become rows. Part
	 * of the component identity.
	 */
	int getWindowIndex();

	T setWindowIndex(int windowIndex);

	/**
	 * Return the start of the window this row covers, in milliseconds, or null on the whole-asset row.
	 */
	Long getTimeFrom();

	T setTimeFrom(Long timeFrom);

	/**
	 * Return the end of the window this row covers, in milliseconds, or null on the whole-asset row.
	 */
	Long getTimeTo();

	T setTimeTo(Long timeTo);

	/**
	 * Return the fingerprint value, hex or base64 encoded.
	 */
	String getFingerprint();

	T setFingerprint(String fingerprint);

	/**
	 * Return the model or algorithm version of the producer, or null.
	 */
	String getProducerVersion();

	T setProducerVersion(String producerVersion);

}
