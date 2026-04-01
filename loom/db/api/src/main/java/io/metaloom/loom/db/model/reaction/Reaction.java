package io.metaloom.loom.db.model.reaction;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Reaction extends CUDElement<Reaction>, MetaElement<Reaction> {

	String getType();

	Reaction setType(String type);

	Integer getRating();

	Reaction setRating(Integer rating);

	// References

	UUID getAssetUuid();

	Reaction setAssetUuid(UUID assetUuid);

	UUID getCommentUuid();

	Reaction setCommentUuid(UUID commentUuid);

	UUID getAnnotationUuid();

	Reaction setAnnotationUuid(UUID annotationUuid);

	UUID getTaskUuid();

	Reaction setTaskUuid(UUID taskUuid);

}
