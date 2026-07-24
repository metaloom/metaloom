package io.metaloom.loom.db.model.asset;

/**
 * Document / extracted text component of an asset.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, page_number)</code>. Tika writes the whole document as page 0, OCR writes one component per page.
 * </p>
 */
public interface AssetDocComp extends AssetComponent<AssetDocComp> {

	/**
	 * Return the page this text was extracted from; 0 means the whole document.
	 */
	int getPageNumber();

	AssetDocComp setPageNumber(int pageNumber);

	/**
	 * Return the total page count of the document, when the producer reports one.
	 */
	Integer getPageCount();

	AssetDocComp setPageCount(Integer pageCount);

	/**
	 * Return the detected or configured language of the extracted text.
	 */
	String getTextLang();

	AssetDocComp setTextLang(String textLang);

	String getDocPlainText();

	AssetDocComp setDocPlainText(String text);

	Integer getDocWordCount();

	AssetDocComp setDocWordCount(Integer wordCount);
}
