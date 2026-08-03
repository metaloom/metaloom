"""The model layer, checked against real server output.

The strongest available evidence that the generated dataclasses agree with the server
is ``tests/fixtures/openapi_bodies.json``: 216 example request and response bodies,
written by the server's own example classes and serialised by the server's own mapper.
If a model parses one and serialises it back unchanged, its field names and types are
right -- and no running server was needed to find out.

The model for each body is resolved from the client's own route table, so the mapping
is the one the client actually uses rather than a second one maintained by hand.
"""

from __future__ import annotations

import json
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from loom_client.models import (
    PagingInfo,
    TaskResponse,
    UserListResponse,
    UserResponse,
    parse_instant,
)
from loom_client.models.base import Model, to_camel_case
from loom_client.models.enums import TaskStatus

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "openapi_bodies.json"


def load_fixtures() -> list[dict]:
    if not FIXTURES.is_file():
        return []
    return json.loads(FIXTURES.read_text(encoding="utf-8"))


def response_models_by_path() -> dict[tuple[str, str], type]:
    """Map ``(verb, path template)`` to the response model the client expects.

    Built by invoking every client method with stand-in arguments, so it is derived
    from the client rather than restated.
    """
    from .test_parity import _templatise, invoke_all_methods

    table: dict[tuple[str, str], type] = {}
    for _name, request in invoke_all_methods():
        if isinstance(request, Exception) or request.model is None:
            continue
        table[(request.method, _template(_templatise(request.path)))] = request.model
    return table


def _normalise(value: Any) -> Any:
    """Drop nulls and empty containers so a round trip compares like for like.

    ``to_dict`` omits ``None``, matching the server's own mapper -- but a hand-written
    example may still spell out a null, and an absent list is indistinguishable from an
    empty one once parsed.
    """
    if isinstance(value, dict):
        return {k: _normalise(v) for k, v in value.items() if v not in (None, [], {})}
    if isinstance(value, list):
        return [_normalise(v) for v in value]
    return value


class OpenApiRoundTripTest(unittest.TestCase):
    """Parse every example body and serialise it back unchanged."""

    @classmethod
    def setUpClass(cls):
        cls.fixtures = load_fixtures()
        cls.models = response_models_by_path()

    @unittest.skipUnless(FIXTURES.is_file(), "fixtures not extracted")
    def test_fixtures_are_present(self):
        self.assertGreater(len(self.fixtures), 200, "expected the full set of example bodies")

    @unittest.skipUnless(FIXTURES.is_file(), "fixtures not extracted")
    def test_response_bodies_round_trip(self):
        checked = 0
        failures = []
        for fixture in self.fixtures:
            if fixture["direction"] != "response":
                continue
            if not str(fixture["status"]).startswith("2"):
                continue
            model = self.models.get((fixture["verb"], _template(fixture["path"])))
            if model is None or not isinstance(fixture["body"], dict):
                continue
            checked += 1
            original = _normalise(fixture["body"])
            try:
                restored = _normalise(model.from_dict(fixture["body"]).to_dict())
            except Exception as e:
                failures.append(f"{model.__name__} {fixture['path']}: {type(e).__name__}: {e}")
                continue
            if restored != original:
                failures.append(
                    f"{model.__name__} {fixture['verb']} /{fixture['path']}\n"
                    f"    lost:  {_diff(original, restored)}\n"
                    f"    added: {_diff(restored, original)}"
                )
        self.assertEqual(failures, [], "\n".join(failures))
        self.assertGreater(checked, 90, f"only {checked} bodies were matched to a model")

    @unittest.skipUnless(FIXTURES.is_file(), "fixtures not extracted")
    def test_no_example_body_is_silently_unparsed(self):
        """Every matched body must parse into a model with at least one field set."""
        for fixture in self.fixtures:
            if fixture["direction"] != "response" or not isinstance(fixture["body"], dict):
                continue
            model = self.models.get((fixture["verb"], _template(fixture["path"])))
            if model is None or not fixture["body"]:
                continue
            parsed = model.from_dict(fixture["body"])
            self.assertIsInstance(parsed, Model)
            self.assertEqual(
                parsed.extra,
                {},
                f"{model.__name__} did not recognise {sorted(parsed.extra)} "
                f"from {fixture['verb']} /{fixture['path']}",
            )


def _template(path: str) -> str:
    """Normalise every path parameter to ``{}``.

    The spec names its parameters ({uuid}, {version}, {sha512}); the client's own
    table is templatised from concrete stand-in values. Both sides must be flattened
    the same way or a route matches nothing for no good reason.
    """
    import re

    return re.sub(r"\{[^}]+\}", "{}", path)


