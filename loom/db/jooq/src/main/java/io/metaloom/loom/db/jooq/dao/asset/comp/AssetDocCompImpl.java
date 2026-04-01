package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetDocComp;

public class AssetDocCompImpl extends AbstractEditableElement<AssetDocComp> implements AssetDocComp {

	private UUID assetUuid;
	private String source;
	private String docPlainText;
	private Integer docWordCount;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetDocComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetDocComp setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getDocPlainText() {
		return docPlainText;
	}

	@Override
	public AssetDocComp setDocPlainText(String text) {
		this.docPlainText = text;
		return this;
	}

	@Override
	public Integer getDocWordCount() {
		return docWordCount;
	}

	@Override
	public AssetDocComp setDocWordCount(Integer wordCount) {
		this.docWordCount = wordCount;
		return this;
	}
}
