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
"""Python client for the MetaLoom Loom REST API.

Synchronous, built on the standard library, with no third-party dependencies::

    from loom_client import LoomClient

    with LoomClient(host="localhost", port=8092) as client:
        client.authenticate("admin", "finger")
        asset = client.load_asset("cf83e1357eefb8bd...").body()
        print(asset.filename)

Every client method returns a request that has not been sent. ``.body()`` gives you
the parsed model; ``.execute()`` gives you the status code and headers too. List
routes additionally take ``.limit()``, ``.from_()``, ``.filter()``, ``.sort()`` and
``.iter()``, which walks every page for you.

Failures raise a subclass of :class:`~loom_client.errors.LoomHttpError` chosen by
status code, so ``except LoomNotFoundError`` works without inspecting ``.status``.
"""

from __future__ import annotations

from .assets import AssetId
from .client import DEFAULT_PORT, DEFAULT_TIMEOUT, LoomClient
from .errors import (
    LoomBadRequestError,
    LoomConflictError,
    LoomConnectionError,
    LoomError,
    LoomForbiddenError,
    LoomHttpError,
    LoomNotFoundError,
    LoomServerError,
    LoomUnauthorizedError,
)
from .filters import after, before, eq, gte, lte, ne, range_
from .params import SortDirection, SortKey
from .request import LoomRequest
from .response import BinaryResponse, LoomResponse

__version__ = "1.0.0.dev0"

__all__ = [
    "DEFAULT_PORT",
    "DEFAULT_TIMEOUT",
    "AssetId",
    "BinaryResponse",
    "LoomBadRequestError",
    "LoomClient",
    "LoomConflictError",
    "LoomConnectionError",
    "LoomError",
    "LoomForbiddenError",
    "LoomHttpError",
    "LoomNotFoundError",
    "LoomRequest",
    "LoomResponse",
    "LoomServerError",
    "LoomUnauthorizedError",
    "SortDirection",
    "SortKey",
    "__version__",
    "after",
    "before",
    "eq",
    "gte",
    "lte",
    "ne",
    "range_",
]
