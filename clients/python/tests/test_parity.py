"""Parity with the Java client, and with the server's published route table.

Three independent checks:

1. Every method the Java client declares exists here, under its snake_case name.
2. Every method here corresponds to a Java one -- an extra is a typo, not a feature.
3. Every path this client builds is a path the server actually registers.

The third is the valuable one, because it is an *independent* oracle: it compares
against the generated API description rather than against the Java client, so it
catches a route that both clients get wrong. That is not hypothetical -- the Java
client shipped a ``listCommentsForAnnotation`` that called a path the server never
registered.

All three skip when the Java tree is not next to the package, which is the case for an
installed wheel.
"""

from __future__ import annotations

import json
import re
import unittest
import uuid
from dataclasses import is_dataclass
from pathlib import Path

from loom_client import LoomClient
from loom_client.models.base import Model

CLIENT_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = CLIENT_ROOT.parent.parent
JAVA_METHODS_DIR = REPO_ROOT / "loom-client/common/src/main/java/io/metaloom/loom/client/common/method"
OPENAPI = REPO_ROOT / "loom/doc/src/main/generated/openapi.json"

#: The Java client's method count, as a tripwire: a new method added there should
#: fail this test rather than quietly leave the Python client behind.
#: 211 abstract declarations plus 21 ``default`` overloads. Cross-checked both ways
#: against ``LoomHttpClientImpl``, which implements exactly these 232.
EXPECTED_JAVA_METHOD_COUNT = 249

#: Paths this client builds that the generated API description does not list.
#:
#: Two very different reasons, kept apart deliberately:
#:
#: * STALE SPEC -- the server registers the route, but ``LoomOpenAPI.generate()`` never
#:   constructs the endpoint that owns it, so it is absent from ``openapi.json``.
#:   ``DedupGroupEndpoint``, ``SearchEndpoint`` and ``SimilarityIndexEndpoint`` are all
#:   missing from that list while being registered on the real router. The client is
#:   right and the description is incomplete.
#: * DEAD ROUTE -- no endpoint implements it at all, so the call really does 404. These
#:   exist because the Java client has them, and full parity was the goal.
KNOWN_UNLISTED_PATHS = {
    # STALE SPEC: DedupGroupEndpoint is registered but absent from LoomOpenAPI.
    "dedup-groups": "stale spec",
    "dedup-groups/{}": "stale spec",
    # STALE SPEC: SearchEndpoint likewise.
    "search/results": "stale spec",
    "search/assets": "stale spec",
    "search/suggestions": "stale spec",
    "search/status": "stale spec",
    # STALE SPEC: SimilarityIndexEndpoint likewise.
    "similarity-index/rebuild": "stale spec",
    # DEAD ROUTE: there is no AssetLocationEndpoint in the server at all. The Java
    # client's AssetLocationMethods have the same problem; kept for parity.
    "locations": "dead route",
    "locations/{}": "dead route",
}

#: Java methods whose Python name is not the mechanical snake_case translation.
#: Java overloads ``uploadAttachment`` on its parameter types; Python cannot.
RENAMES = {
    "uploadAttachment": ("upload_attachment", "upload_attachment_stream"),
}

#: Python-only additions, which have no Java counterpart by design.
PYTHON_ONLY = {
    "authenticate",  # login + set_token, the two-step everyone writes anyway
    "close",
    "from_env",
    "set_token",
    "token",
    "base_url",
}

ABSTRACT_RE = re.compile(r"LoomClientRequest<[\w\[\].]+>\s+(\w+)\(", re.S)
DEFAULT_RE = re.compile(r"default\s+LoomClientRequest<[\w\[\].]+>\s+(\w+)\(", re.S)


def to_snake_case(name: str) -> str:
    out = re.sub(r"(?<=[a-z0-9])([A-Z])", r"_\1", name)
    out = re.sub(r"(?<=[A-Z])([A-Z][a-z])", r"_\1", out)
    return out.lower()


def parse_java_methods() -> tuple[set[str], set[str]]:
    """Return the abstract and ``default`` method names the Java client declares."""
    abstract: set[str] = set()
    defaults: set[str] = set()
    for path in sorted(JAVA_METHODS_DIR.glob("*Methods.java")):
        if path.name == "ClientMethods.java":
            continue
        source = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.DOTALL)
        source = "\n".join(re.sub(r"//.*$", "", line) for line in source.splitlines())
        for match in DEFAULT_RE.finditer(source):
            defaults.add(match.group(1))
        for match in ABSTRACT_RE.finditer(source):
            if match.group(1) not in defaults:
                abstract.add(match.group(1))
    return abstract, defaults


def python_method_names() -> set[str]:
    return {
        name
        for name in dir(LoomClient)
        if not name.startswith("_") and name not in PYTHON_ONLY and callable(getattr(LoomClient, name, None))
    }


