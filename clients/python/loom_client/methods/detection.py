"""Detections. Mirrors ``io.metaloom.loom.client.common.method.DetectionMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..assets import AssetId
from ..models.detection import (
    DetectionBulkCreateRequest,
    DetectionBulkResponse,
    DetectionBulkReviewRequest,
    DetectionConfirmRequest,
    DetectionCreateRequest,
    DetectionListResponse,
    DetectionResponse,
    DetectionUpdateRequest,
)

if TYPE_CHECKING:
    from ..response import BinaryResponse
    from ..request import LoomRequest


class DetectionMethods:
    """Detections under ``/assets/{id}/detections``.

    A detection is something a model found in an asset -- a face, an object, a piece
    of text -- with its bounding area and, usually, an embedding.

    The asset is addressable by UUID or SHA-512, as everywhere in the asset routes.
    Detections normally arrive in batches, so prefer :meth:`bulk_create_asset_detections`
    over one call per box.
    """

    def load_asset_detection(
        self, asset_id: AssetId | _uuid_mod.UUID | str, detection_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[DetectionResponse]:
        """Load a single detection."""
        return self._get(
            f"{self._asset_sub(asset_id)}/detections/{self._uuid(detection_uuid)}", DetectionResponse
        )

    def create_asset_detection(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: DetectionCreateRequest
    ) -> LoomRequest[DetectionResponse]:
        """Record one detection against an asset."""
        return self._post(f"{self._asset_sub(asset_id)}/detections", request, DetectionResponse)

    def update_asset_detection(
        self,
        asset_id: AssetId | _uuid_mod.UUID | str,
        detection_uuid: _uuid_mod.UUID | str,
        request: DetectionUpdateRequest,
    ) -> LoomRequest[DetectionResponse]:
        """Update a detection. Only the fields you set are changed."""
        return self._post(
            f"{self._asset_sub(asset_id)}/detections/{self._uuid(detection_uuid)}",
            request,
            DetectionResponse,
        )

    def list_asset_detections(
        self, asset_id: AssetId | _uuid_mod.UUID | str
    ) -> LoomRequest[DetectionListResponse]:
        """List an asset's detections."""
        return self._get(f"{self._asset_sub(asset_id)}/detections", DetectionListResponse)

    def delete_asset_detection(
        self, asset_id: AssetId | _uuid_mod.UUID | str, detection_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a detection. Answers 204 with no body."""
        return self._delete(f"{self._asset_sub(asset_id)}/detections/{self._uuid(detection_uuid)}")

    def bulk_create_asset_detections(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: DetectionBulkCreateRequest
    ) -> LoomRequest[DetectionBulkResponse]:
        """Record many detections in one request.

        The response reports per-item status, so check each entry rather than only the
        HTTP status.
        """
        return self._post(f"{self._asset_sub(asset_id)}/detections/bulk", request, DetectionBulkResponse)

    def load_detection_crop(
        self, asset_id: AssetId | _uuid_mod.UUID | str, detection_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[BinaryResponse]:
        """Download the cropped face image for a detection.

        Served from the deployment's own storage - face crops are biometric data. The
        bytes are written by the face-detection node, so a detection whose node has not
        run answers 404.
        """
        return self._download(
            f"{self._asset_sub(asset_id)}/detections/{self._uuid(detection_uuid)}/crop"
        )

    def confirm_asset_detection(
        self,
        asset_id: AssetId | _uuid_mod.UUID | str,
        detection_uuid: _uuid_mod.UUID | str,
        request: DetectionConfirmRequest | None = None,
    ) -> LoomRequest[DetectionResponse]:
        """Confirm a detection: the producer found something real here.

        Pass a request with ``corrected_label`` when the box was right but the class was
        wrong. That is still a confirmation -- the producer's own ``label`` is kept
        alongside the correction, because it is the training signal.
        """
        return self._post(
            f"{self._asset_sub(asset_id)}/detections/{self._uuid(detection_uuid)}/confirm",
            request,
            DetectionResponse,
        )

    def reject_asset_detection(
        self, asset_id: AssetId | _uuid_mod.UUID | str, detection_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[DetectionResponse]:
        """Reject a detection as a false positive.

        The row is kept rather than deleted: it is the record that the producer was wrong
        here, which is what stops the next run presenting the same box again as new.
        """
        return self._post(
            f"{self._asset_sub(asset_id)}/detections/{self._uuid(detection_uuid)}/reject",
            None,
            DetectionResponse,
        )

    def bulk_review_asset_detections(
        self, asset_id: AssetId | _uuid_mod.UUID | str, request: DetectionBulkReviewRequest
    ) -> LoomRequest[DetectionBulkResponse]:
        """Record many verdicts on one asset in a single request.

        A bad item is counted and skipped rather than failing the batch, so check
        ``failed`` on the response rather than only the HTTP status.
        """
        return self._post(
            f"{self._asset_sub(asset_id)}/detections/review-bulk", request, DetectionBulkResponse
        )

    def list_detections(
        self, status: str | None = None, type: str | None = None
    ) -> LoomRequest[DetectionListResponse]:
        """The cross-asset review queue.

        Unlike :meth:`list_asset_detections` this spans every asset, which is the one
        question that cannot be asked of a single one: what is still waiting to be
        reviewed.

        Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``.

        Args:
            status: ``PENDING``, ``CONFIRMED`` or ``REJECTED``. Omitted when ``None``.
            type: Detection type, e.g. ``objectdetection``. Omitted when ``None``.
        """
        request = self._get("detections", DetectionListResponse)
        if status is not None:
            request.param("status", status)
        if type is not None:
            request.param("type", type)
        return request
