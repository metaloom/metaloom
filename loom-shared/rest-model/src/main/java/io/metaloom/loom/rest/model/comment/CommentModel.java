package io.metaloom.loom.rest.model.comment;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface CommentModel<T extends CommentModel<T>> extends MetaModel<T>, RestModel {

	String getTitle();

	T setTitle(String title);

	String getText();

	T setText(String text);

}
