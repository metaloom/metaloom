package io.metaloom.loom.db.model.asset;

/**
 * Video stream component of an asset.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, stream_index)</code>. Two producers of the same dimension - a probe node and the quality node - yield two
 * partially filled components; the read side coalesces them by producer precedence.
 * </p>
 */
public interface AssetVideoComp extends AssetComponent<AssetVideoComp> {

	/**
	 * Return which video stream within the container this describes. 0 for single-stream media.
	 */
	int getStreamIndex();

	AssetVideoComp setStreamIndex(int streamIndex);

	Integer getMediaWidth();

	AssetVideoComp setMediaWidth(Integer width);

	Integer getMediaHeight();

	AssetVideoComp setMediaHeight(Integer height);

	/**
	 * Return the duration in milliseconds.
	 */
	Long getMediaDuration();

	AssetVideoComp setMediaDuration(Long duration);

	Integer getVideoBitrate();

	AssetVideoComp setVideoBitrate(Integer bitrate);

	String getVideoEncoding();

	AssetVideoComp setVideoEncoding(String encoding);

	Float getFps();

	AssetVideoComp setFps(Float fps);

	Long getFrameCount();

	AssetVideoComp setFrameCount(Long frameCount);

	Integer getRotation();

	AssetVideoComp setRotation(Integer rotation);

	/**
	 * Return the Laplacian blurriness measure produced by the quality node.
	 */
	Float getBlurriness();

	AssetVideoComp setBlurriness(Float blurriness);
}
