package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.vertx.core.json.JsonObject;

public class AssetTranscriptCompImpl extends AbstractEditableElement<AssetTranscriptComp> implements AssetTranscriptComp {

	private UUID assetUuid;
	private String source;
	private String lang;
	private String transcriptText;
	private Integer duration;
	private String model;
	private JsonObject transcriptJson;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetTranscriptComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetTranscriptComp setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getLang() {
		return lang;
	}

	@Override
	public AssetTranscriptComp setLang(String lang) {
		this.lang = lang;
		return this;
	}

	@Override
	public String getTranscriptText() {
		return transcriptText;
	}

	@Override
	public AssetTranscriptComp setTranscriptText(String text) {
		this.transcriptText = text;
		return this;
	}

	@Override
	public Integer getDuration() {
		return duration;
	}

	@Override
	public AssetTranscriptComp setDuration(Integer duration) {
		this.duration = duration;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public AssetTranscriptComp setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public JsonObject getTranscriptJson() {
		return transcriptJson;
	}

	@Override
	public AssetTranscriptComp setTranscriptJson(JsonObject json) {
		this.transcriptJson = json;
		return this;
	}
}
