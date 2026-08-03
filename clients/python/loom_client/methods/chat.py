"""Chats. Mirrors ``io.metaloom.loom.client.common.method.ChatMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.chat import (
    ChatCreateRequest,
    ChatListResponse,
    ChatResponse,
    ChatUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class ChatMethods:
    """CRUD on ``/chats``.

    Streaming replies and chat sessions are not covered here; the Java client has no
    methods for them either."""

    def load_chat(self, chat_uuid: _uuid_mod.UUID | str) -> LoomRequest[ChatResponse]:
        """Load a single chat."""
        return self._get(f"chats/{self._uuid(chat_uuid)}", ChatResponse)

    def create_chat(self, request: ChatCreateRequest) -> LoomRequest[ChatResponse]:
        """Create a chat."""
        return self._post("chats", request, ChatResponse)

    def update_chat(
        self, chat_uuid: _uuid_mod.UUID | str, request: ChatUpdateRequest
    ) -> LoomRequest[ChatResponse]:
        """Update a chat. Only the fields you set are changed."""
        return self._post(f"chats/{self._uuid(chat_uuid)}", request, ChatResponse)

    def list_chats(self) -> LoomRequest[ChatListResponse]:
        """List chats. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("chats", ChatListResponse)

    def delete_chat(self, chat_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a chat. Answers 204 with no body."""
        return self._delete(f"chats/{self._uuid(chat_uuid)}")
