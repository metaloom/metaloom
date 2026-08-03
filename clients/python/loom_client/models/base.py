# Copyright 2024 Johannes Schüth
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Serialisation machinery and the shared bases for every wire model.

The models themselves are dataclasses generated from the server's Java model classes
(see ``tools/generate_models.py``). This module is hand-written: it is what makes them
work, and the rules it encodes are the ones worth understanding.

**Names.** Python attributes are snake_case, the wire is camelCase. The default
mapping is mechanical; anywhere it would not round-trip — ``_metainfo`` being the
notable case — the generator emits an explicit ``json`` alias in the field metadata.

**Nulls.** ``to_dict()`` omits ``None`` by default, matching the server's own
``Include.NON_NULL`` mapper. This is what create, update and patch want: only the
fields you set are touched.

    A consequence worth knowing: ``replace_*`` (PUT) requires every replaceable
    property to be present in the body, and rejects the request naming the missing
    ones otherwise. So a partially-populated model cannot satisfy a PUT — load the
    element, modify it, and send the result back. ``to_dict(include_none=True)``
    is the escape hatch if you need to clear fields explicitly.

**Unknown fields.** Parsing never fails on a key it does not recognise; the value is
kept in ``extra`` and written back out by ``to_dict()``. A client is almost always
older than the server it talks to, and load-modify-save should not quietly drop the
fields this version has not learned about yet.

**UUIDs are strings.** Models mirror the wire, and nothing in the client treats a
UUID as anything but an opaque identifier. Method *parameters* accept ``uuid.UUID``
as well and normalise — that is where callers genuinely hold one.

