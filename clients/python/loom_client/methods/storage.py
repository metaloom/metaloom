"""Storage report. Mirrors ``io.metaloom.loom.client.common.method.StorageMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..models.storage import StorageBackendListResponse, StorageReportResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class StorageMethods:
    """What is stored, per kind of content, and how much room is left."""

    def load_storage_report(self) -> LoomRequest[StorageReportResponse]:
        """Report what is stored and how full every storage backend is.

        Two byte figures per category, and they answer different questions.
        ``logical_bytes`` is what the catalogue claims -- add up every element -- and is
        what a quota would be charged against. ``distinct_bytes`` is what the disk holds,
        because storage is content-addressed and identical content is stored once however
        many elements point at it.

        Per-category ``distinct_bytes`` does **not** sum to the report's own
        ``distinct_bytes``: one stored object can belong to two categories, because
        copying a face crop into a person's gallery deliberately shares the bytes. The
        report's figure is a separate query and is the physical one.

        Several aggregate scans over the attachment and asset tables. Not something to
        poll -- use :meth:`load_storage_backends` for that.
        """
        return self._get("storage", StorageReportResponse)

    def load_storage_backends(self) -> LoomRequest[StorageBackendListResponse]:
        """List the storage backends and how full each one is.

        Cheap: one capacity probe per backend and no table scans.

        ``free_bytes`` and ``total_bytes`` are ``None`` for an object store, which has no
        capacity to report -- which is why its ``watermark`` is ``UNKNOWN`` rather than
        ``OK``. A bucket is not known to be healthy, it is unmeasurable.
        """
        return self._get("storage/backends", StorageBackendListResponse)
