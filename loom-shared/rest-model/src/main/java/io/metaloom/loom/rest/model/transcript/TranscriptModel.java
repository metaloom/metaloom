package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public interface TranscriptModel<T extends TranscriptModel<T>> extends MetaModel<T>, RestModel {

	/**
	 * Return the source of the transcript. This is the node kind (e.g. {@code whisper}) and maps onto the {@code node_kind} column / component
	 * identity - the wire name is kept as {@code source} for backwards compatibility.
	 */
	String getSource();

	T setSource(String source);

	/**
	 * Return the model or algorithm version of the producer (e.g. {@code whisper-large-v3}). Maps onto {@code producer_version}; the invalidation
	 * query is {@code WHERE node_kind = ? AND producer_version <> ?}.
	 */
	String getProducerVersion();

	T setProducerVersion(String producerVersion);

	/**
	 * Return which audio track was transcribed. Part of the component identity {@code (asset, node_kind, stream_index, lang)}; null defaults to 0.
	 */
	Integer getStreamIndex();

	T setStreamIndex(Integer streamIndex);

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
