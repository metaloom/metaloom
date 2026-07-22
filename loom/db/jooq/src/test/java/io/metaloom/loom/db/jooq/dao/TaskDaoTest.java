package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public class TaskDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<TaskDao, Task> {

	private static final Instant DUE_DATE = Instant.parse("2026-08-01T12:00:00Z");

	private static Stream<Task> stream(Page<Task> page) {
		return StreamSupport.stream(page.spliterator(), false);
	}

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
		assertEquals(TaskStatus.PENDING, createdElement.getStatus(), "New tasks should default to PENDING status");
	}

	@Override
	public void assertUpdate(Task updatedTask) {
		assertEquals("updated_title", updatedTask.getTitle());
		assertEquals(TaskPriority.CRITICAL, updatedTask.getPriority(), "The updated priority should round-trip through the DB");
		assertEquals(TaskStatus.ACCEPTED, updatedTask.getStatus(), "The updated status should round-trip through the DB");
		assertEquals(DUE_DATE, updatedTask.getDueDate(), "The updated due date should round-trip through the DB");
	}

	@Override
	public void updateElement(Task task) {
		task.setTitle("updated_title");
		task.setPriority(TaskPriority.CRITICAL);
		task.setStatus(TaskStatus.ACCEPTED);
		task.setDueDate(DUE_DATE);
	}

	@Test
	public void testDeleteCascadesCommentSubtree() {
		User user = dummyUser();

		// Task 1 with a comment tree: root -> reply -> subReply
		Task task1 = getDao().createTask(user, "task_with_comments");
		getDao().store(task1);
		Comment root = commentOnTask(user, task1, "root comment");
		Comment reply = replyTo(user, root, "first level reply");
		Comment subReply = replyTo(user, reply, "second level reply");

		// Task 2 with its own comment tree — must not be affected
		Task task2 = getDao().createTask(user, "unaffected_task");
		getDao().store(task2);
		Comment otherRoot = commentOnTask(user, task2, "other root comment");
		Comment otherReply = replyTo(user, otherRoot, "other reply");

		getDao().delete(task1);

		assertNull(getDao().load(task1.getUuid()), "The task should be deleted");
		assertNull(commentDao().load(root.getUuid()), "The root comment of the task should be cascade-deleted");
		assertNull(commentDao().load(reply.getUuid()), "The first level reply should be cascade-deleted");
		assertNull(commentDao().load(subReply.getUuid()), "The second level reply should be cascade-deleted");

		assertNotNull(getDao().load(task2.getUuid()), "The other task must not be affected");
		assertNotNull(commentDao().load(otherRoot.getUuid()), "The other task's root comment must not be affected");
		assertNotNull(commentDao().load(otherReply.getUuid()), "The other task's reply must not be affected");
	}

	private Comment commentOnTask(User user, Task task, String text) {
		Comment comment = commentDao().createCommentForTask(user.getUuid(), task.getUuid(), "title", text);
		commentDao().store(comment);
		return comment;
	}

	private Comment replyTo(User user, Comment parent, String text) {
		Comment comment = commentDao().createComment(user, "title", text);
		// Replies only reference their parent comment — the subtree must die with
		// the root via the comment -> comment cascade, not via the task FK.
		comment.setParentUuid(parent.getUuid());
		commentDao().store(comment);
		return comment;
	}

	@Test
	public void testAssignToAsset() {
		User user = dummyUser();
		Task task = getDao().createTask(user, "asset_task");
		getDao().store(task);

		getDao().assignToAsset(task.getUuid(), ASSET_UUID);
		// Assigning twice must be idempotent
		getDao().assignToAsset(task.getUuid(), ASSET_UUID);

		Page<Task> page = getDao().loadPageForAsset(ASSET_UUID, null, 10, null, null, null);
		assertTrue(stream(page).anyMatch(t -> t.getUuid().equals(task.getUuid())), "The assigned task should be listed for the asset");

		getDao().unassignFromAsset(task.getUuid(), ASSET_UUID);
		page = getDao().loadPageForAsset(ASSET_UUID, null, 10, null, null, null);
		assertTrue(stream(page).noneMatch(t -> t.getUuid().equals(task.getUuid())), "The unassigned task should no longer be listed");
	}

	@Test
	public void testAssignToAnnotation() {
		User user = dummyUser();
		Task task = getDao().createTask(user, "annotation_task");
		getDao().store(task);

		getDao().assignToAnnotation(task.getUuid(), ANNOTATION_UUID);
		// Assigning twice must be idempotent
		getDao().assignToAnnotation(task.getUuid(), ANNOTATION_UUID);

		Page<Task> page = getDao().loadPageForAnnotation(ANNOTATION_UUID, null, 10, null, null, null);
		assertTrue(stream(page).anyMatch(t -> t.getUuid().equals(task.getUuid())), "The assigned task should be listed for the annotation");
		assertTrue(getDao().loadForAnnotation(ANNOTATION_UUID).stream().anyMatch(t -> t.getUuid().equals(task.getUuid())),
			"loadForAnnotation should also return the assigned task");

		getDao().unassignFromAnnotation(task.getUuid(), ANNOTATION_UUID);
		page = getDao().loadPageForAnnotation(ANNOTATION_UUID, null, 10, null, null, null);
		assertTrue(stream(page).noneMatch(t -> t.getUuid().equals(task.getUuid())), "The unassigned task should no longer be listed");
	}

}
