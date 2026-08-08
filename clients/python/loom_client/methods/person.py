"""Persons. Mirrors ``io.metaloom.loom.client.common.method.PersonMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.cluster import ClusterListResponse
from ..models.person import (
    PersonCreateRequest,
    PersonListResponse,
    PersonResponse,
    PersonUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class PersonMethods:
    """CRUD on ``/persons``.

    A person is an identity that face detections can be linked to."""

    def load_person(self, person_uuid: _uuid_mod.UUID | str) -> LoomRequest[PersonResponse]:
        """Load a single person."""
        return self._get(f"persons/{self._uuid(person_uuid)}", PersonResponse)

    def create_person(self, request: PersonCreateRequest) -> LoomRequest[PersonResponse]:
        """Create a person."""
        return self._post("persons", request, PersonResponse)

    def update_person(
        self, person_uuid: _uuid_mod.UUID | str, request: PersonUpdateRequest
    ) -> LoomRequest[PersonResponse]:
        """Update a person. Only the fields you set are changed."""
        return self._post(f"persons/{self._uuid(person_uuid)}", request, PersonResponse)

    def list_persons(self) -> LoomRequest[PersonListResponse]:
        """List persons. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("persons", PersonListResponse)

    def delete_person(self, person_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a person. Answers 204 with no body."""
        return self._delete(f"persons/{self._uuid(person_uuid)}")

    def list_person_clusters(
        self, person_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[ClusterListResponse]:
        """List the face clusters confirmed to be this person.

        The inverse of :meth:`confirm_cluster`: given a person, which groups of faces
        were attributed to them, across which assets.
        """
        return self._get(f"persons/{self._uuid(person_uuid)}/clusters", ClusterListResponse)
