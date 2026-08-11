package io.metaloom.loom.db.jooq.dao.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractElement;
import io.metaloom.loom.db.model.share.ShareAnnotation;

public class ShareAnnotationImpl extends AbstractElement<ShareAnnotation> implements ShareAnnotation {

	private UUID uuid;
	private UUID shareUuid;
	private UUID assetUuid;
	private String kind;
	private Double timeFrom;
	private Double timeTo;
	private Double areaX;
	private Double areaY;
	private Double areaWidth;
	private Double areaHeight;
	private String text;
	private String authorName;
	private Instant created;
	private Instant edited;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public ShareAnnotation setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public UUID getShareUuid() {
		return shareUuid;
	}

	@Override
	public ShareAnnotation setShareUuid(UUID shareUuid) {
		this.shareUuid = shareUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public ShareAnnotation setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getKind() {
		return kind;
	}

	@Override
	public ShareAnnotation setKind(String kind) {
		this.kind = kind;
		return this;
	}

	@Override
	public Double getTimeFrom() {
		return timeFrom;
	}

	@Override
	public ShareAnnotation setTimeFrom(Double timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Double getTimeTo() {
		return timeTo;
	}

	@Override
	public ShareAnnotation setTimeTo(Double timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public Double getAreaX() {
		return areaX;
	}

	@Override
	public ShareAnnotation setAreaX(Double areaX) {
		this.areaX = areaX;
		return this;
	}

	@Override
	public Double getAreaY() {
		return areaY;
	}

	@Override
	public ShareAnnotation setAreaY(Double areaY) {
		this.areaY = areaY;
		return this;
	}

	@Override
	public Double getAreaWidth() {
		return areaWidth;
	}

	@Override
	public ShareAnnotation setAreaWidth(Double areaWidth) {
		this.areaWidth = areaWidth;
		return this;
	}

	@Override
	public Double getAreaHeight() {
		return areaHeight;
	}

	@Override
	public ShareAnnotation setAreaHeight(Double areaHeight) {
		this.areaHeight = areaHeight;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public ShareAnnotation setText(String text) {
		this.text = text;
		return this;
	}

	@Override
	public String getAuthorName() {
		return authorName;
	}

	@Override
	public ShareAnnotation setAuthorName(String authorName) {
		this.authorName = authorName;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public ShareAnnotation setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public Instant getEdited() {
		return edited;
	}

	@Override
	public ShareAnnotation setEdited(Instant edited) {
		this.edited = edited;
		return this;
	}
}
