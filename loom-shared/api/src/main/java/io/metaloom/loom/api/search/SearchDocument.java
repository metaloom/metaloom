package io.metaloom.loom.api.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One indexable document - the write-side mirror of a {@code search_document} row.
 *
 * <p>
 * The 1:1 correspondence with the table is deliberate and load bearing: it is what lets an external indexer read one table instead of re-joining nine
 * source tables at index time, which in turn is what makes swapping the provider a binding change rather than a rewrite.
 * </p>
 *
 * <p>
 * The four text fields are ranked: {@link #getTitle()} weight A, {@link #getSubtitle()} B, {@link #getBody()} C, {@link #getKeywords()} D.
 * </p>
 */
public class SearchDocument {

	private SearchEntityType entityType;

	private UUID entityUuid;

	private UUID assetUuid;

	private String title = "";

	private String subtitle = "";

	private String body = "";

	private String keywords = "";

	/** Set when {@link #getBody()} was cut at the configured cap. A tsvector is limited to 1 MB, so long extractions must be truncated. */
	private boolean bodyTruncated;

	private String lang = "";

	private String mimeType;

	private Long size;

	private Long timeFrom;

	private Instant sortDate;

	/**
	 * Reserved ACL projection - written from day 1, read by nothing until row-level ACL lands. Assets have no {@code library_uuid} column; membership is
	 * many-to-many via {@code library_asset}, which is why this is a list.
	 */
	private List<UUID> libraryUuids = new ArrayList<>();

	/** Reserved ACL projection. See {@link #getLibraryUuids()}. */
	private List<UUID> spaceUuids = new ArrayList<>();

	/** Reserved ACL projection. See {@link #getLibraryUuids()}. */
	private List<UUID> collectionUuids = new ArrayList<>();

	private List<String> tagNames = new ArrayList<>();

	private int indexVersion = 1;

	private Instant syncedAt;

	public SearchEntityType getEntityType() {
		return entityType;
	}

	public SearchDocument setEntityType(SearchEntityType entityType) {
		this.entityType = entityType;
		return this;
	}

	public UUID getEntityUuid() {
		return entityUuid;
	}

	public SearchDocument setEntityUuid(UUID entityUuid) {
		this.entityUuid = entityUuid;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public SearchDocument setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public SearchDocument setTitle(String title) {
		this.title = title == null ? "" : title;
		return this;
	}

	public String getSubtitle() {
		return subtitle;
	}

	public SearchDocument setSubtitle(String subtitle) {
		this.subtitle = subtitle == null ? "" : subtitle;
		return this;
	}

	public String getBody() {
		return body;
	}

	public SearchDocument setBody(String body) {
		this.body = body == null ? "" : body;
		return this;
	}

	public String getKeywords() {
		return keywords;
	}

	public SearchDocument setKeywords(String keywords) {
		this.keywords = keywords == null ? "" : keywords;
		return this;
	}

	public boolean isBodyTruncated() {
		return bodyTruncated;
	}

	public SearchDocument setBodyTruncated(boolean bodyTruncated) {
		this.bodyTruncated = bodyTruncated;
		return this;
	}

	public String getLang() {
		return lang;
	}

	public SearchDocument setLang(String lang) {
		this.lang = lang == null ? "" : lang;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public SearchDocument setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public Long getSize() {
		return size;
	}

	public SearchDocument setSize(Long size) {
		this.size = size;
		return this;
	}

	public Long getTimeFrom() {
		return timeFrom;
	}

	public SearchDocument setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public Instant getSortDate() {
		return sortDate;
	}

	public SearchDocument setSortDate(Instant sortDate) {
		this.sortDate = sortDate;
		return this;
	}

	public List<UUID> getLibraryUuids() {
		return libraryUuids;
	}

	public SearchDocument setLibraryUuids(List<UUID> libraryUuids) {
		this.libraryUuids = libraryUuids == null ? new ArrayList<>() : libraryUuids;
		return this;
	}

	public List<UUID> getSpaceUuids() {
		return spaceUuids;
	}

	public SearchDocument setSpaceUuids(List<UUID> spaceUuids) {
		this.spaceUuids = spaceUuids == null ? new ArrayList<>() : spaceUuids;
		return this;
	}

	public List<UUID> getCollectionUuids() {
		return collectionUuids;
	}

	public SearchDocument setCollectionUuids(List<UUID> collectionUuids) {
		this.collectionUuids = collectionUuids == null ? new ArrayList<>() : collectionUuids;
		return this;
	}

	public List<String> getTagNames() {
		return tagNames;
	}

	public SearchDocument setTagNames(List<String> tagNames) {
		this.tagNames = tagNames == null ? new ArrayList<>() : tagNames;
		return this;
	}

	public int getIndexVersion() {
		return indexVersion;
	}

	public SearchDocument setIndexVersion(int indexVersion) {
		this.indexVersion = indexVersion;
		return this;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public SearchDocument setSyncedAt(Instant syncedAt) {
		this.syncedAt = syncedAt;
		return this;
	}

	@Override
	public String toString() {
		return "SearchDocument[" + entityType + " " + entityUuid + " " + title + "]";
	}
}