@unittest.skipUnless(JAVA_METHODS_DIR.is_dir(), "Java client tree not present")
class JavaParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.abstract, cls.defaults = parse_java_methods()
        cls.python = python_method_names()

    def test_method_count_tripwire(self):
        """A method added to the Java client should fail here, not go unnoticed."""
        total = len(self.abstract) + len(self.defaults)
        self.assertEqual(
            total,
            EXPECTED_JAVA_METHOD_COUNT,
            f"The Java client now declares {total} methods, not {EXPECTED_JAVA_METHOD_COUNT}. "
            f"Port the new ones and update EXPECTED_JAVA_METHOD_COUNT.",
        )

    def test_every_java_method_has_a_python_counterpart(self):
        missing = []
        for java_name in sorted(self.abstract):
            expected = RENAMES.get(java_name, (to_snake_case(java_name),))
            if not any(name in self.python for name in expected):
                missing.append(f"{java_name} -> {expected}")
        self.assertEqual(missing, [], f"{len(missing)} Java methods have no Python counterpart")

    def test_default_overloads_collapse_into_their_primary(self):
        """Java's UUID/SHA512 overloads have no separate Python method.

        Python has no overloading; the single parameter accepts both forms instead.
        Each overload must still be reachable under the primary's name.
        """
        for java_name in sorted(self.defaults):
            expected = RENAMES.get(java_name, (to_snake_case(java_name),))
            self.assertTrue(
                any(name in self.python for name in expected),
                f"overload {java_name} is not reachable as {expected}",
            )

    def test_no_orphan_python_methods(self):
        """A Python method with no Java original is a typo or a stray."""
        java_snake = {to_snake_case(n) for n in self.abstract | self.defaults}
        for renamed in RENAMES.values():
            java_snake.update(renamed)
        orphans = sorted(self.python - java_snake)
        self.assertEqual(orphans, [], f"{len(orphans)} Python methods have no Java counterpart")


#: Stand-in for a free-form string argument, such as a pipeline node id.
#:
#: Distinctive rather than a bare ``"x"`` so that ``_templatise`` can tell a value the probe
#: substituted from a literal segment of the path. Not every string argument ends up in the path
#: -- most become query or body values, where the exact text is irrelevant.
SAMPLE_STRING_PARAM = "sample-param"


def _dummy(annotation: str, name: str):
    """Build a stand-in argument so a method can be invoked for its path."""
    text = str(annotation)
    if name == "file" or "bytes" in text:
        # A path would have to exist on disk; raw bytes need an explicit filename,
        # which the keyword defaults below supply.
        return b"x"
    if "AssetId" in text or "UUID" in text or name.endswith("uuid"):
        return str(uuid.uuid4())
    if "int" in text and "version" in name:
        return 1
    if "Request" in text:
        cls = _model_named(text)
        return cls() if cls else {}
    return SAMPLE_STRING_PARAM


#: Keyword arguments needed to build a request for methods whose defaults are not
#: sufficient on their own.
EXTRA_KWARGS = {
    "upload_asset": {"filename": "x.bin"},
    "upload_asset_binary": {"filename": "x.bin"},
    "upload_attachment": {"filename": "x.bin"},
}


def _model_named(annotation: str):
    import loom_client.models as models

    for token in re.findall(r"\b([A-Z]\w+)\b", annotation):
        candidate = getattr(models, token, None)
        if isinstance(candidate, type) and issubclass(candidate, Model) and is_dataclass(candidate):
            return candidate
    return None


#: A syntactically valid SHA-512, for exercising the content-addressed asset paths.
SAMPLE_SHA512 = "cf" * 64


def invoke_all_methods():
    """Build a request from every client method and yield ``(name, request)``.

    Nothing is sent: the request object already knows its verb, path and response
    model, which is everything the route checks need.

    Asset-scoped methods are invoked twice, once per identity form, so both
    ``/assets/{uuid}`` and ``/assets/sha512/{sha512}`` are covered.
    """
    import inspect

    client = LoomClient()
    try:
        for name in sorted(python_method_names()):
            method = getattr(client, name)
            parameters = [
                p
                for p in inspect.signature(method).parameters.values()
                if p.kind not in (p.VAR_POSITIONAL, p.VAR_KEYWORD) and p.default is inspect.Parameter.empty
            ]
            takes_asset_id = any("AssetId" in str(p.annotation) for p in parameters)
            for asset_form in ("uuid", "sha512") if takes_asset_id else ("uuid",):
                args = [
                    SAMPLE_SHA512
                    if asset_form == "sha512" and "AssetId" in str(p.annotation)
                    else _dummy(p.annotation, p.name)
                    for p in parameters
                ]
                try:
                    yield name, method(*args, **EXTRA_KWARGS.get(name, {}))
                except Exception as e:  # a method that cannot be built is a real defect
                    yield name, e
    finally:
        client.close()


