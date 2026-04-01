package io.metaloom.loom.db.jooq.dao.tag;

import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;

public class AssetTagImpl extends TagImpl implements AssetTag {

	private Long timeFrom;
	private Long timeTo;
	private Integer areaStartX;
	private Integer areaStartY;
	private Integer areaWidth;
	private Integer areaHeight;

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public Tag setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Long getTimeTo() {
		return timeTo;
	}

	@Override
	public Tag setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public Integer getAreaHeight() {
		return areaHeight;
	}

	@Override
	public Tag setAreaHeight(Integer areaHeight) {
		this.areaHeight = areaHeight;
		return this;
	}

	@Override
	public Integer getAreaWidth() {
		return areaWidth;
	}

	@Override
	public Tag setAreaWidth(Integer areaWidth) {
		this.areaWidth = areaWidth;
		return this;
	}

	@Override
	public Integer getAreaStartX() {
		return areaStartX;
	}

	@Override
	public Tag setAreaStartX(Integer areaStartX) {
		this.areaStartX = areaStartX;
		return this;
	}

	@Override
	public Integer getAreaStartY() {
		return areaStartY;
	}

	@Override
	public Tag setAreaStartY(Integer areaStartY) {
		this.areaStartY = areaStartY;
		return this;
	}
}
