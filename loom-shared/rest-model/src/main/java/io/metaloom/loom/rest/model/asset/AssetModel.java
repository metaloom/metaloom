
package io.metaloom.loom.rest.model.asset;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.FingerprintInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.SHA512;

public interface AssetModel<T extends AssetModel<T>> extends MetaModel<T> {

	GeoLocationInfo getGeo();

	T setGeo(GeoLocationInfo geo);

	HashInfo getHashes();

	T setHashes(HashInfo hashes);

	FingerprintInfo getFingerprint();

	T setFingerprint(FingerprintInfo fingerprint);

	FileInfo getFile();

	T setFile(FileInfo file);

	ConsistencyInfo getConsistency();

	T setConsistency(ConsistencyInfo consistency);

	@JsonIgnore
	default T setRequired(String filename, String mimeType, SHA512 hashsum, long size, String origin) {
		if (getFile() == null) {
			setFile(new FileInfo());
		}
		getFile().setFilename(filename).setSize(size).setMimeType(mimeType).setOrigin(origin);
		if (getHashes() == null) {
			setHashes(new HashInfo());
		}
		getHashes().setSHA512(hashsum);
		return self();
	}

}
