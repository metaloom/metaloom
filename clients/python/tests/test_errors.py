"""Status-code to exception mapping, and error message extraction."""

from __future__ import annotations

import unittest

from loom_client.errors import (
    LoomBadRequestError,
    LoomConflictError,
    LoomForbiddenError,
    LoomHttpError,
    LoomNotFoundError,
    LoomServerError,
    LoomUnauthorizedError,
)

from .stubserver import StubServerTestCase


class StatusMappingTest(StubServerTestCase):
    def _expect(self, status, error_type):
        self.stub.enqueue(status, json_body={"message": "nope"})
        with self.assertRaises(error_type) as caught:
            self.client.rest_info().execute()
        self.assertEqual(caught.exception.status, status)
        return caught.exception

    def test_400(self):
        self._expect(400, LoomBadRequestError)

    def test_401(self):
        self._expect(401, LoomUnauthorizedError)

    def test_403(self):
        self._expect(403, LoomForbiddenError)

    def test_404(self):
        self._expect(404, LoomNotFoundError)

    def test_409(self):
        self._expect(409, LoomConflictError)

    def test_500(self):
        self._expect(500, LoomServerError)

    def test_503(self):
        self._expect(503, LoomServerError)

    def test_unmapped_status_falls_back_to_the_base_type(self):
        error = self._expect(418, LoomHttpError)
        self.assertIs(type(error), LoomHttpError)

    def test_every_subclass_is_catchable_as_loom_http_error(self):
        # The status attribute is retained so the older `if e.status == 404` idiom
        # keeps working alongside the new subclasses.
        self.stub.enqueue(404, json_body={"message": "gone"})
        with self.assertRaises(LoomHttpError) as caught:
            self.client.rest_info().execute()
        self.assertEqual(caught.exception.status, 404)


class ErrorMessageTest(StubServerTestCase):
    def test_message_comes_from_the_generic_message_response(self):
        self.stub.enqueue(404, json_body={"message": "Could not find user"})
        with self.assertRaises(LoomNotFoundError) as caught:
            self.client.load_user("u1").execute()
        self.assertEqual(caught.exception.message, "Could not find user")

    def test_non_json_error_body_falls_back_to_raw_text(self):
        # A proxy or a crash can return HTML. Parsing must not raise a second
        # exception while handling the first.
        self.stub.enqueue(502, raw=b"<html>Bad Gateway</html>", headers={"Content-Type": "text/html"})
        with self.assertRaises(LoomHttpError) as caught:
            self.client.rest_info().execute()
        self.assertEqual(caught.exception.message, "<html>Bad Gateway</html>")

    def test_json_without_a_message_field_falls_back_to_raw_text(self):
        self.stub.enqueue(400, json_body={"other": "thing"})
        with self.assertRaises(LoomBadRequestError) as caught:
            self.client.rest_info().execute()
        self.assertEqual(caught.exception.message, '{"other": "thing"}')

    def test_empty_error_body_falls_back_to_the_reason_phrase(self):
        self.stub.enqueue(403)
        with self.assertRaises(LoomForbiddenError) as caught:
            self.client.rest_info().execute()
        self.assertEqual(caught.exception.message, "Forbidden")

    def test_str_includes_the_request_and_the_message(self):
        self.stub.enqueue(404, json_body={"message": "Could not find user"})
        with self.assertRaises(LoomNotFoundError) as caught:
            self.client.load_user("u1").execute()
        text = str(caught.exception)
        self.assertIn("GET", text)
        self.assertIn("/api/v1/users/u1", text)
        self.assertIn("404", text)
        self.assertIn("Could not find user", text)


if __name__ == "__main__":
    unittest.main()
