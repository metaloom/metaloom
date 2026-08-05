package io.metaloom.loom.rest.model.notification;

import java.time.Instant;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface NotificationExamples extends ExampleValues {

	default Example notificationResponseExample() {
		return new ExampleImpl(notificationResponse(), "The notification response", HttpResponseStatus.OK);
	}

	default Example notificationListResponseExample() {
		return new ExampleImpl(notificationListResponse(), "The notification list response", HttpResponseStatus.OK);
	}

	default Example notificationUpdateRequestExample() {
		return new ExampleImpl(notificationUpdateRequest(), "The notification update request", HttpResponseStatus.OK);
	}

	default NotificationResponse notificationResponse() {
		NotificationResponse model = new NotificationResponse();
		model.setUuid(uuidA());
		model.setType(NotificationType.TASK_ASSIGNED);
		model.setTitle("joedoe assigned you \"Colour-grade the hero shot\"");
		model.setBody("The white balance drifts warm in the top-left quadrant.");
		model.setRead(false);
		model.setTaskUuid(uuidC());
		model.setMeta(meta());
		return model;
	}

	default NotificationListResponse notificationListResponse() {
		NotificationListResponse model = new NotificationListResponse();
		model.add(notificationResponse());
		model.setUnreadCount(1);
		model.setMetainfo(pagingInfo());
		return model;
	}

	default Example notificationMarkAllReadResponseExample() {
		return new ExampleImpl(new GenericMessageResponse().setMessage("Marked 3 notifications as read"),
			"The mark-all-read response", HttpResponseStatus.OK);
	}

	default NotificationUpdateRequest notificationUpdateRequest() {
		NotificationUpdateRequest model = new NotificationUpdateRequest();
		model.setRead(true);
		return model;
	}
}
