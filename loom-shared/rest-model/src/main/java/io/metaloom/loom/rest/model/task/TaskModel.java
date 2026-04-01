package io.metaloom.loom.rest.model.task;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface TaskModel<T extends TaskModel<T>> extends MetaModel<T>, RestModel {

	String getTitle();

	T setTitle(String title);

	String getDescription();

	T setDescription(String description);

	TaskPriority getPriority();

	T setPriority(TaskPriority priority);

}
