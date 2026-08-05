package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.notification.NotificationDao;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskAssignee;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.notification.NotificationEventMessage;

/**
 * Turns "something happened" into inbox rows and live frames.
 *
 * <p>
 * Lives in the rest module because it is the only place that already sees <b>both</b> {@link DaoCollection} and
 * {@link PipelineEventBroadcaster}; {@code NodeRegistryEventPublisher} in this same package is the precedent. Every arrow points one way — call sites
 * → dispatcher → {DAOs, broadcaster} — so there is no cycle and Dagger needs no new module entry.
 * </p>
 *
 * <h2>Rules that hold for every trigger</h2>
 * <ol>
 * <li><b>Persist first, broadcast second.</b> The inbox is the source of truth; the socket is an optimisation. A dropped frame must not lose the
 * row.</li>
 * <li><b>The actor is suppressed centrally</b>, here, not at the call sites. Doing it per call site re-introduces the bug on the "assign to a group
 * I am a member of" path, where the actor arrives through the fan-out rather than by name.</li>
 * <li><b>Recipients are deduplicated.</b> Somebody named directly <i>and</i> sitting in an assigned group gets one row, not two.</li>
 * <li><b>A failure here never fails the caller.</b> A notification problem must not turn a successful task assignment into a 500.</li>
 * </ol>
 */
