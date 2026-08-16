package io.metaloom.loom.rest.model.fingerprintcomp;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Create/upsert request for a perceptual fingerprint component. Keyed by {@code (asset, node_kind, algorithm, window_index)}; re-posting the same key
 * rewrites the row.
 */
public class FingerprintCompCreateRequest extends AbstractMetaModel<FingerprintCompCreateRequest>
	implements RestRequestModel, FingerprintCompModel<FingerprintCompCreateRequest> {

	private String nodeKind;
	private String algorithm;
	private int windowIndex;
	private Long timeFrom;
	private Long timeTo;
	private String fingerprint;
	private String producerVersion;

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public FingerprintCompCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getAlgorithm() {
		return algorithm;
	}

	@Override
	public FingerprintCompCreateRequest setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	@Override
	public int getWindowIndex() {
		return windowIndex;
	}

	@Override
	public FingerprintCompCreateRequest setWindowIndex(int windowIndex) {
		this.windowIndex = windowIndex;
		return this;
	}

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public FingerprintCompCreateRequest setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Long getTimeTo() {
		return timeTo;
	}

	@Override
	public FingerprintCompCreateRequest setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public String getFingerprint() {
		return fingerprint;
	}

	@Override
	public FingerprintCompCreateRequest setFingerprint(String fingerprint) {
		this.fingerprint = fingerprint;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public FingerprintCompCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public FingerprintCompCreateRequest self() {
		return this;
	}

}
