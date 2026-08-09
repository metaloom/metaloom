package io.metaloom.loom.rest.model.library;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request to add a single asset to a library.
 *
 * <p>
 * This writes the organizational membership ({@code library_asset}) only. It does not create or move a binary - where an asset's bytes live is
 * {@code /binaries} and the asset's pool, not this route.
 * </p>
 */
public class LibraryAssetRequest implements RestRequestModel {

	@JsonPropertyDescription("Uuid of the asset to add to the library.")
	private UUID assetUuid;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public LibraryAssetRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

}
