"""GraphQL. Mirrors ``io.metaloom.loom.client.common.method.GraphQLMethods``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..models.graphql import GraphQLRequest, GraphQLResponse

if TYPE_CHECKING:
    from ..request import LoomRequest


class GraphQLMethods:
    """The GraphQL endpoint at ``/graphql``.

    An alternative to the REST routes for read-heavy work, where one query can replace
    a fan-out of calls.
    """

    def execute_graph_ql(self, request: GraphQLRequest) -> LoomRequest[GraphQLResponse]:
        """Execute a GraphQL query.

        GraphQL reports query errors inside a 200 response rather than as an HTTP
        status, so check the response's ``errors`` field -- no exception is raised for
        a query that failed to resolve.
        """
        return self._post("graphql", request, GraphQLResponse)
