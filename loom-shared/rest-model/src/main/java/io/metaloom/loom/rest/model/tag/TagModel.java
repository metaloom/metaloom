package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;

public interface TagModel<T extends TagModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	String getCollection();

	T setCollection(String collection);

	AreaInfo getArea();

	T setArea(AreaInfo area);

	String getColor();

	T setColor(String color);

	@JsonIgnore
	default T setArea(long from, long to) {
		return setArea(new AreaInfo().setFrom(from).setTo(to));
	}

	@JsonIgnore
	default T setArea(int startX, int startY, int width, int height) {
		return setArea(new AreaInfo().setStartX(startX).setStartY(startY).setWidth(width).setHeight(height));
	}

	@JsonIgnore
	default T setArea(long from, long to, int startX, int startY, int width, int height) {
		return setArea(new AreaInfo().setFrom(from).setTo(to).setStartX(startX).setStartY(startY).setWidth(width).setHeight(height));
	}
}
