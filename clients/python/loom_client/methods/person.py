"""Persons. Mirrors ``io.metaloom.loom.client.common.method.PersonMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.cluster import ClusterListResponse
from ..models.person import (
    PersonAvatarRequest,
    PersonCreateRequest,
    PersonImageImportRequest,
    PersonImageListResponse,
    PersonImageResponse,
    PersonListResponse,
    PersonResponse,
    PersonUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest
    from ..response import BinaryResponse


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

    def list_person_images(
        self, person_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[PersonImageListResponse]:
        """List the person's own pictures, newest first.

        Person images belong to the person rather than to any asset, so they outlive the
        material somebody was found in.
        """
        return self._get(f"persons/{self._uuid(person_uuid)}/images", PersonImageListResponse)

    def upload_person_image(
        self,
        person_uuid: _uuid_mod.UUID | str,
        file: str | bytes,
        mime_type: str | None = None,
        pool_uuid: _uuid_mod.UUID | str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[PersonImageResponse]:
        """Upload a picture of this person.

        Args:
            person_uuid: The person the picture belongs to.
            file: Path to the file, or its bytes. Bytes require ``filename``.
            mime_type: Content type; defaults to ``application/octet-stream``.
            pool_uuid: Storage pool for the bytes. Without one the image lands in the
                deployment's default storage, since a person image has no parent asset
                to inherit a pool from.
            filename: Overrides the name sent to the server.
        """
        return self._upload_multipart(
            f"persons/{self._uuid(person_uuid)}/images",
            file,
            PersonImageResponse,
            filename=filename,
            mime_type=mime_type,
            fields=(("poolUuid", str(pool_uuid) if pool_uuid else None),),
        )

    def import_person_image(
        self, person_uuid: _uuid_mod.UUID | str, request: PersonImageImportRequest
    ) -> LoomRequest[PersonImageResponse]:
        """Copy a detection's face crop into the person's own images.

        A copy, not a reference: the new image shares the crop's content-addressed bytes
        but belongs to the person, so it survives deleting the asset the face was found
        in. Confirming a cluster does not do this - choosing what somebody looks like is
        a separate decision from attributing a face to them.
        """
        return self._post(
            f"persons/{self._uuid(person_uuid)}/images/from-detection",
            request,
            PersonImageResponse,
        )

    def download_person_image(
        self, person_uuid: _uuid_mod.UUID | str, image_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[BinaryResponse]:
        """Download one of the person's images.

        The body streams, so close it -- see
        :meth:`~loom_client.methods.asset_binary.AssetBinaryMethods.download_asset_binary`.
        """
        return self._download(
            f"persons/{self._uuid(person_uuid)}/images/{self._uuid(image_uuid)}/data"
        )

    def delete_person_image(
        self, person_uuid: _uuid_mod.UUID | str, image_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete one of the person's images. Answers 204 with no body.

        Deleting the image currently used as the avatar leaves the person without one
        rather than failing.
        """
        return self._delete(
            f"persons/{self._uuid(person_uuid)}/images/{self._uuid(image_uuid)}"
        )

    def set_person_avatar(
        self, person_uuid: _uuid_mod.UUID | str, request: PersonAvatarRequest
    ) -> LoomRequest[PersonResponse]:
        """Designate one of the person's images as their avatar.

        A blank or absent ``image_uuid`` clears it.
        """
        return self._post(
            f"persons/{self._uuid(person_uuid)}/avatar", request, PersonResponse
        )
