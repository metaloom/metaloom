package io.metaloom.loom.db.model.tag;

public interface AssetTag extends Tag {

	Integer getAreaStartY();

	Tag setAreaStartY(Integer areaStartY);

	Integer getAreaStartX();

	Tag setAreaStartX(Integer areaStartX);

	Integer getAreaWidth();

	Tag setAreaWidth(Integer areaWidth);

	Integer getAreaHeight();

	Tag setAreaHeight(Integer areaHeight);

	Long getTimeTo();

	Tag setTimeTo(Long timeTo);

	Long getTimeFrom();

	Tag setTimeFrom(Long timeFrom);
}