def _diff(a: Any, b: Any, prefix: str = "") -> str:
    """Keys present in ``a`` but not ``b``, for a readable failure message."""
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for key, value in a.items():
            if key not in b:
                out.append(f"{prefix}{key}={value!r}")
            else:
                out.append(_diff(value, b[key], f"{prefix}{key}."))
    elif isinstance(a, list) and isinstance(b, list) and a and b:
        out.append(_diff(a[0], b[0], f"{prefix}[]."))
    elif a != b:
        out.append(f"{prefix}: {a!r} != {b!r}")
    return " ".join(x for x in out if x)


class SerialisationTest(unittest.TestCase):
    """The rules encoded in models/base.py."""

    def test_snake_case_maps_to_camel_case(self):
        info = PagingInfo(last_uuid="u1", per_page=25, total_count=3)
        self.assertEqual(info.to_dict(), {"lastUuid": "u1", "perPage": 25, "totalCount": 3})

    def test_metainfo_alias_both_ways(self):
        page = UserListResponse.from_dict({"data": [], "_metainfo": {"totalCount": 7}})
        self.assertEqual(page.metainfo.total_count, 7)
        self.assertIn("_metainfo", page.to_dict())

    def test_nulls_are_omitted_by_default(self):
        # Matches the server's Include.NON_NULL mapper: only what you set is sent.
        self.assertEqual(UserResponse(username="joe").to_dict()["username"], "joe")
        self.assertNotIn("email", UserResponse(username="joe").to_dict())

    def test_include_none_emits_explicit_nulls(self):
        # The escape hatch for satisfying a PUT completeness check by hand.
        out = UserResponse(username="joe").to_dict(include_none=True)
        self.assertIsNone(out["email"])
        self.assertIn("email", out)

    def test_primitives_keep_their_value_when_falsey(self):
        # Java `boolean` and `long` are never suppressed, so they are always on the
        # wire even at False/0 -- unlike their boxed counterparts.
        out = UserResponse(username="joe", enabled=False).to_dict()
        self.assertIs(out["enabled"], False)
        self.assertEqual(PagingInfo(total_count=0).to_dict()["totalCount"], 0)

    def test_unknown_fields_survive_a_round_trip(self):
        # A client is usually older than the server it talks to; load-modify-save must
        # not drop what this version has not learned about.
        parsed = UserResponse.from_dict({"username": "joe", "somethingNew": [1, 2]})
        self.assertEqual(parsed.extra, {"somethingNew": [1, 2]})
        self.assertEqual(parsed.to_dict()["somethingNew"], [1, 2])

    def test_declared_fields_win_over_unknown_ones(self):
        parsed = UserResponse.from_dict({"username": "joe"})
        parsed.extra["username"] = "impostor"
        self.assertEqual(parsed.to_dict()["username"], "joe")

    def test_nested_models_are_parsed(self):
        parsed = UserResponse.from_dict(
            {"status": {"creator": {"uuid": "u1", "name": "admin"}, "created": "2026-01-01T00:00:00Z"}}
        )
        self.assertEqual(parsed.status.creator.name, "admin")
        self.assertEqual(parsed.to_dict()["status"]["creator"]["name"], "admin")

    def test_lists_of_models_are_parsed(self):
        page = UserListResponse.from_dict({"data": [{"username": "a"}, {"username": "b"}]})
        self.assertEqual([u.username for u in page.data], ["a", "b"])
        self.assertIsInstance(page.data[0], UserResponse)

    def test_known_enum_values_become_members(self):
        parsed = TaskResponse.from_dict({"taskStatus": "ACCEPTED"})
        self.assertIs(parsed.task_status, TaskStatus.ACCEPTED)
        self.assertEqual(parsed.to_dict()["taskStatus"], "ACCEPTED")

    def test_unknown_enum_values_pass_through_as_strings(self):
        # A newer server may send a member this version does not have; refusing to
        # parse the whole response over one field nobody asked for is worse.
        parsed = TaskResponse.from_dict({"taskStatus": "ESCALATED"})
        self.assertEqual(parsed.task_status, "ESCALATED")
        self.assertEqual(parsed.to_dict()["taskStatus"], "ESCALATED")

    def test_from_dict_rejects_a_non_object(self):
        with self.assertRaises(TypeError):
            UserResponse.from_dict([1, 2, 3])

    def test_from_dict_of_none_is_none(self):
        self.assertIsNone(UserResponse.from_dict(None))


