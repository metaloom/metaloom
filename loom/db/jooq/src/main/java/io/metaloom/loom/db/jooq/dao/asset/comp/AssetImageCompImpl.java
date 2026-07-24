package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetImageComp;

public class AssetImageCompImpl extends AbstractAssetCompImpl<AssetImageComp> implements AssetImageComp {

	private int streamIndex;
	private String imageDominantColor;
	private String imageEncoding;
	private Integer mediaWidth;
	private Integer mediaHeight;
	private Integer orientation;
	private Integer bitDepth;
	private Float blurriness;

	@Override
	public int getStreamIndex() {
		return streamIndex;
	}

	@Override
	public AssetImageComp setStreamIndex(int streamIndex) {
		this.streamIndex = streamIndex;
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
	public String getImageEncoding() {
		return imageEncoding;
	}

	@Override
	public AssetImageComp setImageEncoding(String encoding) {
		this.imageEncoding = encoding;
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

	@Override
	public Integer getOrientation() {
		return orientation;
	}

	@Override
	public AssetImageComp setOrientation(Integer orientation) {
		this.orientation = orientation;
		return this;
	}

	@Override
	public Integer getBitDepth() {
		return bitDepth;
	}

	@Override
	public AssetImageComp setBitDepth(Integer bitDepth) {
		this.bitDepth = bitDepth;
		return this;
	}

	@Override
	public Float getBlurriness() {
		return blurriness;
	}

	@Override
	public AssetImageComp setBlurriness(Float blurriness) {
		this.blurriness = blurriness;
		return this;
	}
}
