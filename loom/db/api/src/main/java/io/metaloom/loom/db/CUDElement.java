package io.metaloom.loom.db;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.metaloom.loom.db.model.user.User;

public interface CUDElement<SELF extends CUDElement<SELF>> extends Element<SELF>, MetaElement<SELF> {

	UUID getEditorUuid();

	default SELF setEditor(User editor) {
		Objects.requireNonNull(editor, "The editor is null");
		return setEditorUuid(editor.getUuid());
	}

	SELF setEditorUuid(UUID editorUuid);

	UUID getCreatorUuid();

	default SELF setCreator(User creator) {
		Objects.requireNonNull(creator, "The creator is null");
		return setCreatorUuid(creator.getUuid());
	}

	SELF setCreatorUuid(UUID uuid);

	Instant getEdited();

	SELF setEdited(Instant edate);

	Instant getCreated();

	SELF setCreated(Instant cdate);
}
