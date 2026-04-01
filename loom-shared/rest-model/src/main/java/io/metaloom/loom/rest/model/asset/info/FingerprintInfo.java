package io.metaloom.loom.rest.model.asset.info;

import io.metaloom.loom.rest.model.RestModel;

public class FingerprintInfo implements RestModel {

	private String fingerprintV1;

	public String getFingerprintV1() {
		return fingerprintV1;
	}

	public FingerprintInfo setFingerprintV1(String fp) {
		this.fingerprintV1 = fp;
		return this;
	}

}
