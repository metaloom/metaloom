package io.metaloom.loom.db.model.asset;

/**
 * Document component of an asset. Multiple doc components can exist per asset.
 */
public interface AssetDocComp extends AssetComponent<AssetDocComp> {

	String getDocPlainText();

	AssetDocComp setDocPlainText(String text);

	Integer getDocWordCount();

	AssetDocComp setDocWordCount(Integer wordCount);
}
