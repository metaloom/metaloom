"""Pipelines. Mirrors ``io.metaloom.loom.client.common.method.PipelineMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.base import GenericMessageResponse
from ..models.pipeline import (
    PipelineCreateRequest,
    PipelineListResponse,
    PipelineResponse,
    PipelineRunItemListResponse,
    PipelineRunListResponse,
    PipelineRunRecord,
    PipelineRunRequest,
    PipelineRunResponse,
    PipelineRunStatsResponse,
    PipelineUpdateRequest,
    PipelineVersionListResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class PipelineMethods:
    """Pipeline definitions, runs and versions under ``/pipelines``.

    A pipeline is a stored graph of processing nodes. Starting one creates a *run*,
    which can be paused, resumed or cancelled while it works through its items. Every
    edit to the definition produces a new *version*, and an old version can be restored.

    Version numbers are integers, not UUIDs -- ``/pipelines/{uuid}/versions/3``.
    """

    # -- definitions ---------------------------------------------------------

    def load_pipeline(self, pipeline_uuid: _uuid_mod.UUID | str) -> LoomRequest[PipelineResponse]:
        """Load a single pipeline definition."""
        return self._get(f"pipelines/{self._uuid(pipeline_uuid)}", PipelineResponse)

    def create_pipeline(self, request: PipelineCreateRequest) -> LoomRequest[PipelineResponse]:
        """Create a pipeline definition."""
        return self._post("pipelines", request, PipelineResponse)

    def update_pipeline(
        self, pipeline_uuid: _uuid_mod.UUID | str, request: PipelineUpdateRequest
    ) -> LoomRequest[PipelineResponse]:
        """Update a pipeline definition. This creates a new version."""
        return self._post(f"pipelines/{self._uuid(pipeline_uuid)}", request, PipelineResponse)

    def list_pipelines(self) -> LoomRequest[PipelineListResponse]:
        """List pipelines. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("pipelines", PipelineListResponse)

    def delete_pipeline(self, pipeline_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a pipeline definition. Answers 204 with no body."""
        return self._delete(f"pipelines/{self._uuid(pipeline_uuid)}")

    # -- runs ------------------------------------------------------------------

    def run_pipeline(
        self, pipeline_uuid: _uuid_mod.UUID | str, request: PipelineRunRequest
    ) -> LoomRequest[PipelineRunResponse]:
        """Start a run and return its handle.

        Answers 503 when no processor accepts the pipeline's source node kind -- that
        means nothing in the fleet can do the work, not that the server is broken.
        """
        return self._post(f"pipelines/{self._uuid(pipeline_uuid)}/run", request, PipelineRunResponse)

    def load_pipeline_run(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[PipelineRunRecord]:
        """Load a single run."""
        return self._get(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}", PipelineRunRecord
        )

    def list_pipeline_runs(self, pipeline_uuid: _uuid_mod.UUID | str) -> LoomRequest[PipelineRunListResponse]:
        """List a pipeline's runs."""
        return self._get(f"pipelines/{self._uuid(pipeline_uuid)}/runs", PipelineRunListResponse)

    def list_pipeline_run_items(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[PipelineRunItemListResponse]:
        """List the individual items a run processed."""
        return self._get(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/items",
            PipelineRunItemListResponse,
        )

    def load_pipeline_run_stats(self) -> LoomRequest[PipelineRunStatsResponse]:
        """Load aggregate run statistics across all pipelines."""
        return self._get("pipelines/runs/stats", PipelineRunStatsResponse)

    def pause_pipeline_run(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[GenericMessageResponse]:
        """Pause a running pipeline. Takes no body."""
        return self._post_empty(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/pause",
            GenericMessageResponse,
        )

    def resume_pipeline_run(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[GenericMessageResponse]:
        """Resume a paused pipeline. Takes no body."""
        return self._post_empty(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/resume",
            GenericMessageResponse,
        )

    def cancel_pipeline_run(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[GenericMessageResponse]:
        """Cancel a run. Takes no body."""
        return self._post_empty(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/cancel",
            GenericMessageResponse,
        )

    # -- versions --------------------------------------------------------------

    def list_pipeline_versions(
        self, pipeline_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[PipelineVersionListResponse]:
        """List a pipeline's stored versions."""
        return self._get(f"pipelines/{self._uuid(pipeline_uuid)}/versions", PipelineVersionListResponse)

    def load_pipeline_version(
        self, pipeline_uuid: _uuid_mod.UUID | str, version: int
    ) -> LoomRequest[PipelineResponse]:
        """Load one stored version of a pipeline definition."""
        return self._get(f"pipelines/{self._uuid(pipeline_uuid)}/versions/{int(version)}", PipelineResponse)

    def restore_pipeline_version(
        self, pipeline_uuid: _uuid_mod.UUID | str, version: int, request=None
    ) -> LoomRequest[PipelineResponse]:
        """Restore a stored version, making it current.

        Args:
            pipeline_uuid: The pipeline to restore.
            version: The version number to restore.
            request: Optional ``PipelineVersionRestoreRequest``; the route also accepts
                an empty body.
        """
        path = f"pipelines/{self._uuid(pipeline_uuid)}/versions/{int(version)}/restore"
        if request is None:
            return self._post_empty(path, PipelineResponse)
        return self._post(path, request, PipelineResponse)
