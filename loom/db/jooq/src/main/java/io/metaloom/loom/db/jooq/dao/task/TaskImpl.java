package io.metaloom.loom.db.jooq.dao.task;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.task.Task;

public class TaskImpl extends AbstractEditableElement<Task> implements Task {

	private String title;

	private String description;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Task setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Task setDescription(String description) {
		this.description = description;
		return this;
	}
}
