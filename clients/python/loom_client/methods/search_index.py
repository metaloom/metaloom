"""Search index admin. Mirrors ``io.metaloom.loom.client.common.method.SearchIndexMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.searchindex import (
    IndexJobCreateRequest,
    IndexJobListResponse,
    IndexJobResponse,
    SearchIndexListResponse,
    SearchIndexResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class SearchIndexMethods:
    """Operating the lexical, embedding-vector and fingerprint indices.

    Supersedes :meth:`~loom_client.methods.similarity.SimilarityMethods.rebuild_similarity_index`,
    which rebuilt synchronously and reported nothing while it ran. Here the work is a
    job: the create call returns immediately and the job is polled for progress.
    """

    def list_search_indices(self) -> LoomRequest[SearchIndexListResponse]:
        """List every search index, plus the storage backends they live in.

        ``documentCount`` (what the database holds) and ``indexedCount`` (what the index
        holds) are both reported because their disagreement is the whole point: fewer
        indexed means a backlog, more means orphans a delta sync would remove.

        Size on disk is on the backend, not the index -- one Lucene directory holds
        every vector space at once, so a per-space byte figure does not exist.
        """
        return self._get("search-indices", SearchIndexListResponse)

    def load_search_index(self, index_id: str) -> LoomRequest[SearchIndexResponse]:
        """Read one index.

        :param index_id: an ``id`` from :meth:`list_search_indices`, e.g. ``lexical`` or
            ``vector-face-inspireface-r18-512``. Do not build these by hand -- they are
            slugs of a model name and the server resolves them by lookup, not by parsing.
        """
        return self._get(f"search-indices/{index_id}", SearchIndexResponse)

    def list_search_index_jobs(self, index_id: str) -> LoomRequest[IndexJobListResponse]:
        """Recent maintenance jobs for one index, newest first, including the running one.

        Jobs are held in memory only, so a server restart empties this.
        """
        return self._get(f"search-indices/{index_id}/jobs", IndexJobListResponse)

    def create_search_index_job(self, index_id: str, request: IndexJobCreateRequest) -> LoomRequest[IndexJobResponse]:
        """Start a maintenance job. Answers 202 with the job to poll.

        :param request: the action -- ``REINDEX``, ``DELTA_SYNC`` or ``DROP``. It must be
            one of the index's ``supportedActions``; anything else is a 400 naming what
            the index does accept. ``DROP`` on the lexical index is refused for that
            reason rather than merely hidden in the UI.
        """
        return self._post(f"search-indices/{index_id}/jobs", request, IndexJobResponse)

    def load_search_index_job(self, index_id: str, job_uuid: _uuid_mod.UUID | str) -> LoomRequest[IndexJobResponse]:
        """Read one job and how far it has got.

        ``total`` is ``None`` for the lexical rebuild -- that is one SQL call whose
        length cannot be predicted, so a client shows an indeterminate progress bar
        rather than a fabricated percentage.
        """
        return self._get(f"search-indices/{index_id}/jobs/{self._uuid(job_uuid)}", IndexJobResponse)

    def cancel_search_index_job(self, index_id: str, job_uuid: _uuid_mod.UUID | str) -> LoomRequest[IndexJobResponse]:
        """Ask a running job to stop at its next item boundary.

        Cooperative, so the returned job is the state at the moment of asking; it becomes
        ``CANCELLED`` once the worker notices. Whatever had already been written stays
        written -- these operations are idempotent, so a cancelled job is a partial one.
        """
        return self._delete(f"search-indices/{index_id}/jobs/{self._uuid(job_uuid)}", IndexJobResponse)
