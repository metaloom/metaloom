package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;
import io.vertx.core.json.JsonObject;

public class TranscriptCreateRequest extends AbstractMetaModel<TranscriptCreateRequest>
	implements RestRequestModel, TranscriptModel<TranscriptCreateRequest> {

	private String source;
	private String lang;
	private String transcriptText;
	private Long duration;
	private String model;
	private JsonObject transcriptJson;

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public TranscriptCreateRequest setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getLang() {
		return lang;
	}

	@Override
	public TranscriptCreateRequest setLang(String lang) {
		this.lang = lang;
		return this;
	}

	@Override
	public String getTranscriptText() {
		return transcriptText;
	}

	@Override
	public TranscriptCreateRequest setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
		return this;
	}

	@Override
	public Long getDuration() {
		return duration;
	}

	@Override
	public TranscriptCreateRequest setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public TranscriptCreateRequest setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public JsonObject getTranscriptJson() {
		return transcriptJson;
	}

	@Override
	public TranscriptCreateRequest setTranscriptJson(JsonObject transcriptJson) {
		this.transcriptJson = transcriptJson;
		return this;
	}

	@Override
	public TranscriptCreateRequest self() {
		return this;
	}

}
