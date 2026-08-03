"""Transcripts. Mirrors ``io.metaloom.loom.client.common.method.TranscriptMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.transcript import (
    TranscriptCreateRequest,
    TranscriptListResponse,
    TranscriptResponse,
    TranscriptUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class TranscriptMethods:
    """Speech-to-text transcripts under ``/assets/{uuid}/transcripts``.

    Written by transcription nodes such as Whisper, and searchable once indexed."""

    def create_asset_transcript(
        self, asset_uuid: _uuid_mod.UUID | str, request: TranscriptCreateRequest
    ) -> LoomRequest[TranscriptResponse]:
        """Attach a transcript to an asset."""
        return self._post(f"assets/{self._uuid(asset_uuid)}/transcripts", request, TranscriptResponse)

    def load_asset_transcript(
        self, asset_uuid: _uuid_mod.UUID | str, transcript_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[TranscriptResponse]:
        """Load a single transcript of an asset."""
        return self._get(
            f"assets/{self._uuid(asset_uuid)}/transcripts/{self._uuid(transcript_uuid)}", TranscriptResponse
        )

    def update_asset_transcript(
        self,
        asset_uuid: _uuid_mod.UUID | str,
        transcript_uuid: _uuid_mod.UUID | str,
        request: TranscriptUpdateRequest,
    ) -> LoomRequest[TranscriptResponse]:
        """Update a transcript. Only the fields you set are changed."""
        return self._post(
            f"assets/{self._uuid(asset_uuid)}/transcripts/{self._uuid(transcript_uuid)}",
            request,
            TranscriptResponse,
        )

    def list_asset_transcripts(self, asset_uuid: _uuid_mod.UUID | str) -> LoomRequest[TranscriptListResponse]:
        """List an asset's transcripts."""
        return self._get(f"assets/{self._uuid(asset_uuid)}/transcripts", TranscriptListResponse)

    def delete_asset_transcript(
        self, asset_uuid: _uuid_mod.UUID | str, transcript_uuid: _uuid_mod.UUID | str
    ) -> LoomRequest[None]:
        """Delete a transcript. Answers 204 with no body."""
        return self._delete(f"assets/{self._uuid(asset_uuid)}/transcripts/{self._uuid(transcript_uuid)}")
