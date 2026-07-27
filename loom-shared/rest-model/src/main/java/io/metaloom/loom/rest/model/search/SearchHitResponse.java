package io.metaloom.loom.rest.model.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One search result.
 */
public class SearchHitResponse implements RestResponseModel<SearchHitResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Kind of element that was found: asset, transcript, tag, annotation, person, collection, library, cluster.")
	private String type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("UUID of the found element.")
	private UUID uuid;

	@JsonPropertyDescription("UUID of the asset this hit belongs to, when there is one. Equals uuid for asset hits.")
	private UUID assetUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Relevance score. Only comparable within one response.")
	private double score;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Primary label of the hit, e.g. the filename or tag name.")
	private String title;

	@JsonPropertyDescription("Secondary label, e.g. the file path or tag collection.")
	private String subtitle;

	@JsonPropertyDescription("Which field produced the match: title, subtitle, body, keywords or fuzzy.")
	private String matchedIn;

	@JsonPropertyDescription("Match snippets. Only populated when highlight=true was requested and the provider supports it.")
	private List<String> highlights = new ArrayList<>();

	@JsonPropertyDescription("Offset into the media in milliseconds, for transcript and annotation hits. Lets a client deep-link into the player.")
	private Long timeFromMs;

	@JsonPropertyDescription("Mime type of the associated asset.")
	private String mimeType;

	@JsonPropertyDescription("Size in bytes of the associated asset.")
	private Long size;

	@JsonPropertyDescription("Date used for chronological sorting.")
	private Instant sortDate;

	public String getType() {
		return type;
	}

	public SearchHitResponse setType(String type) {
		this.type = type;
		return this;
	}

	public UUID getUuid() {
		return uuid;
	}

	public SearchHitResponse setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public SearchHitResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public double getScore() {
		return score;
	}

	public SearchHitResponse setScore(double score) {
		this.score = score;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public SearchHitResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	public String getSubtitle() {
		return subtitle;
	}

	public SearchHitResponse setSubtitle(String subtitle) {
		this.subtitle = subtitle;
		return this;
	}

	public String getMatchedIn() {
		return matchedIn;
	}

	public SearchHitResponse setMatchedIn(String matchedIn) {
		this.matchedIn = matchedIn;
		return this;
	}

	public List<String> getHighlights() {
		return highlights;
	}

	public SearchHitResponse setHighlights(List<String> highlights) {
		this.highlights = highlights;
		return this;
	}

	public Long getTimeFromMs() {
		return timeFromMs;
	}

	public SearchHitResponse setTimeFromMs(Long timeFromMs) {
		this.timeFromMs = timeFromMs;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public SearchHitResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public Long getSize() {
		return size;
	}

	public SearchHitResponse setSize(Long size) {
		this.size = size;
		return this;
	}

	public Instant getSortDate() {
		return sortDate;
	}

	public SearchHitResponse setSortDate(Instant sortDate) {
		this.sortDate = sortDate;
		return this;
	}

	@Override
	public SearchHitResponse self() {
		return this;
	}
}
