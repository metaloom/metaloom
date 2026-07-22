package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;
import io.metaloom.utils.hash.SHA512;

public interface TaskMethods {

	LoomClientRequest<TaskResponse> loadTask(UUID taskUuid);

	LoomClientRequest<TaskResponse> createTask(TaskCreateRequest request);

	LoomClientRequest<TaskResponse> updateTask(UUID taskUuid, TaskUpdateRequest request);

	LoomClientRequest<TaskListResponse> listTasks();

	LoomClientRequest<NoResponse> deleteTask(UUID taskUuid);

	// TASK - ASSET

	LoomClientRequest<TaskListResponse> listAssetTasks(AssetId assetId);

	default LoomClientRequest<TaskListResponse> listAssetTasks(SHA512 assetHash) {
		return listAssetTasks(assetId(assetHash));
	}

	default LoomClientRequest<TaskListResponse> listAssetTasks(UUID assetUuid) {
		return listAssetTasks(assetId(assetUuid));
	}

	LoomClientRequest<TaskResponse> assignTaskToAsset(AssetId assetId, UUID taskUuid);

	default LoomClientRequest<TaskResponse> assignTaskToAsset(SHA512 assetHash, UUID taskUuid) {
		return assignTaskToAsset(assetId(assetHash), taskUuid);
	}

	default LoomClientRequest<TaskResponse> assignTaskToAsset(UUID assetUuid, UUID taskUuid) {
		return assignTaskToAsset(assetId(assetUuid), taskUuid);
	}

	LoomClientRequest<NoResponse> unassignTaskFromAsset(AssetId assetId, UUID taskUuid);

	default LoomClientRequest<NoResponse> unassignTaskFromAsset(SHA512 assetHash, UUID taskUuid) {
		return unassignTaskFromAsset(assetId(assetHash), taskUuid);
	}

	default LoomClientRequest<NoResponse> unassignTaskFromAsset(UUID assetUuid, UUID taskUuid) {
		return unassignTaskFromAsset(assetId(assetUuid), taskUuid);
	}

	// TASK - ANNOTATION

	LoomClientRequest<TaskListResponse> listAnnotationTasks(UUID annotationUuid);

	LoomClientRequest<TaskResponse> assignTaskToAnnotation(UUID annotationUuid, UUID taskUuid);

	LoomClientRequest<NoResponse> unassignTaskFromAnnotation(UUID annotationUuid, UUID taskUuid);

}