**Timestamps are raw.** The server encodes them two different ways (see
:func:`parse_instant`), so the value is stored exactly as it arrived rather than
guessed at.
"""

from __future__ import annotations

import types
import typing
from dataclasses import dataclass, field, fields
from datetime import datetime, timezone
from enum import Enum
from typing import Any, TypeVar

T = TypeVar("T", bound="Model")

#: Every model and enum class by name. Populated on class creation and used to
#: resolve annotations, so generated modules can reference each other's types
#: without importing them at runtime and creating import cycles.
MODEL_REGISTRY: dict[str, type] = {}

#: The format the server's strict deserialiser accepts: whole seconds, no fraction.
INSTANT_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


def register(cls: type) -> type:
    """Register a class so annotations can refer to it by name. Usable as a decorator."""
    MODEL_REGISTRY[cls.__name__] = cls
    return cls


# ---------------------------------------------------------------------------
# name mapping
# ---------------------------------------------------------------------------


def to_camel_case(name: str) -> str:
    """``last_uuid`` -> ``lastUuid``. The default attribute-to-wire mapping."""
    head, *rest = name.split("_")
    return head + "".join(word[:1].upper() + word[1:] for word in rest)


def _wire_name(f: Any) -> str:
    return f.metadata.get("json") or to_camel_case(f.name)


# ---------------------------------------------------------------------------
# timestamps
# ---------------------------------------------------------------------------


def parse_instant(value: str | float | int | None) -> datetime | None:
    """Parse either timestamp encoding the server uses.

    Most timestamps are ISO-8601 strings, but a handful — ``SearchHitResponse.sortDate``
    and ``SearchStatusResponse.lastSyncedAt`` among them — are epoch seconds as a
    number, because the server's mapper was not configured to write dates as strings
    consistently. Rather than guess, models store what arrived; this parses both.

    Returns:
        A timezone-aware ``datetime`` in UTC, or ``None``.
    """
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, tz=timezone.utc)
    text = str(value).strip()
    if not text:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    return datetime.fromisoformat(text)


def format_instant(value: datetime) -> str:
    """Format a ``datetime`` the way the server's strict parser expects.

    Truncates to whole seconds: the server's pattern has no fractional part, so
    sending one is rejected with 400.
    """
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).strftime(INSTANT_FORMAT)


# ---------------------------------------------------------------------------
# annotation resolution
# ---------------------------------------------------------------------------

_HINT_CACHE: dict[type, dict[str, Any]] = {}


def _hints(cls: type) -> dict[str, Any]:
    """Resolve and cache a model's annotations.

    Generated modules import each other's types only under ``TYPE_CHECKING``, to keep
    them free of import cycles, so those names are supplied here from the registry.

    The registry goes in as *localns*, not globalns: ``get_type_hints`` walks the MRO
    and resolves each class's annotations against its own module, and passing globalns
    would replace all of those at once -- so a base declared in this module would stop
    resolving the names this module imports. As localns it only fills the gaps.
    """
    cached = _HINT_CACHE.get(cls)
    if cached is not None:
        return cached
    try:
        resolved = typing.get_type_hints(cls, localns=dict(MODEL_REGISTRY))
    except Exception:
        # An annotation naming a type this client version does not know about must
        # not make the whole model unusable — such fields fall through untouched.
        resolved = {}
    _HINT_CACHE[cls] = resolved
    return resolved


def _unwrap_optional(annotation: Any) -> Any:
    origin = typing.get_origin(annotation)
    if origin is typing.Union or origin is types.UnionType:
        args = [a for a in typing.get_args(annotation) if a is not type(None)]
        if len(args) == 1:
            return args[0]
        # `SomeEnum | str | None` — the enum carries the conversion.
        for arg in args:
            if isinstance(arg, type) and issubclass(arg, Enum):
                return arg
        return args[0] if args else Any
    return annotation


def _decode(value: Any, annotation: Any) -> Any:
    """Convert one JSON value according to its resolved annotation."""
    if value is None:
        return None

    annotation = _unwrap_optional(annotation)
    origin = typing.get_origin(annotation)

    if origin in (list, set, frozenset, tuple):
        args = typing.get_args(annotation)
        item = args[0] if args else Any
        return [_decode(v, item) for v in value]

    if origin is dict:
        args = typing.get_args(annotation)
        item = args[1] if len(args) == 2 else Any
        return {k: _decode(v, item) for k, v in value.items()}

    if isinstance(annotation, type):
        if issubclass(annotation, Model):
            return annotation.from_dict(value)
        if issubclass(annotation, Enum):
            return _decode_enum(annotation, value)

    return value


def _decode_enum(enum_cls: type[Enum], value: Any) -> Any:
    """Coerce to an enum member, passing unrecognised values through unchanged.

    A newer server may send a member this client does not have. Raising would make an
    otherwise usable response unreadable over a field the caller may not even touch.
    """
    try:
        return enum_cls(value)
    except ValueError:
        return value


def _encode(value: Any, include_none: bool) -> Any:
    """Convert one attribute value to its JSON representation."""
    if isinstance(value, Model):
        return value.to_dict(include_none=include_none)
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return format_instant(value)
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_encode(v, include_none) for v in value]
    if isinstance(value, dict):
        return {k: _encode(v, include_none) for k, v in value.items()}
    return value


# ---------------------------------------------------------------------------
# bases
# ---------------------------------------------------------------------------


@dataclass
class Model:
    """Base class for every request and response model.

    Attributes:
        extra: Wire fields this client version does not declare. Preserved on parse
            and written back on serialisation, so a round trip is lossless.
    """

    extra: dict[str, Any] = field(default_factory=dict, repr=False, compare=False, kw_only=True)

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        MODEL_REGISTRY[cls.__name__] = cls

    @classmethod
    def from_dict(cls: type[T], data: dict[str, Any] | None) -> T | None:
        """Build an instance from a decoded JSON object.

        Unknown keys are kept in :attr:`extra` rather than raising.
        """
        if data is None:
            return None
        if not isinstance(data, dict):
            raise TypeError(f"{cls.__name__}.from_dict expects an object, got {type(data).__name__}")

        hints = _hints(cls)
        known: dict[str, str] = {}
        values: dict[str, Any] = {}
        for f in fields(cls):
            if f.name == "extra":
                continue
            wire = _wire_name(f)
            known[wire] = f.name
            if wire in data:
                values[f.name] = _decode(data[wire], hints.get(f.name, Any))

        instance = cls(**values)
        instance.extra = {k: v for k, v in data.items() if k not in known}
        return instance

    def to_dict(self, *, include_none: bool = False) -> dict[str, Any]:
        """Serialise to a JSON-ready dict.

        Args:
            include_none: Emit unset fields as explicit ``null`` instead of omitting
                them. Off by default, matching the server's own mapper. Turn it on
                to satisfy the completeness check on a ``replace_*`` (PUT) call.
        """
        # Unknown fields go in first so a declared field always wins on collision.
        out: dict[str, Any] = dict(self.extra)
        for f in fields(self):
            if f.name == "extra":
                continue
            value = getattr(self, f.name)
            if value is None and not include_none:
                out.pop(_wire_name(f), None)
                continue
            out[_wire_name(f)] = _encode(value, include_none)
        return out


@dataclass
class Response(Model):
    """A model that carries the element's own UUID.

    Mirrors ``AbstractResponse``.
    """

    uuid: str | None = None


@dataclass
class NamedReference(Response):
    """A lightweight ``{uuid, name}`` pointer to another element.

    Mirrors ``AbstractNamedReference``.
    """

    name: str | None = None


@dataclass
class CreatorEditorStatus(Model):
    """Who created and last edited an element, and when.

    ``created`` and ``edited`` are ISO-8601 strings; use :func:`parse_instant` to get
    a ``datetime``.
    """

    creator: UserReference | None = None
    created: str | None = None
    editor: UserReference | None = None
    edited: str | None = None


@dataclass
class UserReference(NamedReference):
    """A ``{uuid, name}`` pointer to a user.

    Declared here rather than in the generated user module because
    :class:`CreatorEditorStatus`, which nearly every response embeds, refers to it.
    """


@dataclass
class CreatorEditorResponse(Response):
    """A response carrying creator/editor bookkeeping and custom metadata.

    Mirrors ``AbstractCreatorEditorRestResponse``, the base of most response models.
    """

    status: CreatorEditorStatus | None = None
    meta: dict[str, Any] | None = None


@dataclass
class MetaModel(Model):
    """A request model that accepts custom metadata.

    Mirrors ``AbstractMetaModel``.
    """

    meta: dict[str, Any] | None = None


@dataclass
class PagingInfo(Model):
    """Paging bookkeeping attached to every list response as ``_metainfo``.

    Attributes:
        last_uuid: UUID of the final element on this page — the cursor to pass as
            ``from`` to fetch the next one.
        per_page: Page size that was applied.
        total_count: Total matching elements across all pages.
    """

    last_uuid: str | None = None
    per_page: int | None = None
    total_count: int = 0


@dataclass
class ListResponse(Model):
    """Base for paged list responses.

    Subclasses narrow ``data`` to their element type. Prefer
    :meth:`loom_client.request.LoomRequest.iter` over walking ``metainfo`` by hand.
    """

    data: list[Any] = field(default_factory=list)
    metainfo: PagingInfo | None = field(default=None, metadata={"json": "_metainfo"})

    def __iter__(self):
        return iter(self.data)

    def __len__(self) -> int:
        return len(self.data)


@dataclass
class GenericMessageResponse(Model):
    """The server's generic ``{"message": ...}`` payload.

    Errors arrive in this shape; :attr:`loom_client.errors.LoomHttpError.message`
    reads it for you, so you rarely need this type directly.
    """

    message: str | None = None


# Model itself is not covered by __init_subclass__.
register(Model)
