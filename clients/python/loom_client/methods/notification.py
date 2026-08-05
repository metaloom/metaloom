"""Notifications. Mirrors ``io.metaloom.loom.client.common.method.NotificationMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.notification import (
    NotificationListResponse,
    NotificationResponse,
    NotificationUpdateRequest,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class NotificationMethods:
    """The caller's own inbox under ``/notifications``.

    Every method operates on the authenticated user's entries and nobody else's.
    Reading, marking or deleting somebody else's notification answers 404 rather than
    403 -- a 403 would confirm the uuid exists.

    There is deliberately no create method. Notifications are dispatched server-side
    when something happens (a task is assigned, a comment is posted, a run fails); a
    client able to post one could forge a message from anybody.
    """

    def list_notifications(self) -> LoomRequest[NotificationListResponse]:
        """List the caller's notifications, newest first.

        The response carries ``unread_count`` for the whole inbox, not for the page.
        """
        return self._get("notifications", NotificationListResponse)

    def list_unread_notifications(self) -> LoomRequest[NotificationListResponse]:
        """List only the unread entries. ``unread_count`` is still the full total."""
        return self._get("notifications?unread=true", NotificationListResponse)

    def load_notification(self, notification_uuid: _uuid_mod.UUID | str) -> LoomRequest[NotificationResponse]:
        """Load one of the caller's notifications."""
        return self._get(f"notifications/{self._uuid(notification_uuid)}", NotificationResponse)

    def update_notification(
        self, notification_uuid: _uuid_mod.UUID | str, request: NotificationUpdateRequest
    ) -> LoomRequest[NotificationResponse]:
        """Mark a notification read or unread. ``read`` is the only mutable field."""
        return self._post(f"notifications/{self._uuid(notification_uuid)}", request, NotificationResponse)

    def mark_notification_read(
        self, notification_uuid: _uuid_mod.UUID | str, read: bool = True
    ) -> LoomRequest[NotificationResponse]:
        """Mark one notification as read (or, with ``read=False``, back to unread)."""
        return self.update_notification(notification_uuid, NotificationUpdateRequest(read=read))

    def mark_all_notifications_read(self) -> LoomRequest[None]:
        """Mark every unread entry of the caller as read."""
        return self._post_empty("notifications/read-all")

    def delete_notification(self, notification_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Dismiss one notification. Answers 204 with no body."""
        return self._delete(f"notifications/{self._uuid(notification_uuid)}")

    def clear_notifications(self) -> LoomRequest[None]:
        """Delete every notification of the caller.

        The retention escape hatch: nothing prunes the table automatically.
        """
        return self._delete("notifications")
