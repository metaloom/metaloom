package io.metaloom.loom.api.notification;

/**
 * What happened, from the recipient's point of view.
 *
 * <p>
 * Persisted as a plain string against a CHECK constraint rather than a Postgres enum — see the header of {@code V2.70__add_notification.sql}. Keep
 * these names in sync with that CHECK: an unknown value is rejected by the database, not silently stored.
 * </p>
 */
public enum NotificationType {

	/** A task was assigned to you, directly or through a group you belong to. */
	TASK_ASSIGNED,

	/** A task you were responsible for was taken off you. */
	TASK_UNASSIGNED,

	/** A task you are responsible for, or created, moved to a different workflow status. */
	TASK_STATUS_CHANGED,

	/** Somebody commented on a task you created or are assigned to. */
	TASK_COMMENT,

	/** Somebody replied to a comment you wrote. */
	COMMENT_REPLY,

	/** A pipeline run you started ended in failure. */
	PIPELINE_RUN_FAILED,

	/**
	 * An ad-hoc node run you started reached a terminal status.
	 *
	 * <p>
	 * Unlike {@link #PIPELINE_RUN_FAILED} this fires on success too: an ad-hoc run is started from a
	 * chat turn or a script that has already moved on, so "it finished" is the whole point of the
	 * signal. See {@code spec/chat/AGENTIC_NODE_EXECUTION.md}.
	 * </p>
	 */
	NODE_RUN_COMPLETED;

}
