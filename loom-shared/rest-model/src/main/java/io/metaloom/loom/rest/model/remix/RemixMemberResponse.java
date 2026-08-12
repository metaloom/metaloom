package io.metaloom.loom.rest.model.remix;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * One asset's membership in a remix, with enough of the asset to render a card without a second
 * request.
 *
 * <p>
 * {@code uuid} is the membership's own identity, not the asset's - {@code assetUuid} is the asset.
 * </p>
 */
public class RemixMemberResponse extends AbstractResponse<RemixMemberResponse> {

	/** The original the remix is built around. At most one per remix. */
	public static final String ROLE_SOURCE = "SOURCE";

	/** Anything made from the source. */
	public static final String ROLE_DERIVED = "DERIVED";

	@JsonPropertyDescription("Uuid of the member asset.")
	private UUID assetUuid;

	@JsonPropertyDescription("SOURCE or DERIVED.")
	private String role;

	@JsonPropertyDescription("User-defined position within the remix. Null sorts last.")
	private Integer ordinal;

	@JsonPropertyDescription("Filename of the member asset.")
	private String filename;

	@JsonPropertyDescription("Mime type of the member asset.")
	private String mimeType;

	@JsonPropertyDescription("SHA512 content hash of the member asset.")
	private String sha512sum;

	@JsonPropertyDescription("Size of the member asset in bytes.")
	private Long size;

	@JsonPropertyDescription("When the asset was added to the remix.")
	private Instant added;

	@JsonPropertyDescription("Uuid of the user who added it.")
	private UUID addedBy;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public RemixMemberResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getRole() {
		return role;
	}

	public RemixMemberResponse setRole(String role) {
		this.role = role;
		return this;
	}

	public Integer getOrdinal() {
		return ordinal;
	}

	public RemixMemberResponse setOrdinal(Integer ordinal) {
		this.ordinal = ordinal;
		return this;
	}

	public String getFilename() {
		return filename;
	}

	public RemixMemberResponse setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public RemixMemberResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public String getSha512sum() {
		return sha512sum;
	}

	public RemixMemberResponse setSha512sum(String sha512sum) {
		this.sha512sum = sha512sum;
		return this;
	}

	public Long getSize() {
		return size;
	}

	public RemixMemberResponse setSize(Long size) {
		this.size = size;
		return this;
	}

	public Instant getAdded() {
		return added;
	}

	public RemixMemberResponse setAdded(Instant added) {
		this.added = added;
		return this;
	}

	public UUID getAddedBy() {
		return addedBy;
	}

	public RemixMemberResponse setAddedBy(UUID addedBy) {
		this.addedBy = addedBy;
		return this;
	}

	@Override
	public RemixMemberResponse self() {
		return this;
	}

}
