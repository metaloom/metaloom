package io.metaloom.loom.db.model.detection;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Detection extends CUDElement<Detection>, MetaElement<Detection> {

	String getType();

	Detection setType(String type);

	Integer getFrameNumber();

	Detection setFrameNumber(Integer frameNumber);

	Float getBboxX();

	Detection setBboxX(Float bboxX);

	Float getBboxY();

	Detection setBboxY(Float bboxY);

	Float getBboxWidth();

	Detection setBboxWidth(Float bboxWidth);

	Float getBboxHeight();

	Detection setBboxHeight(Float bboxHeight);

	Float getConfidence();

	Detection setConfidence(Float confidence);

	UUID getAssetUuid();

	Detection setAssetUuid(UUID assetUuid);

}
