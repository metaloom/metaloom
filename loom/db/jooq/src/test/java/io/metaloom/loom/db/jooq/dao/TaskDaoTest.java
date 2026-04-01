package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.user.User;

public class TaskDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<TaskDao, Task> {

	@Override
	public TaskDao getDao() {
		return taskDao();
	}

	@Override
	public Task createElement(User user, int i) {
		return getDao().createTask(user, "task_" + i);
	}

	@Override
	public void assertCreate(Task createdElement) {
		assertEquals("task_0", createdElement.getTitle());
	}

	@Override
	public void assertUpdate(Task updatedTask) {
		assertEquals("updated_title", updatedTask.getTitle());
	}

	@Override
	public void updateElement(Task task) {
		task.setTitle("updated_title");
	}

}
