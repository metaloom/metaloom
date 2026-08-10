package io.metaloom.loom.rest.model.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What one kind of stored content costs.
 *
 * <p>
 * Two byte figures, and they answer different questions. {@code logicalBytes} is what the catalogue claims - add up every row - and is what a quota
 * would be charged against. {@code distinctBytes} is what the disk actually holds, because storage is content-addressed and identical content is
 * stored once however many rows point at it.
 * </p>
 *
 * <p>
 * 🔴 {@code distinctBytes} does <strong>not</strong> sum across categories. One stored object can appear under two of them - copying a face crop into
 * a person's gallery shares the bytes deliberately - so adding the column up double-counts. The physical total is
 * {@code StorageReportResponse.distinctBytes}.
 * </p>
 */
public class StorageCategoryModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The kind of content: ASSET_BINARY, ASSET_THUMBNAIL, EMBEDDING_ATTACHMENT, FACE_CROP, PERSON_IMAGE, PERSON_AVATAR or USER_AVATAR. A future release may add values.")
	private String category;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many elements of this kind exist.")
	private long elements;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The sum of those elements' sizes in bytes, counting duplicated content once per element.")
	private long logicalBytes;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many distinct stored objects those elements resolve to.")
	private long distinctObjects;

	@JsonProperty(required = true)
	@JsonPropertyDescription("What those objects occupy in bytes, counting duplicated content once. Not summable across categories: one object can belong to two of them.")
	private long distinctBytes;

	public StorageCategoryModel() {
	}

	public String getCategory() {
		return category;
	}

	public StorageCategoryModel setCategory(String category) {
		this.category = category;
		return this;
	}

	public long getElements() {
		return elements;
	}

	public StorageCategoryModel setElements(long elements) {
		this.elements = elements;
		return this;
	}

	public long getLogicalBytes() {
		return logicalBytes;
	}

	public StorageCategoryModel setLogicalBytes(long logicalBytes) {
		this.logicalBytes = logicalBytes;
		return this;
	}

	public long getDistinctObjects() {
		return distinctObjects;
	}

	public StorageCategoryModel setDistinctObjects(long distinctObjects) {
		this.distinctObjects = distinctObjects;
		return this;
	}

	public long getDistinctBytes() {
		return distinctBytes;
	}

	public StorageCategoryModel setDistinctBytes(long distinctBytes) {
		this.distinctBytes = distinctBytes;
		return this;
	}
}