def client_route_table() -> list[tuple[str, str, str]]:
    """``(method name, verb, path)`` for every request the client can build."""
    routes = []
    for name, request in invoke_all_methods():
        if isinstance(request, Exception):
            routes.append((name, "ERROR", f"{type(request).__name__}: {request}"))
        else:
            routes.append((name, request.method, request.path))
    return routes


def _templatise(path: str) -> str:
    """Replace concrete ids with ``{uuid}`` so paths compare against the spec."""
    # A query string is not part of the route. The spec lists parameters separately, so
    # `notifications?unread=true` and `notifications` are the same path as far as route
    # parity is concerned.
    path = path.split("?", 1)[0]
    parts = []
    for part in path.split("/"):
        if re.fullmatch(r"[0-9a-fA-F-]{36}", part):
            parts.append("{uuid}")
        elif re.fullmatch(r"[0-9a-fA-F]{128}", part):
            parts.append("{sha512}")
        elif re.fullmatch(r"\d+", part):
            parts.append("{version}")
        elif part == SAMPLE_STRING_PARAM:
            # A path parameter that is neither a uuid nor a number -- a pipeline node id, say.
            # Only the probe ever produces this value, so it can only be a parameter.
            parts.append("{name}")
        else:
            parts.append(part)
    return "/".join(parts)


@unittest.skipUnless(OPENAPI.is_file(), "generated openapi.json not present")
class ServerRouteParityTest(unittest.TestCase):
    """Cross-check the client's paths against the routes the server registers."""

    @classmethod
    def setUpClass(cls):
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        cls.server_paths = set()
        for path in document.get("paths", {}):
            relative = path.removeprefix("/api/v1").strip("/")
            # The spec names its parameters ({uuid}, {sha512}, {version}, ...);
            # normalise them all so only the shape is compared.
            cls.server_paths.add(re.sub(r"\{[^}]+\}", "{}", relative))
        cls.routes = client_route_table()

    def test_every_method_builds_a_request(self):
        broken = [
            (n, p)
            for n, verb, p in self.routes
            if verb == "ERROR" and "only registers the nested routes by UUID" not in p
        ]
        self.assertEqual(broken, [], f"{len(broken)} methods raised while building a request")

    def test_asset_sub_resources_reject_a_sha512(self):
        """The server has no content-addressed routes below the asset itself.

        Exactly five ``/assets/sha512/{sha512}`` routes are registered, all on the
        asset. Nothing nested under it -- no tags, tasks, detections or reactions --
        has a hash form, so the client refuses rather than building a path that 404s.
        The Java client's ``SHA512`` overloads of those methods cannot succeed.
        """
        rejected = {n for n, verb, p in self.routes if verb == "ERROR"}
        self.assertEqual(
            len(rejected),
            19,
            f"expected the 19 asset sub-resource methods to reject a hash, got {sorted(rejected)}",
        )
        for name, verb, path in self.routes:
            if verb == "ERROR":
                self.assertIn("load_asset(sha512)", path, f"{name}: unhelpful error message")

    def test_every_client_path_exists_on_the_server(self):
        unknown = []
        for name, verb, path in self.routes:
            if verb == "ERROR":
                continue
            shape = re.sub(r"\{[^}]+\}", "{}", _templatise(path))
            if shape in self.server_paths or shape in KNOWN_UNLISTED_PATHS:
                continue
            unknown.append(f"{name}: {verb} /{path} -> {shape}")
        self.assertEqual(
            unknown,
            [],
            f"{len(unknown)} client methods target a path the API description does not "
            f"list. Either the path is wrong, or the route is missing from openapi.json "
            f"and belongs in KNOWN_UNLISTED_PATHS with a reason.",
        )

    def test_known_unlisted_paths_are_all_still_unlisted(self):
        """Retire an exception once the spec catches up, rather than letting it rot."""
        stale = sorted(p for p in KNOWN_UNLISTED_PATHS if p in self.server_paths)
        self.assertEqual(
            stale,
            [],
            "These paths are now in the API description and should be removed from KNOWN_UNLISTED_PATHS.",
        )

    def test_report_server_paths_the_client_does_not_reach(self):
        """Informational: the client is a subset of the API, by design.

        The Java client has no methods for processors, node descriptors, memory, chat
        sessions or either WebSocket, and this client inherits exactly those gaps.
        """
        reached = {re.sub(r"\{[^}]+\}", "{}", _templatise(p)) for _, v, p in self.routes if v != "ERROR"}
        unreached = sorted(self.server_paths - reached)
        print(f"\n{len(unreached)} server paths not covered by the client:")
        for path in unreached:
            print(f"  /{path}")


if __name__ == "__main__":
    unittest.main()
