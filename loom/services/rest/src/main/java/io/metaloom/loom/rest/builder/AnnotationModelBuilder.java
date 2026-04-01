package io.metaloom.loom.rest.builder;

import java.util.List;
import java.util.stream.Collectors;

import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.annotation.AnnotationListResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.task.TaskResponse;

public interface AnnotationModelBuilder extends ModelBuilder, UserModelBuilder, TaskModelBuilder {

	default AnnotationResponse toResponse(Annotation annotation) {
		AnnotationResponse response = new AnnotationResponse();
		response.setUuid(annotation.getUuid());
		response.setAssetUuid(annotation.getAssetUuid());
		response.setTitle(annotation.getTitle());
		response.setMeta(annotation.getMeta());
		response.setDescription(annotation.getDescription());
		response.setArea(annotationArea(annotation));

		List<Task> tasks = daos().taskDao().loadForAnnotation(annotation.getUuid());
		List<TaskResponse> restTasks = tasks.stream().map(this::toResponse).collect(Collectors.toList());
		response.setTasks(restTasks);

		setStatus(annotation, response);
		return response;
	}

	default AreaInfo annotationArea(Annotation annotation) {
		AreaInfo area = new AreaInfo();
		area.setFrom(annotation.getTimeFrom());
		area.setTo(annotation.getTimeTo());
		area.setHeight(annotation.getAreaHeight());
		area.setWidth(annotation.getAreaWidth());
		area.setStartX(annotation.getAreaStartX());
		area.setStartY(annotation.getAreaStartY());
		return area;
	}

	default AnnotationListResponse toAnnotationList(Page<Annotation> page) {
		return setPage(new AnnotationListResponse(), page, this::toResponse);
	}

}
