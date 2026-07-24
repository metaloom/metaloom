package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetDocComp;

public class AssetDocCompImpl extends AbstractAssetCompImpl<AssetDocComp> implements AssetDocComp {

	private int pageNumber;
	private Integer pageCount;
	private String textLang;
	private String docPlainText;
	private Integer docWordCount;

	@Override
	public int getPageNumber() {
		return pageNumber;
	}

	@Override
	public AssetDocComp setPageNumber(int pageNumber) {
		this.pageNumber = pageNumber;
		return this;
	}

	@Override
	public Integer getPageCount() {
		return pageCount;
	}

	@Override
	public AssetDocComp setPageCount(Integer pageCount) {
		this.pageCount = pageCount;
		return this;
	}

	@Override
	public String getTextLang() {
		return textLang;
	}

	@Override
	public AssetDocComp setTextLang(String textLang) {
		this.textLang = textLang;
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
