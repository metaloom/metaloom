package io.metaloom.loom.db.jooq.dao.annotation;

import java.util.UUID;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.annotation.Annotation;

public class AnnotationImpl extends AbstractEditableElement<Annotation> implements Annotation {

	private AnnotationType type;

	private String title;

	private UUID assetUuid;

	private String description;

	private Long timeFrom;
	private Long timeTo;
	private Integer areaStartX;
	private Integer areaStartY;
	private Integer areaWidth;
	private Integer areaHeight;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Annotation setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public AnnotationType getType() {
		return type;
	}

	@Override
	public Annotation setType(AnnotationType type) {
		this.type = type;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Annotation setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Annotation setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public Annotation setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Long getTimeTo() {
		return timeTo;
	}

	@Override
	public Annotation setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public Integer getAreaHeight() {
		return areaHeight;
	}

	@Override
	public Annotation setAreaHeight(Integer areaHeight) {
		this.areaHeight = areaHeight;
		return this;
	}

	@Override
	public Integer getAreaWidth() {
		return areaWidth;
	}

	@Override
	public Annotation setAreaWidth(Integer areaWidth) {
		this.areaWidth = areaWidth;
		return this;
	}

	@Override
	public Integer getAreaStartX() {
		return areaStartX;
	}

	@Override
	public Annotation setAreaStartX(Integer areaStartX) {
		this.areaStartX = areaStartX;
		return this;
	}

	@Override
	public Integer getAreaStartY() {
		return areaStartY;
	}

	@Override
	public Annotation setAreaStartY(Integer areaStartY) {
		this.areaStartY = areaStartY;
		return this;
	}
}
