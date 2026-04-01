package io.metaloom.loom.db.model.asset;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

/**
 * Base interface for all asset component types.
 */
public interface AssetComponent<SELF extends AssetComponent<SELF>> extends CUDElement<SELF> {

	UUID getAssetUuid();

	SELF setAssetUuid(UUID assetUuid);

	/**
	 * Return the source label that indicates the origin of this component within the asset (e.g. "exif", "audio track german", "thumbnail").
	 */
	String getSource();

	SELF setSource(String source);
}
