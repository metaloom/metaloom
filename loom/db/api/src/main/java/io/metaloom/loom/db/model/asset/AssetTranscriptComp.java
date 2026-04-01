package io.metaloom.loom.db.model.asset;

import io.vertx.core.json.JsonObject;

/**
 * Transcript component of an asset. Multiple transcripts can exist per asset (e.g. different languages, models).
 */
public interface AssetTranscriptComp extends AssetComponent<AssetTranscriptComp> {

	String getLang();

	AssetTranscriptComp setLang(String lang);

	String getTranscriptText();

	AssetTranscriptComp setTranscriptText(String text);

	Integer getDuration();

	AssetTranscriptComp setDuration(Integer duration);

	String getModel();

	AssetTranscriptComp setModel(String model);

	JsonObject getTranscriptJson();

	AssetTranscriptComp setTranscriptJson(JsonObject json);
}
