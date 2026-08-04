"""Pipelines. Mirrors ``io.metaloom.loom.client.common.method.PipelineMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING
from urllib.parse import quote as _quote

from ..models.base import GenericMessageResponse
from ..models.pipeline import (
    PipelineBreakpointRequest,
    PipelineBreakpointResponse,
    PipelineCreateRequest,
    PipelineListResponse,
    PipelineNodeReExecuteRequest,
    PipelineNodeReExecuteResponse,
    PipelineNodeTaskListResponse,
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

    def list_pipeline_run_item_tasks(
        self,
        pipeline_uuid: _uuid_mod.UUID | str,
        run_uuid: _uuid_mod.UUID | str,
        item_uuid: _uuid_mod.UUID | str,
    ) -> LoomRequest[PipelineNodeTaskListResponse]:
        """List the node executions of a single run item, with the outputs each node emitted.

        The finest granularity the engine records: one entry per graph node, plus one per
        element for a node downstream of a MANY output. Outputs are keyed by output port id.
        """
        return self._get(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}"
            f"/items/{self._uuid(item_uuid)}/tasks",
            PipelineNodeTaskListResponse,
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

    # -- breakpoints -----------------------------------------------------------
    #
    # Run state, not definition state: a breakpoint is set on a run that is already
    # going and is never written back into the stored pipeline.

    def load_pipeline_run_breakpoints(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[PipelineBreakpointResponse]:
        """Load the nodes a run halts at, and the executions it is currently holding.

        ``node_ids`` is what was armed; ``held`` is what has actually stopped so far.
        Neither implies the other -- a breakpoint no item has reached yet is armed and
        holding nothing.
        """
        return self._get(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/breakpoints",
            PipelineBreakpointResponse,
        )

    def set_pipeline_run_breakpoints(
        self,
        pipeline_uuid: _uuid_mod.UUID | str,
        run_uuid: _uuid_mod.UUID | str,
        request: PipelineBreakpointRequest,
    ) -> LoomRequest[PipelineBreakpointResponse]:
        """Replace the set of nodes a run halts at.

        A whole-set replacement, so sending the same request twice leaves the run in the
        same state. An empty list disarms everything and releases whatever was held.
        """
        return self._put(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/breakpoints",
            request,
            PipelineBreakpointResponse,
        )

    def continue_pipeline_run_breakpoint(
        self,
        pipeline_uuid: _uuid_mod.UUID | str,
        run_uuid: _uuid_mod.UUID | str,
        node_id: str,
    ) -> LoomRequest[GenericMessageResponse]:
        """Release every execution one node is holding. Takes no body.

        The breakpoint stays armed, so the next item reaching that node stops too.
        """
        return self._post_empty(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}"
            f"/breakpoints/{_quote(node_id)}/continue",
            GenericMessageResponse,
        )

    def step_pipeline_run(
        self, pipeline_uuid: _uuid_mod.UUID | str, run_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[PipelineBreakpointResponse]:
        """Release exactly one held execution. Takes no body.

        Fails with 409 when the run is not holding anything -- a step that quietly did
        nothing would look identical to one that advanced the run.
        """
        return self._post_empty(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}/steps",
            PipelineBreakpointResponse,
        )

    def re_execute_pipeline_run_node(
        self,
        pipeline_uuid: _uuid_mod.UUID | str,
        run_uuid: _uuid_mod.UUID | str,
        node_id: str,
        request: PipelineNodeReExecuteRequest,
    ) -> LoomRequest[PipelineNodeReExecuteResponse]:
        """Run a node held at a breakpoint again over the same input.

        Settings given here apply to this run only and never touch the stored pipeline;
        keeping one is a separate act through ``update_pipeline``. Each attempt keeps its
        own task row, so a result can be compared with the one it replaced rather than
        overwriting it.

        Fails with 409 when that execution is not held -- only a held execution may be
        re-run, because a hold is what guarantees nothing downstream has consumed the
        result being discarded.
        """
        return self._post(
            f"pipelines/{self._uuid(pipeline_uuid)}/runs/{self._uuid(run_uuid)}"
            f"/nodes/{_quote(node_id)}/reexecutions",
            request,
            PipelineNodeReExecuteResponse,
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
