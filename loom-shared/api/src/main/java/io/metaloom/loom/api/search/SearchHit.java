package io.metaloom.loom.api.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One search result.
 *
 * <p>
 * A hit is deliberately <b>not</b> an {@code Element} - it can point at a transcript span or any other row that has no REST resource of its own, so it
 * carries just enough to render a result row and navigate to the thing it found.
 * </p>
 */
public class SearchHit {

	private SearchEntityType type;

	/** UUID of the entity itself (the transcript comp, the tag, ...). */
	private UUID uuid;

	/** The asset this hit belongs to, when there is one. Equals {@link #getUuid()} for {@link SearchEntityType#ASSET}. */
	private UUID assetUuid;

	private double score;

	private String title;

	private String subtitle;

	/** Which field produced the match: {@code filename}, {@code path}, {@code transcript}, {@code ocr}, {@code tag}, ... */
	private String matchedIn;

	private List<String> highlights = new ArrayList<>();

	/** Offset into the media in milliseconds, for transcript/segment/detection hits. Lets the UI deep-link into the player. */
	private Long timeFromMs;

	private String mimeType;

	private Long size;

	private Instant sortDate;

	public SearchEntityType getType() {
		return type;
	}

	public SearchHit setType(SearchEntityType type) {
		this.type = type;
		return this;
	}

	public UUID getUuid() {
		return uuid;
	}

	public SearchHit setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public SearchHit setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public double getScore() {
		return score;
	}

	public SearchHit setScore(double score) {
		this.score = score;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public SearchHit setTitle(String title) {
		this.title = title;
		return this;
	}

	public String getSubtitle() {
		return subtitle;
	}

	public SearchHit setSubtitle(String subtitle) {
		this.subtitle = subtitle;
		return this;
	}

	public String getMatchedIn() {
		return matchedIn;
	}

	public SearchHit setMatchedIn(String matchedIn) {
		this.matchedIn = matchedIn;
		return this;
	}

	public List<String> getHighlights() {
		return highlights;
	}

	public SearchHit setHighlights(List<String> highlights) {
		this.highlights = highlights == null ? new ArrayList<>() : highlights;
		return this;
	}

	public Long getTimeFromMs() {
		return timeFromMs;
	}

	public SearchHit setTimeFromMs(Long timeFromMs) {
		this.timeFromMs = timeFromMs;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public SearchHit setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public Long getSize() {
		return size;
	}

	public SearchHit setSize(Long size) {
		this.size = size;
		return this;
	}

	public Instant getSortDate() {
		return sortDate;
	}

	public SearchHit setSortDate(Instant sortDate) {
		this.sortDate = sortDate;
		return this;
	}

	@Override
	public String toString() {
		return "SearchHit[" + type + " " + uuid + " score=" + score + " " + title + "]";
	}
}
