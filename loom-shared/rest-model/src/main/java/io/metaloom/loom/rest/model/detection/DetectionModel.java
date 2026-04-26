package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface DetectionModel<T extends DetectionModel<T>> extends MetaModel<T>, RestModel {

	String getType();

	T setType(String type);

	Integer getFrameNumber();

	T setFrameNumber(Integer frameNumber);

	Float getBboxX();

	T setBboxX(Float bboxX);

	Float getBboxY();

	T setBboxY(Float bboxY);

	Float getBboxWidth();

	T setBboxWidth(Float bboxWidth);

	Float getBboxHeight();

	T setBboxHeight(Float bboxHeight);

	Float getConfidence();

	T setConfidence(Float confidence);

}
