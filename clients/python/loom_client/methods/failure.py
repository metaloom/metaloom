"""Problem reports. Mirrors ``io.metaloom.loom.client.common.method.FailureReportMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.failure import (
    FailureReportCreateRequest,
    FailureReportListResponse,
    FailureReportResponse,
    FailureReportUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class FailureReportMethods:
    """CRUD on ``/failure-reports``.

    A failure report is what a user says went wrong, joined to the server log by the
    ``X-Trace-Id`` of the response that failed. Submitting one needs authentication and
    no permission -- a permission to report a failure would, wherever it went ungranted,
    turn the product's only response to a breakage into a 403. Reading the inbox does
    take ``READ_FAILURE_REPORT``, because a report may carry a screenshot of whatever was
    on the reporter's screen."""

    def load_failure_report(
        self, report_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[FailureReportResponse]:
        """Load a single problem report."""
        return self._get(
            f"failure-reports/{self._uuid(report_uuid)}", FailureReportResponse
        )

    def create_failure_report(
        self, request: FailureReportCreateRequest
    ) -> LoomRequest[FailureReportResponse]:
        """Submit a problem report.

        Only ``action`` is required. A failure that produced no response -- a render
        throw, a socket that closed -- is still worth reporting, and those are the ones
        hardest to reproduce."""
        return self._post("failure-reports", request, FailureReportResponse)

    def update_failure_report(
        self, report_uuid: _uuid_mod.UUID | str, request: FailureReportUpdateRequest
    ) -> LoomRequest[FailureReportResponse]:
        """Move a report through triage. ``triage_status`` is the only editable field."""
        return self._post(
            f"failure-reports/{self._uuid(report_uuid)}", request, FailureReportResponse
        )

    def list_failure_reports(self) -> LoomRequest[FailureReportListResponse]:
        """List problem reports. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("failure-reports", FailureReportListResponse)

    def delete_failure_report(
        self, report_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a report. Its screenshot goes with it -- the row is ``ON DELETE CASCADE``."""
        return self._delete(f"failure-reports/{self._uuid(report_uuid)}")
