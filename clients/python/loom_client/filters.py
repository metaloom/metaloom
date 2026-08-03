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
"""LHS filter expressions for paged list routes.

Loom parses filters in left-hand-side bracket notation::

    name[eq]=joedoe
    size[gte]=1MB
    created[after]=2026-01-01T00:00:00Z

Build them with the helpers here and hand them to
:meth:`loom_client.request.LoomRequest.filter`::

    client.list_users().filter(eq("username", "joedoe")).body()
    client.list_assets().filter(gte("size", "1MB")).filter(ne("status", "FAILED")).body()

Filters combine by repeating the ``filter`` query parameter; the server ANDs them.

The keys the server currently registers are ``uuid``, ``name``, ``collection``,
``username``, ``size``, ``status`` and ``dry_run`` — but any key is accepted here,
since the registered set is server-side state this client cannot verify.
"""

from __future__ import annotations

from dataclasses import dataclass

#: Equality.
EQUALS = "eq"
#: Inequality.
NOT_EQUALS = "ne"
#: Greater than or equal.
GREATER = "gte"
#: Less than or equal.
LESSER = "lte"
#: Temporal: strictly after.
AFTER = "after"
#: Temporal: strictly before.
BEFORE = "before"
#: Inclusive interval, e.g. ``size[range]=1MB-2MB``.
RANGE = "range"


@dataclass(frozen=True)
class Filter:
    """A single ``key[operation]=value`` term."""

    key: str
    operation: str
    value: str

    def __str__(self) -> str:
        return f"{self.key}[{self.operation}]={self.value}"


def filter_(key: str, operation: str, value: object) -> Filter:
    """Build a filter with an arbitrary operation.

    Prefer the named helpers below; this exists for operations the server gains
    before this client learns about them.
    """
    return Filter(key, operation, str(value))


def eq(key: str, value: object) -> Filter:
    """``key[eq]=value``"""
    return Filter(key, EQUALS, str(value))


def ne(key: str, value: object) -> Filter:
    """``key[ne]=value``"""
    return Filter(key, NOT_EQUALS, str(value))


def gte(key: str, value: object) -> Filter:
    """``key[gte]=value``"""
    return Filter(key, GREATER, str(value))


def lte(key: str, value: object) -> Filter:
    """``key[lte]=value``"""
    return Filter(key, LESSER, str(value))


def after(key: str, value: object) -> Filter:
    """``key[after]=value`` — for temporal fields."""
    return Filter(key, AFTER, str(value))


def before(key: str, value: object) -> Filter:
    """``key[before]=value`` — for temporal fields."""
    return Filter(key, BEFORE, str(value))


def range_(key: str, low: object, high: object) -> Filter:
    """``key[range]=low-high`` — an inclusive interval."""
    return Filter(key, RANGE, f"{low}-{high}")
