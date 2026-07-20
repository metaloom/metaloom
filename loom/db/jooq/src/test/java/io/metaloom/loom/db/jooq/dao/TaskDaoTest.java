package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.api.task.TaskPriority;
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
		assertEquals(TaskPriority.MEDIUM, createdElement.getPriority(), "New tasks should default to MEDIUM priority");
	}

	@Override
	public void assertUpdate(Task updatedTask) {
		assertEquals("updated_title", updatedTask.getTitle());
		assertEquals(TaskPriority.CRITICAL, updatedTask.getPriority(), "The updated priority should round-trip through the DB");
	}

	@Override
	public void updateElement(Task task) {
		task.setTitle("updated_title");
		task.setPriority(TaskPriority.CRITICAL);
	}

}
