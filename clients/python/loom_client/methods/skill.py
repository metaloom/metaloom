"""Skills. Mirrors ``io.metaloom.loom.client.common.method.SkillMethods``."""

from __future__ import annotations

import uuid as _uuid_mod
from typing import TYPE_CHECKING

from ..models.skill import (
    SkillCreateRequest,
    SkillListResponse,
    SkillResponse,
    SkillUpdateRequest,
    SkillVersionListResponse,
)

if TYPE_CHECKING:
    from ..request import LoomRequest


class SkillMethods:
    """Skills under ``/skills``, plus the library they are installed from.

    A skill is a reusable capability the assistant can call. ``/skills`` holds the ones
    installed on this instance; ``/skills/library`` holds the catalogue available to
    install from. As with pipelines, every edit produces a version that can be restored.
    """

    def load_skill(self, skill_uuid: _uuid_mod.UUID | str) -> LoomRequest[SkillResponse]:
        """Load a single skill."""
        return self._get(f"skills/{self._uuid(skill_uuid)}", SkillResponse)

    def create_skill(self, request: SkillCreateRequest) -> LoomRequest[SkillResponse]:
        """Create a skill."""
        return self._post("skills", request, SkillResponse)

    def update_skill(
        self, skill_uuid: _uuid_mod.UUID | str, request: SkillUpdateRequest
    ) -> LoomRequest[SkillResponse]:
        """Update a skill. This creates a new version."""
        return self._post(f"skills/{self._uuid(skill_uuid)}", request, SkillResponse)

    def list_skills(self) -> LoomRequest[SkillListResponse]:
        """List installed skills. Supports ``limit``, ``from_``, ``filter``, ``sort`` and ``iter``."""
        return self._get("skills", SkillListResponse)

    def delete_skill(self, skill_uuid: _uuid_mod.UUID | str) -> LoomRequest[None]:
        """Delete a skill. Answers 204 with no body."""
        return self._delete(f"skills/{self._uuid(skill_uuid)}")

    def list_skill_library(self) -> LoomRequest[SkillListResponse]:
        """List the catalogue of skills available to install."""
        return self._get("skills/library", SkillListResponse)

    def install_skill(self, skill_uuid: _uuid_mod.UUID | str) -> LoomRequest[SkillResponse]:
        """Install a skill from the library. Takes no body."""
        return self._post_empty(f"skills/{self._uuid(skill_uuid)}/install", SkillResponse)

    def list_skill_versions(self, skill_uuid: _uuid_mod.UUID | str) -> LoomRequest[SkillVersionListResponse]:
        """List a skill's stored versions."""
        return self._get(f"skills/{self._uuid(skill_uuid)}/versions", SkillVersionListResponse)

    def load_skill_version(
        self, skill_uuid: _uuid_mod.UUID | str, version_number: int
    ) -> LoomRequest[SkillResponse]:
        """Load one stored version of a skill."""
        return self._get(f"skills/{self._uuid(skill_uuid)}/versions/{int(version_number)}", SkillResponse)

    def restore_skill_version(
        self, skill_uuid: _uuid_mod.UUID | str, version_number: int
    ) -> LoomRequest[SkillResponse]:
        """Restore a stored version, making it current. Takes no body."""
        return self._post_empty(
            f"skills/{self._uuid(skill_uuid)}/versions/{int(version_number)}/restore",
            SkillResponse,
        )