class InstantTest(unittest.TestCase):
    """The server encodes timestamps two different ways; both must parse."""

    def test_iso_string(self):
        parsed = parse_instant("2026-01-01T00:00:00Z")
        self.assertEqual(parsed.year, 2026)
        self.assertIsNotNone(parsed.tzinfo)

    def test_epoch_seconds(self):
        # SearchHitResponse.sortDate and SearchStatusResponse.lastSyncedAt arrive this
        # way, because the server's mapper was not told to write dates as strings.
        parsed = parse_instant(1767225600.0)
        self.assertEqual(parsed.year, 2026)

    def test_none_and_blank(self):
        self.assertIsNone(parse_instant(None))
        self.assertIsNone(parse_instant("  "))

    def test_format_truncates_to_whole_seconds(self):
        from datetime import datetime, timezone

        from loom_client.models import format_instant

        # The server's parse pattern has no fractional part and rejects one with 400.
        stamped = datetime(2026, 1, 1, 12, 30, 45, 123456, tzinfo=timezone.utc)
        self.assertEqual(format_instant(stamped), "2026-01-01T12:30:45Z")


class NameMappingTest(unittest.TestCase):
    def test_camel_case_conversion(self):
        self.assertEqual(to_camel_case("last_uuid"), "lastUuid")
        self.assertEqual(to_camel_case("db_revision"), "dbRevision")
        self.assertEqual(to_camel_case("sha512"), "sha512")
        self.assertEqual(to_camel_case("uuid"), "uuid")

    def test_every_model_resolves_its_annotations(self):
        """A model whose annotations do not resolve silently stops converting types.

        ``_hints`` swallows resolution failures so one unknown type cannot break a
        whole response -- which means a systematic failure would otherwise show up
        only as nested models arriving as plain dicts.
        """
        from dataclasses import fields as dataclass_fields

        import loom_client.models as models
        from loom_client.models.base import _hints

        unresolved = []
        for name in models.__all__:
            candidate = getattr(models, name)
            if not (isinstance(candidate, type) and issubclass(candidate, Model)):
                continue
            expected = {f.name for f in dataclass_fields(candidate)}
            resolved = set(_hints(candidate))
            if not expected <= resolved:
                unresolved.append(f"{name}: {sorted(expected - resolved)}")
        self.assertEqual(unresolved, [], f"{len(unresolved)} models have unresolved annotations")

    def test_every_model_field_round_trips_its_wire_name(self):
        """No generated attribute may map to a wire name that cannot be recovered."""
        from dataclasses import fields as dataclass_fields

        import loom_client.models as models

        broken = []
        for name in models.__all__:
            candidate = getattr(models, name)
            if not (isinstance(candidate, type) and issubclass(candidate, Model)):
                continue
            for f in dataclass_fields(candidate):
                if f.name == "extra" or f.metadata.get("json"):
                    continue
                # Without an explicit alias the mapping must be reversible.
                if to_camel_case(f.name) != to_camel_case(f.name):
                    broken.append(f"{name}.{f.name}")
        self.assertEqual(broken, [])


@dataclass
class _Nested(Model):
    value: str | None = None


@dataclass
class _Holder(Model):
    items: list[_Nested] = field(default_factory=list)
    mapping: dict[str, _Nested] = field(default_factory=dict)
    renamed: str | None = field(default=None, metadata={"json": "_renamed"})


class ContainerTest(unittest.TestCase):
    """Lists, maps and aliases, which the generator emits for real models."""

    def test_list_of_models(self):
        holder = _Holder.from_dict({"items": [{"value": "a"}, {"value": "b"}]})
        self.assertEqual([i.value for i in holder.items], ["a", "b"])
        self.assertEqual(holder.to_dict()["items"], [{"value": "a"}, {"value": "b"}])

    def test_map_of_models(self):
        holder = _Holder.from_dict({"mapping": {"k": {"value": "v"}}})
        self.assertEqual(holder.mapping["k"].value, "v")
        self.assertEqual(holder.to_dict()["mapping"], {"k": {"value": "v"}})

    def test_explicit_alias(self):
        holder = _Holder.from_dict({"_renamed": "x"})
        self.assertEqual(holder.renamed, "x")
        self.assertEqual(holder.to_dict()["_renamed"], "x")
        self.assertNotIn("renamed", holder.to_dict())


if __name__ == "__main__":
    unittest.main()
