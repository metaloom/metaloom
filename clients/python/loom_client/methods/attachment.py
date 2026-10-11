"""Attachments. Mirrors ``io.metaloom.loom.client.common.method.AttachmentMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.asset import AssetResponse
from ..models.attachment import (
    AttachmentListResponse,
    AttachmentResponse,
    AttachmentUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest
    from ..response import BinaryResponse


class AttachmentMethods:
    """Files attached to an asset -- sidecars, previews, exported artefacts.

    Java declares two ``uploadAttachment`` overloads, one taking a file and one taking
    a stream. Python has no overloading, so the stream form is
    :meth:`upload_attachment_stream`.
    """

    def load_attachment(self, uuid: _uuid_mod.UUID | str) -> LoomRequest[AttachmentResponse]:
        """Load a single attachment record."""
        return self._get(f"attachments/{self._uuid(uuid)}", AttachmentResponse)

    def update_attachment(
        self, uuid: _uuid_mod.UUID | str, request: AttachmentUpdateRequest
    ) -> LoomRequest[AttachmentResponse]:
        """Update an attachment. Only the fields you set are changed."""
        return self._post(f"attachments/{self._uuid(uuid)}", request, AttachmentResponse)

    def list_attachments(self) -> LoomRequest[AttachmentListResponse]:
        """List attachments. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("attachments", AttachmentListResponse)

    def delete_attachment(self, uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete an attachment. Answers 204 with no body."""
        return self._delete(f"attachments/{self._uuid(uuid)}")

    def upload_attachment(
        self,
        file: str | bytes,
        mime_type: str | None = None,
        asset_uuid: _uuid_mod.UUID | str | None = None,
        type: str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[AttachmentResponse]:
        """Upload an attachment.

        Args:
            file: Path to the file, or its bytes. Bytes require ``filename``.
            mime_type: Content type; defaults to ``application/octet-stream``.
            asset_uuid: Asset to attach to.
            type: Attachment type, as the server defines it.
            filename: Overrides the name sent to the server.
        """
        return self._upload_multipart(
            "attachments",
            file,
            AttachmentResponse,
            filename=filename,
            mime_type=mime_type,
            fields=(
                ("assetUuid", str(asset_uuid) if asset_uuid else None),
                ("type", type),
            ),
        )

    def upload_attachment_stream(
        self, filename: str, mime_type: str, file_data: bytes
    ) -> LoomRequest[AttachmentResponse]:
        """Upload an attachment as a raw body rather than a multipart form.

        The Java overload of the same name takes an ``InputStream``; this takes the
        bytes, since the body is buffered either way.
        """
        return self._upload_binary("attachments", file_data, mime_type, AttachmentResponse)

    def download_attachment(self, uuid: _uuid_mod.UUID | str) -> LoomRequest[BinaryResponse]:
        """Download an attachment's bytes.

        The body streams, so close it -- see
        :meth:`~loom_client.methods.asset_binary.AssetBinaryMethods.download_asset_binary`.
        """
        return self._download(f"attachments/{self._uuid(uuid)}/data")

    def upload_face_crop(
        self,
        file: str | bytes,
        asset_uuid: _uuid_mod.UUID | str,
        detection_uuid: _uuid_mod.UUID | str,
        variant: str | None = None,
        node_kind: str | None = None,
    ) -> LoomRequest[AttachmentResponse]:
        """Upload a cropped face, bound to the detection it depicts.

        A face crop belongs to one detected face rather than to a whole asset - an asset
        has many faces - so the detection uuid is what addresses it later. Uploading the
        same ``(detection, variant)`` again replaces the previous crop.
        """
        return self._upload_multipart(
            "attachments",
            file,
            AttachmentResponse,
            filename="face-crop.jpg",
            mime_type="image/jpeg",
            fields=(
                ("assetUuid", str(asset_uuid)),
                ("type", "FACE_CROP"),
                ("detectionUuid", str(detection_uuid)),
                ("variant", variant),
                ("nodeKind", node_kind),
            ),
        )

    # --- Chat attachments -------------------------------------------------------------
    # Files a user dropped into a conversation. Owned by the chat rather than by an asset:
    # reachable only through the chat, and hidden from the generic routes above.

    def upload_chat_attachment(
        self,
        chat_uuid: _uuid_mod.UUID | str,
        file: str | bytes,
        mime_type: str | None = None,
        *,
        filename: str | None = None,
    ) -> LoomRequest[AttachmentResponse]:
        """Attach a file to a chat. A chat file is filed in no library."""
        return self._upload_multipart(
            f"chats/{self._uuid(chat_uuid)}/attachments",
            file,
            AttachmentResponse,
            filename=filename,
            mime_type=mime_type,
        )

    def list_chat_attachments(self, chat_uuid: _uuid_mod.UUID | str) -> LoomRequest[AttachmentListResponse]:
        """The files attached to a chat, newest first."""
        return self._get(f"chats/{self._uuid(chat_uuid)}/attachments", AttachmentListResponse)

    def delete_chat_attachment(
        self, chat_uuid: _uuid_mod.UUID | str, attachment_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Detach a file. The bytes stay in content-addressed storage; only the chat's reference goes."""
        return self._delete(f"chats/{self._uuid(chat_uuid)}/attachments/{self._uuid(attachment_uuid)}")

    def download_chat_attachment(
        self, chat_uuid: _uuid_mod.UUID | str, attachment_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[BinaryResponse]:
        """The raw bytes of a chat attachment. The body streams, so close it."""
        return self._download(f"chats/{self._uuid(chat_uuid)}/attachments/{self._uuid(attachment_uuid)}/data")

    def save_chat_attachment_to_library(
        self,
        chat_uuid: _uuid_mod.UUID | str,
        attachment_uuid: _uuid_mod.UUID | str,
        library_uuid: _uuid_mod.UUID | str | None = None,
    ) -> LoomRequest[AssetResponse]:
        """Save a chat attachment into the media library as a real asset.

        The attachment stays on the chat. Without ``library_uuid`` the server files it in
        ``LOOM_CHAT_ATTACHMENT_LIBRARY``.
        """
        request = self._post_empty(
            f"chats/{self._uuid(chat_uuid)}/attachments/{self._uuid(attachment_uuid)}/asset", AssetResponse
        )
        if library_uuid is not None:
            request.param("libraryUuid", str(library_uuid))
        return request