@Singleton
public class NotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

	/**
	 * Ceiling on how many rows one event may write.
	 *
	 * <p>
	 * An oversized ACL group is a configuration problem, and it must not make the originating action fail — so past this the fan-out is truncated
	 * and logged rather than refused.
	 * </p>
	 */
	static final int MAX_FANOUT = 500;

	private final NotificationDao dao;
	private final DaoCollection daos;
	private final PipelineEventBroadcaster broadcaster;
	private final LoomModelBuilder modelBuilder;

	@Inject
	public NotificationDispatcher(NotificationDao dao, DaoCollection daos, PipelineEventBroadcaster broadcaster, LoomModelBuilder modelBuilder) {
		this.dao = dao;
		this.daos = daos;
		this.broadcaster = broadcaster;
		this.modelBuilder = modelBuilder;
	}

	// ── Triggers ──────────────────────────────────────────────────────────

	/**
	 * A task was assigned to people. Only the targets named in <b>this</b> call are notified — re-assigning an existing assignee is a no-op at the
	 * DAO layer and must not re-notify them either.
	 */
	public void taskAssigned(UUID actorUuid, Task task, List<UUID> userUuids, List<UUID> groupUuids) {
		dispatch(NotificationType.TASK_ASSIGNED, actorUuid,
			resolveTargets(userUuids, groupUuids),
			actorName(actorUuid) + " assigned you \"" + task.getTitle() + "\"",
			task.getDescription(),
			n -> n.setTaskUuid(task.getUuid()));
	}

	public void taskUnassigned(UUID actorUuid, Task task, List<UUID> userUuids, List<UUID> groupUuids) {
		dispatch(NotificationType.TASK_UNASSIGNED, actorUuid,
			resolveTargets(userUuids, groupUuids),
			actorName(actorUuid) + " unassigned you from \"" + task.getTitle() + "\"",
			null,
			n -> n.setTaskUuid(task.getUuid()));
	}

	/**
	 * A task changed workflow status. Reaches everyone responsible for it plus its creator — the person who raised the work wants to know it was
	 * accepted or rejected even if they are not doing it themselves.
	 */
	public void taskStatusChanged(UUID actorUuid, Task task, Object from, Object to) {
		dispatch(NotificationType.TASK_STATUS_CHANGED, actorUuid,
			interestedInTask(task),
			actorName(actorUuid) + " moved \"" + task.getTitle() + "\" from " + from + " to " + to,
			null,
			n -> n.setTaskUuid(task.getUuid()));
	}

	public void taskCommented(UUID actorUuid, Task task, Comment comment) {
		dispatch(NotificationType.TASK_COMMENT, actorUuid,
			interestedInTask(task),
			actorName(actorUuid) + " commented on \"" + task.getTitle() + "\"",
			comment.getText(),
			n -> {
				n.setTaskUuid(task.getUuid());
				n.setCommentUuid(comment.getUuid());
			});
	}

	/**
	 * Somebody replied to a comment. Single recipient: the author of the parent.
	 */
	public void commentReplied(UUID actorUuid, Comment parent, Comment reply) {
		if (parent.getCreatorUuid() == null) {
			return;
		}
		dispatch(NotificationType.COMMENT_REPLY, actorUuid,
			List.of(parent.getCreatorUuid()),
			actorName(actorUuid) + " replied to your comment",
			reply.getText(),
			n -> {
				n.setCommentUuid(reply.getUuid());
				n.setTaskUuid(parent.getTaskUuid());
				n.setAssetUuid(parent.getAssetUuid());
			});
	}

	/**
	 * A pipeline run failed. Machine-generated, so there is no actor and nothing is suppressed — the person who started the run is told even though
	 * they "caused" it.
	 */
	public void pipelineRunFailed(UUID starterUuid, UUID runUuid, String pipelineName, String errorMessage) {
		if (starterUuid == null) {
			return;
		}
		dispatch(NotificationType.PIPELINE_RUN_FAILED, null,
			List.of(starterUuid),
			"Pipeline run failed: " + pipelineName,
			errorMessage,
			n -> n.setPipelineRunUuid(runUuid));
	}

	// ── Core ──────────────────────────────────────────────────────────────

	/**
	 * @param actorUuid  suppressed from the recipient set; null for a machine-generated event, which suppresses nobody
	 * @param recipients raw recipients, possibly containing duplicates and the actor
	 * @param subject    fills in whichever subject reference applies
	 */
	private void dispatch(NotificationType type, UUID actorUuid, Collection<UUID> recipients, String title, String body,
		Consumer<Notification> subject) {
		try {
			Set<UUID> targets = new LinkedHashSet<>(recipients);
			// Rule 2: centrally, once. Assigning something to yourself notifies nobody.
			if (actorUuid != null) {
				targets.remove(actorUuid);
			}
			if (targets.isEmpty()) {
				return;
			}
			if (targets.size() > MAX_FANOUT) {
				log.warn("Notification fan-out for {} would reach {} recipients; truncating to {}", type, targets.size(), MAX_FANOUT);
				targets = new LinkedHashSet<>(new ArrayList<>(targets).subList(0, MAX_FANOUT));
			}

			List<Notification> batch = new ArrayList<>(targets.size());
			for (UUID recipient : targets) {
				Notification notification = dao.createNotification(recipient, actorUuid, type, title);
				notification.setBody(body);
				subject.accept(notification);
				batch.add(notification);
			}
			// Rule 1: the row lands before the frame does.
			dao.storeBatch(batch);

			for (Notification notification : batch) {
				broadcaster.broadcastNotification(notification.getRecipientUuid(), new NotificationEventMessage()
					.setType(type)
					.setNotification(modelBuilder.toResponse(notification))
					.setUnreadCount(dao.countUnread(notification.getRecipientUuid())));
			}
		} catch (Exception e) {
			// Rule 4. RunStatsAggregator already exercises an exploding broadcaster, so this
			// defensiveness has precedent in the codebase.
			log.error("Failed to dispatch {} notification", type, e);
		}
	}

	/**
	 * Expand assignment targets into concrete users: those named directly, plus the current members of every named group.
	 */
	private List<UUID> resolveTargets(List<UUID> userUuids, List<UUID> groupUuids) {
		List<UUID> resolved = new ArrayList<>();
		if (userUuids != null) {
			resolved.addAll(userUuids);
		}
		if (groupUuids != null) {
			for (UUID groupUuid : groupUuids) {
				daos.groupDao().loadUsersForGroup(groupUuid).forEach(user -> resolved.add(user.getUuid()));
			}
		}
		return resolved;
	}

	/**
	 * Everyone who should hear about a change to a task: its current assignees (groups expanded) plus whoever created it.
	 */
	private List<UUID> interestedInTask(Task task) {
		List<UUID> interested = new ArrayList<>(daos.taskDao().loadAssignedUserUuids(task.getUuid()));
		if (task.getCreatorUuid() != null) {
			interested.add(task.getCreatorUuid());
		}
		return interested;
	}

	/**
	 * A display name for the actor, for the pre-rendered title. "Someone" covers both a machine-generated event and an actor who has since been
	 * deleted — the title is frozen at dispatch, so it cannot go stale later.
	 */
	private String actorName(UUID actorUuid) {
		if (actorUuid == null) {
			return "Someone";
		}
		User actor = daos.userDao().load(actorUuid);
		return actor == null ? "Someone" : actor.getUsername();
	}

	/** Convenience for a call site that already holds the assignee rows. */
	static List<UUID> userUuidsOf(List<TaskAssignee> assignees) {
		return assignees.stream().filter(a -> !a.isGroupAssignment()).map(TaskAssignee::getUserUuid).toList();
	}

}
