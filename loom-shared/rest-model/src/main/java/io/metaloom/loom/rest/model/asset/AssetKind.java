package io.metaloom.loom.rest.model.asset;

/**
 * Definition of the different kinds of assets. 
 */
public enum AssetKind {
	
	/**
	 * Video files (e.g. mp4, mpeg, avi)
	 */
	VIDEO,
	
	/**
	 * Audio files (e.g. flac, mp3, wav)
	 */
	AUDIO,
	
	/**
	 * Image files (e.g. jpeg, png, bmp)
	 */
	IMAGE,
	
	/**
	 * Document files (e.g. docx, doc, txt, pdf)
	 */
	DOC,
	
	/**
	 * Other binary files.
	 */
	BIN;

}
