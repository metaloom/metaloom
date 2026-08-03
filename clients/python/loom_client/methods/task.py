"""Tasks. Mirrors ``io.metaloom.loom.client.common.method.TaskMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.task import TaskCreateRequest, TaskListResponse, TaskResponse, TaskUpdateRequest

if TYPE_CHECKING:
    from ..request import LoomRequest


class TaskMethods:
    """Work items under ``/tasks``, and their assignment to assets and annotations.

    A task is a unit of human work -- review this, tag that. It exists independently
    and is then assigned to one or more elements, which is why the assignment routes
    are nested under the element rather than under the task.

    Assignment carries no payload: the path says everything, so the POST has an empty
    body.
    """

    def load_task(self, task_uuid: _uuid_mod.UUID | str) -> LoomRequest[TaskResponse]:
        """Load a single task."""
        return self._get(f"tasks/{self._uuid(task_uuid)}", TaskResponse)

    def create_task(self, request: TaskCreateRequest) -> LoomRequest[TaskResponse]:
        """Create a task."""
        return self._post("tasks", request, TaskResponse)

    def update_task(
        self, task_uuid: _uuid_mod.UUID | str, request: TaskUpdateRequest
    ) -> LoomRequest[TaskResponse]:
        """Update a task. Only the fields you set are changed."""
        return self._post(f"tasks/{self._uuid(task_uuid)}", request, TaskResponse)

    def list_tasks(self) -> LoomRequest[TaskListResponse]:
        """List tasks. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("tasks", TaskListResponse)

    def delete_task(self, task_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a task. Answers 204 with no body."""
        return self._delete(f"tasks/{self._uuid(task_uuid)}")

    # -- assignment to assets ----------------------------------------------

    def list_asset_tasks(self, asset_id: AssetId | _uuid_mod.UUID | str) -> LoomRequest[TaskListResponse]:
        """List the tasks assigned to an asset."""
        return self._get(f"{self._asset_sub(asset_id)}/tasks", TaskListResponse)

    def assign_task_to_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, task_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[TaskResponse]:
        """Assign an existing task to an asset."""
        return self._post_empty(f"{self._asset_sub(asset_id)}/tasks/{self._uuid(task_uuid)}", TaskResponse)

    def unassign_task_from_asset(
        self, asset_id: AssetId | _uuid_mod.UUID | str, task_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Unassign a task from an asset. The task itself is not deleted."""
        return self._delete(f"{self._asset_sub(asset_id)}/tasks/{self._uuid(task_uuid)}")

    # -- assignment to annotations -----------------------------------------

    def list_annotation_tasks(self, annotation_uuid: _uuid_mod.UUID | str) -> LoomRequest[TaskListResponse]:
        """List the tasks assigned to an annotation."""
        return self._get(f"annotations/{self._uuid(annotation_uuid)}/tasks", TaskListResponse)

    def assign_task_to_annotation(
        self, annotation_uuid: _uuid_mod.UUID | str, task_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[TaskResponse]:
        """Assign an existing task to an annotation."""
        return self._post_empty(
            f"annotations/{self._uuid(annotation_uuid)}/tasks/{self._uuid(task_uuid)}",
            TaskResponse,
        )

    def unassign_task_from_annotation(
        self, annotation_uuid: _uuid_mod.UUID | str, task_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Unassign a task from an annotation. The task itself is not deleted."""
        return self._delete(f"annotations/{self._uuid(annotation_uuid)}/tasks/{self._uuid(task_uuid)}")
