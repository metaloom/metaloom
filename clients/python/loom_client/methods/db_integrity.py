"""Database integrity checks. Mirrors ``io.metaloom.loom.client.common.method.DbIntegrityMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING, Optional

from ..models.dbintegrity import DbIntegrityCheckListResponse, DbIntegrityReportResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class DbIntegrityMethods:
    """Whether the database still holds the invariants the application assumes."""

    def load_db_integrity_report(
        self,
        check: Optional[str] = None,
        category: Optional[str] = None,
        severity: Optional[str] = None,
    ) -> LoomRequest[DbIntegrityReportResponse]:
        """Run the integrity checks and report the findings.

        The report covers what the schema itself cannot enforce: rows pointing at
        things that are gone through columns that carry no foreign key, audit
        timestamps that contradict each other, required names left blank, varchar
        columns holding values their Java enum does not have, and CHECK constraints
        that rows were written around.

        Computed per request; there is no stored report and no job to poll. Clean
        checks are included, so the response says what was looked at as well as what
        was found.

        :param check: a single check code, e.g. ``DANGLING_SEARCH_DOCUMENT``.
            An unknown code is rejected with 400 rather than answered with an empty
            report - a mistyped filter must not read as a clean database.
        :param category: ``DANGLING``, ``TIMESTAMP``, ``MANDATORY_FIELD``,
            ``VOCABULARY`` or ``CARDINALITY``.
        :param severity: minimum severity to include: ``INFO``, ``WARN`` or ``ERROR``.
        """
        request = self._get("db-integrity", DbIntegrityReportResponse)
        # Real query parameters: the path is percent-encoded, so an inlined "?check="
        # would reach the server as %3Fcheck= and the route would not match.
        if check is not None:
            request.param("check", check)
        if category is not None:
            request.param("category", category)
        if severity is not None:
            request.param("severity", severity)
        return request

    def load_db_integrity_checks(self) -> LoomRequest[DbIntegrityCheckListResponse]:
        """List the registered checks without running any of them.

        Useful for building a filter, or for showing what a check means next to a
        result, without paying for a full sweep.
        """
        return self._get("db-integrity/checks", DbIntegrityCheckListResponse)
