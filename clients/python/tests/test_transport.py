"""Transport-level behaviour: URLs, verbs, headers, query encoding."""

from __future__ import annotations

import unittest

from loom_client import LoomClient
from loom_client.filters import eq, gte
from loom_client.params import SortDirection, SortKey

from .stubserver import StubServerTestCase


class UrlAssemblyTest(unittest.TestCase):
    """URL joining, which is where a hand-rolled client usually goes wrong first."""

    def setUp(self) -> None:
        self.client = LoomClient(host="example.org", port=8092)
        self.addCleanup(self.client.close)

    def test_plain_path(self):
        url = self.client._transport.url_for("users")
        self.assertEqual(url, "http://example.org:8092/api/v1/users")

    def test_leading_slash_is_equivalent(self):
        # 20 of the Java client's call sites pass "/tags" and the rest pass "tags".
        # OkHttp absorbs the difference; a naive string join would emit "/api/v1//tags".
        plain = self.client._get("tags", None)
        slashed = self.client._get("/tags", None)
        self.assertEqual(plain.path, slashed.path)
        self.assertEqual(
            self.client._transport.url_for(slashed.path),
            "http://example.org:8092/api/v1/tags",
        )

    def test_empty_path_targets_the_api_root_without_a_trailing_slash(self):
        self.assertEqual(
            self.client._transport.url_for(""),
            "http://example.org:8092/api/v1",
        )

    def test_path_prefix(self):
        client = LoomClient(host="example.org", port=443, scheme="https", path_prefix="/loom/")
        self.addCleanup(client.close)
        self.assertEqual(
            client._transport.url_for("users"),
            "https://example.org:443/loom/api/v1/users",
        )

    def test_query_parameters_are_appended(self):
        url = self.client._transport.url_for("users", [("limit", "5"), ("sort", "username")])
        self.assertEqual(url, "http://example.org:8092/api/v1/users?limit=5&sort=username")


class VerbTest(StubServerTestCase):
    def test_get(self):
        self.stub.enqueue(json_body={"version": "1.2.3"})
        info = self.client.rest_info().body()
        self.assertEqual(info.version, "1.2.3")
        self.assertEqual(self.stub.last.method, "GET")
        self.assertEqual(self.stub.last.path, "/api/v1")

    def test_post_sends_json(self):
        from loom_client.models import UserCreateRequest

        self.stub.enqueue(201, json_body={"uuid": "u1", "username": "joe"})
        response = self.client.create_user(UserCreateRequest(username="joe")).execute()
        self.assertEqual(response.status, 201)
        self.assertEqual(response.body.username, "joe")
        recorded = self.stub.last
        self.assertEqual(recorded.method, "POST")
        self.assertEqual(recorded.path, "/api/v1/users")
        self.assertEqual(recorded.header("Content-Type"), "application/json")
        self.assertEqual(recorded.json, {"username": "joe"})

    def test_patch_is_sent_as_patch(self):
        from loom_client.models import UserUpdateRequest

        self.stub.enqueue(json_body={"uuid": "u1"})
        self.client.patch_user("u1", UserUpdateRequest(email="a@b.c")).body()
        self.assertEqual(self.stub.last.method, "PATCH")
        self.assertEqual(self.stub.last.json, {"email": "a@b.c"})

    def test_put_is_sent_as_put(self):
        from loom_client.models import UserUpdateRequest

        self.stub.enqueue(json_body={"uuid": "u1"})
        self.client.replace_user("u1", UserUpdateRequest(username="joe")).body()
        self.assertEqual(self.stub.last.method, "PUT")

    def test_delete_returns_none_on_204(self):
        self.stub.enqueue(204)
        response = self.client.delete_user("u1").execute()
        self.assertEqual(response.status, 204)
        self.assertIsNone(response.body)
        self.assertEqual(self.stub.last.method, "DELETE")

    def test_body_less_post_sets_content_length_zero(self):
        # Several routes act on the path alone. Sending no body at all would omit
        # Content-Length entirely.
        self.stub.enqueue(json_body={})
        self.client._post_empty("assets/a1/tasks/t1", None).execute()
        self.assertEqual(self.stub.last.header("Content-Length"), "0")
        self.assertEqual(self.stub.last.body, b"")

    def test_null_fields_are_omitted_from_the_body(self):
        from loom_client.models import UserCreateRequest

        self.stub.enqueue(json_body={})
        self.client.create_user(UserCreateRequest(username="joe", email=None)).execute()
        self.assertEqual(self.stub.last.json, {"username": "joe"})


