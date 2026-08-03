"""Search. Mirrors ``io.metaloom.loom.client.common.method.SearchMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..models.search import (
    SearchResultResponse,
    SearchStatusResponse,
    SearchSuggestionListResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


#: Query parameters the ``/search/*`` routes accept, mirroring the server's
#: ``SearchQueryParameterKey``. Deliberately disjoint from the paged-list parameters
#: in :mod:`loom_client.params`: search pages by offset or cursor, not by keyset seek,
#: and the search term cannot be expressed as an LHS filter because the filter grammar
#: has no "contains" operator.
SEARCH_PARAMETERS = (
    "q",  # the search term; supports "quoted phrases", or, and -negation
    "types",  # comma separated entity types, e.g. "asset,transcript,tag"
    "mode",  # matching mode, e.g. "LEXICAL"
    "limit",  # page size, default 25
    "offset",  # result offset; prefer the returned cursor when there is one
    "cursor",  # opaque cursor from a previous response; wins over offset
    "sort",  # result ordering, e.g. "RELEVANCE"
    "highlight",  # return match snippets
    "mime",  # restrict to a mime type prefix, e.g. "image/"
    "library",  # restrict to a library UUID
    "space",  # restrict to a space UUID
    "collection",  # restrict to a collection UUID
    "tag",  # comma separated tag names the asset must carry
    "from",  # only elements first seen at or after this instant
    "to",  # only elements first seen at or before this instant
    "lang",  # restrict to a language tag
    "profile",  # named search profile
    "facets",  # comma separated facets to compute
)


class SearchMethods:
    """Full-text search over ``/search``.

    These routes take their own parameters -- see :data:`SEARCH_PARAMETERS` -- which
    are disjoint from the ``limit``/``from_``/``filter``/``sort`` set used by the paged
    list routes. Pass them as keyword arguments::

        client.search("aurora", types="asset,transcript", limit=50).body()
        client.search_assets("sunset", mime="image/", highlight=True).body()

    Paging is by ``offset`` or by the ``cursor`` a previous response returned; the
    cursor wins when both are given. ``.iter()`` does not apply here -- it follows the
    keyset cursor that list routes use.

    ``from`` collides with the Python keyword, so pass it as ``from_``; it is sent
    under its real name.
    """

    def search(self, query: str | None = None, **parameters: object) -> LoomRequest[SearchResultResponse]:
        """Search across every indexed element type."""
        return self._search("search/results", query, SearchResultResponse, parameters)

    def search_assets(
        self, query: str | None = None, **parameters: object
    ) -> LoomRequest[SearchResultResponse]:
        """Search assets only."""
        return self._search("search/assets", query, SearchResultResponse, parameters)

    def search_suggestions(
        self, prefix: str | None = None, **parameters: object
    ) -> LoomRequest[SearchSuggestionListResponse]:
        """Autocomplete suggestions for a prefix.

        The prefix travels as ``q``, the same parameter the other search routes use.
        """
        return self._search("search/suggestions", prefix, SearchSuggestionListResponse, parameters)

    def search_status(self) -> LoomRequest[SearchStatusResponse]:
        """Report index health and when it last synchronised.

        ``last_synced_at`` arrives as epoch seconds rather than an ISO string; pass it
        through :func:`loom_client.models.parse_instant`.
        """
        return self._get("search/status", SearchStatusResponse)

    def _search(self, path, term, model, parameters):
        """Attach the term and any extras as real query parameters.

        They must not be baked into the path: the path is percent-encoded, so a "?" in
        it would reach the server as %3F and the route would not match. A term of
        ``None`` is omitted rather than sent as the string "None".
        """
        request = self._get(path, model)
        if term is not None:
            request.param("q", term)
        for key, value in parameters.items():
            if value is None:
                continue
            # `from` is a Python keyword, so callers write from_.
            request.param(key.rstrip("_") if key == "from_" else key, value)
        return request
