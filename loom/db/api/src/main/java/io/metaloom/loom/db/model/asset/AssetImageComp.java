package io.metaloom.loom.db.model.asset;

/**
 * Image component of an asset. Multiple image components can exist per asset (e.g. thumbnail, preview, full-size).
 */
public interface AssetImageComp extends AssetComponent<AssetImageComp> {

	String getImageDominantColor();

	AssetImageComp setImageDominantColor(String color);

	Integer getMediaWidth();

	AssetImageComp setMediaWidth(Integer width);

	Integer getMediaHeight();

	AssetImageComp setMediaHeight(Integer height);
}
