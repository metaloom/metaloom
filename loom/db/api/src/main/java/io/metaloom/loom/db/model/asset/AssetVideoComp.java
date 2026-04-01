package io.metaloom.loom.db.model.asset;

/**
 * Video component of an asset. Multiple video components can exist per asset (e.g. different video streams).
 */
public interface AssetVideoComp extends AssetComponent<AssetVideoComp> {

	Integer getMediaWidth();

	AssetVideoComp setMediaWidth(Integer width);

	Integer getMediaHeight();

	AssetVideoComp setMediaHeight(Integer height);

	Long getMediaDuration();

	AssetVideoComp setMediaDuration(Long duration);

	Integer getVideoBitrate();

	AssetVideoComp setVideoBitrate(Integer bitrate);

	String getVideoEncoding();

	AssetVideoComp setVideoEncoding(String encoding);
}
