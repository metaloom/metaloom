package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public interface TranscriptModel<T extends TranscriptModel<T>> extends MetaModel<T>, RestModel {

	String getSource();

	T setSource(String source);

	String getLang();

	T setLang(String lang);

	String getTranscriptText();

	T setTranscriptText(String transcriptText);

	Long getDuration();

	T setDuration(Long duration);

	String getModel();

	T setModel(String model);

	JsonObject getTranscriptJson();

	T setTranscriptJson(JsonObject transcriptJson);

}