class HeaderTest(StubServerTestCase):
    def test_no_authorization_header_before_a_token_is_set(self):
        self.stub.enqueue(json_body={})
        self.client.rest_info().execute()
        self.assertIsNone(self.stub.last.header("Authorization"))

    def test_bearer_token_is_sent_once_set(self):
        self.stub.enqueue(json_body={})
        self.client.set_token("tok123").rest_info().execute()
        self.assertEqual(self.stub.last.header("Authorization"), "Bearer tok123")

    def test_authenticate_logs_in_and_installs_the_token(self):
        self.stub.enqueue(json_body={"token": "tok456"})
        self.stub.enqueue(json_body={"username": "admin"})
        self.client.authenticate("admin", "finger")
        self.assertEqual(self.client.token, "tok456")
        login = self.stub.requests[0]
        self.assertEqual(login.path, "/api/v1/login")
        self.assertEqual(login.json, {"username": "admin", "password": "finger"})
        self.client.me().execute()
        self.assertEqual(self.stub.last.header("Authorization"), "Bearer tok456")

    def test_accept_header_is_always_json(self):
        self.stub.enqueue(json_body={})
        self.client.rest_info().execute()
        self.assertEqual(self.stub.last.header("Accept"), "application/json")

    def test_response_headers_are_case_insensitive(self):
        self.stub.enqueue(json_body={}, headers={"X-Custom-Thing": "yes"})
        response = self.client.rest_info().execute()
        self.assertEqual(response.header("x-custom-thing"), "yes")
        self.assertEqual(response.header("X-CUSTOM-THING"), "yes")
        self.assertIsNone(response.header("nope"))


class QueryParameterTest(StubServerTestCase):
    def test_limit_sort_and_direction(self):
        self.stub.enqueue(json_body={"data": []})
        self.client.list_users().limit(50).sort(SortKey.USERNAME).direction(SortDirection.DESCENDING).body()
        self.assertEqual(
            self.stub.last.query_pairs,
            [("limit", "50"), ("sort", "username"), ("dir", "DESCENDING")],
        )

    def test_from_seeks(self):
        self.stub.enqueue(json_body={"data": []})
        self.client.list_users().from_("abc-123").body()
        self.assertEqual(self.stub.last.query_pairs, [("from", "abc-123")])

    def test_filters_repeat_rather_than_collapse(self):
        self.stub.enqueue(json_body={"data": []})
        self.client.list_users().filter(eq("username", "joedoe")).filter(gte("size", "1MB")).body()
        self.assertEqual(
            self.stub.last.query_pairs,
            [("filter", "username[eq]=joedoe"), ("filter", "size[gte]=1MB")],
        )

    def test_filter_brackets_are_percent_encoded(self):
        self.stub.enqueue(json_body={"data": []})
        self.client.list_users().filter(eq("name", "a b")).body()
        self.assertIn("filter=name%5Beq%5D%3Da+b", self.stub.last.query)


class RedirectTest(StubServerTestCase):
    def test_redirects_are_surfaced_not_followed(self):
        # Following a 302 would turn a POST into a GET, silently converting a write
        # into a read.
        from loom_client.errors import LoomHttpError

        self.stub.enqueue(302, headers={"Location": "/api/v1/elsewhere"})
        with self.assertRaises(LoomHttpError) as caught:
            self.client.rest_info().execute()
        self.assertEqual(caught.exception.status, 302)
        self.assertEqual(len(self.stub.requests), 1)


class TimeoutTest(unittest.TestCase):
    def test_java_style_timeouts_are_folded_rather_than_ignored(self):
        # urllib has a single timeout where the Java client has three. Ported code
        # passing connect/read timeouts should not have them silently dropped.
        client = LoomClient(timeout=10, connect_timeout=30, read_timeout=20)
        self.addCleanup(client.close)
        self.assertEqual(client._transport.timeout, 30)

    def test_per_request_override(self):
        client = LoomClient(timeout=10)
        self.addCleanup(client.close)
        request = client.rest_info().timeout(600)
        self.assertEqual(request._timeout, 600)


class ConnectionErrorTest(unittest.TestCase):
    def test_refused_connection_raises_a_connection_error(self):
        from loom_client.errors import LoomConnectionError

        # Port 1 on loopback: nothing listens there.
        client = LoomClient(host="127.0.0.1", port=1, timeout=2)
        self.addCleanup(client.close)
        with self.assertRaises(LoomConnectionError):
            client.rest_info().execute()


if __name__ == "__main__":
    unittest.main()
