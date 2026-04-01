package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetImageComp;

public class AssetImageCompImpl extends AbstractEditableElement<AssetImageComp> implements AssetImageComp {

	private UUID assetUuid;
	private String source;
	private String imageDominantColor;
	private Integer mediaWidth;
	private Integer mediaHeight;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetImageComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetImageComp setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getImageDominantColor() {
		return imageDominantColor;
	}

	@Override
	public AssetImageComp setImageDominantColor(String color) {
		this.imageDominantColor = color;
		return this;
	}

	@Override
	public Integer getMediaWidth() {
		return mediaWidth;
	}

	@Override
	public AssetImageComp setMediaWidth(Integer width) {
		this.mediaWidth = width;
		return this;
	}

	@Override
	public Integer getMediaHeight() {
		return mediaHeight;
	}

	@Override
	public AssetImageComp setMediaHeight(Integer height) {
		this.mediaHeight = height;
		return this;
	}
}
