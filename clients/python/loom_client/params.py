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
"""Query parameter keys and sort vocabulary for paged list routes.

Mirrors ``io.metaloom.loom.rest.parameter.QueryParameterKey`` and
``io.metaloom.loom.api.sort``. The search routes (``/search/*``) accept a
different, disjoint parameter set and do not use these keys.
"""

from __future__ import annotations

from enum import Enum

#: Page size. The server defaults to 25 when the parameter is absent.
LIMIT = "limit"

#: Seek paging cursor — the UUID to continue from.
FROM = "from"

#: LHS filter expression, e.g. ``name[eq]=joedoe``. May be repeated.
FILTER = "filter"

#: Field to sort by; see :class:`SortKey`.
SORT = "sort"

#: Sort order; see :class:`SortDirection`.
DIRECTION = "dir"

#: Page size the server applies when ``limit`` is not given.
DEFAULT_LIMIT = 25


class SortDirection(str, Enum):
    """Sort order. The server defaults to ascending."""

    ASCENDING = "ASCENDING"
    DESCENDING = "DESCENDING"

    def __str__(self) -> str:
        return self.value


class SortKey(str, Enum):
    """Fields the server can sort by.

    Any string is accepted by :meth:`loom_client.request.LoomRequest.sort`; this enum
    only names the keys the server currently recognises.

    There is deliberately no ``LASTNAME``: the server's ``LoomSortKey.LASTNAME`` is
    declared with the key ``"firstname"``, so sorting by last name is not reachable
    until that is fixed upstream.
    """

    USERNAME = "username"
    FIRSTNAME = "firstname"
    NAME = "name"
    EMAIL = "email"
    COLLECTION = "collection"
    SHA512 = "sha512"
    MD5 = "md5"
    UUID = "uuid"

    def __str__(self) -> str:
        return self.value
