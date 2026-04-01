package io.metaloom.loom.db.model.task;

import io.metaloom.loom.db.CUDElement;

public interface Task extends CUDElement<Task> {

	String getTitle();

	Task setTitle(String title);

	String getDescription();

	Task setDescription(String description);

}
