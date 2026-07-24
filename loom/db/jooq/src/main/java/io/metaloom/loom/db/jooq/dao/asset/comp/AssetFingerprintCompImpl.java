package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetFingerprintComp;

public class AssetFingerprintCompImpl extends AbstractAssetCompImpl<AssetFingerprintComp> implements AssetFingerprintComp {

	private String algorithm;
	private int sectorIndex;
	private Long timeFrom;
	private Long timeTo;
	private String fingerprint;

	@Override
	public String getAlgorithm() {
		return algorithm;
	}

	@Override
	public AssetFingerprintComp setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	@Override
	public int getSectorIndex() {
		return sectorIndex;
	}

	@Override
	public AssetFingerprintComp setSectorIndex(int sectorIndex) {
		this.sectorIndex = sectorIndex;
		return this;
	}

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public AssetFingerprintComp setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Long getTimeTo() {
		return timeTo;
	}

	@Override
	public AssetFingerprintComp setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public String getFingerprint() {
		return fingerprint;
	}

	@Override
	public AssetFingerprintComp setFingerprint(String fingerprint) {
		this.fingerprint = fingerprint;
		return this;
	}
}
