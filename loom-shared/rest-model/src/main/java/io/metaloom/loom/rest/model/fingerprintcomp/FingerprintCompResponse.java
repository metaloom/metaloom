package io.metaloom.loom.rest.model.fingerprintcomp;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class FingerprintCompResponse extends AbstractCreatorEditorRestResponse<FingerprintCompResponse>
	implements FingerprintCompModel<FingerprintCompResponse> {

	private String assetUuid;
	private String nodeKind;
	private String algorithm;
	private int sectorIndex;
	private Long timeFrom;
	private Long timeTo;
	private String fingerprint;
	private String producerVersion;

	public String getAssetUuid() {
		return assetUuid;
	}

	public FingerprintCompResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public FingerprintCompResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getAlgorithm() {
		return algorithm;
	}

	@Override
	public FingerprintCompResponse setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	@Override
	public int getSectorIndex() {
		return sectorIndex;
	}

	@Override
	public FingerprintCompResponse setSectorIndex(int sectorIndex) {
		this.sectorIndex = sectorIndex;
		return this;
	}

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public FingerprintCompResponse setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Long getTimeTo() {
		return timeTo;
	}

	@Override
	public FingerprintCompResponse setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public String getFingerprint() {
		return fingerprint;
	}

	@Override
	public FingerprintCompResponse setFingerprint(String fingerprint) {
		this.fingerprint = fingerprint;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public FingerprintCompResponse setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public FingerprintCompResponse self() {
		return this;
	}

}
