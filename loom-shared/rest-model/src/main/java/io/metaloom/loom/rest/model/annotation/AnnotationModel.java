package io.metaloom.loom.rest.model.annotation;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface AnnotationModel<T extends AnnotationModel<T>> extends RestModel, MetaModel<T> {

	AnnotationType getType();

	T setType(AnnotationType type);

	String getDescription();

	T setDescription(String description);

	String getTitle();

	T setTitle(String title);

	AreaInfo getArea();

	T setArea(AreaInfo area);

	UUID getAssetUuid();

	T setAssetUuid(UUID assetUuid);

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
