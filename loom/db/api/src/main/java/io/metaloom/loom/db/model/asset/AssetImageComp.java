package io.metaloom.loom.db.model.asset;

/**
 * Image component of an asset.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, stream_index)</code>. Never gated on the asset's mime type: an MP3 with embedded cover art legitimately owns
 * an image component.
 * </p>
 */
public interface AssetImageComp extends AssetComponent<AssetImageComp> {

	/**
	 * Return which image stream this describes: multi-frame TIFF/GIF, embedded cover art. 0 for a plain image.
	 */
	int getStreamIndex();

	AssetImageComp setStreamIndex(int streamIndex);

	String getImageDominantColor();

	AssetImageComp setImageDominantColor(String color);

	String getImageEncoding();

	AssetImageComp setImageEncoding(String encoding);

	Integer getMediaWidth();

	AssetImageComp setMediaWidth(Integer width);

	Integer getMediaHeight();

	AssetImageComp setMediaHeight(Integer height);

	Integer getOrientation();

	AssetImageComp setOrientation(Integer orientation);

	Integer getBitDepth();

	AssetImageComp setBitDepth(Integer bitDepth);

	/**
	 * Return the Laplacian blurriness measure produced by the quality node.
	 */
	Float getBlurriness();

	AssetImageComp setBlurriness(Float blurriness);
}
