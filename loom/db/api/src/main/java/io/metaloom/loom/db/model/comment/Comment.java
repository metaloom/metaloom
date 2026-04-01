package io.metaloom.loom.db.model.comment;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Comment extends CUDElement<Comment>, MetaElement<Comment> {

	String getTitle();

	Comment setTitle(String title);

	String getText();

	Comment setText(String text);

	UUID getTaskUuid();

	Comment setTaskUuid(UUID taskUuid);

}
