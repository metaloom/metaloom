package io.metaloom.loom.rest.model.asset;

/**
 * Discriminator for the type of asset component.
 */
public enum AssetComponentType {
	GEO,
	DOC,
	IMAGE,
	VIDEO,
	AUDIO,
	TRANSCRIPT,
	JSON;
}
