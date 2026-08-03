"""Byte-exact multipart encoding.

Multipart is hand-rolled, so the body layout is asserted literally: a subtly wrong
boundary or a missing CRLF produces an upload the server rejects with no useful
explanation.
"""

from __future__ import annotations

import os
import tempfile
import unittest

from loom_client import multipart
from loom_client.errors import LoomError


class EncodeTest(unittest.TestCase):
    def _split(self, body: bytes, content_type: str) -> list[str]:
        boundary = content_type.split("boundary=")[1]
        return body.decode("utf-8", "replace").split(f"--{boundary}")

    def test_file_part_is_named_file(self):
        # All three upload routes expect the part to be named exactly "file".
        body, content_type = multipart.encode(b"data", filename="a.txt")
        self.assertIn(b'name="file"', body)
        self.assertIn(b'filename="a.txt"', body)
        self.assertTrue(content_type.startswith("multipart/form-data; boundary=----loom"))

    def test_exact_layout(self):
        body, content_type = multipart.encode(
            b"BYTES", filename="a.txt", mime_type="text/plain", fields=[("libraryUuid", "lib1")]
        )
        boundary = content_type.split("boundary=")[1]
        self.assertEqual(
            body,
            (
                f"--{boundary}\r\n"
                'Content-Disposition: form-data; name="libraryUuid"\r\n\r\n'
                "lib1\r\n"
                f"--{boundary}\r\n"
                'Content-Disposition: form-data; name="file"; filename="a.txt"\r\n'
                "Content-Type: text/plain\r\n\r\n"
                "BYTES\r\n"
                f"--{boundary}--\r\n"
            ).encode(),
        )

    def test_fields_precede_the_file_and_keep_their_order(self):
        body, content_type = multipart.encode(
            b"x", filename="a", fields=[("libraryUuid", "l1"), ("poolUuid", "p1")]
        )
        parts = self._split(body, content_type)
        self.assertIn("libraryUuid", parts[1])
        self.assertIn("poolUuid", parts[2])
        self.assertIn('name="file"', parts[3])

    def test_none_valued_fields_are_skipped(self):
        # Optional parameters such as poolUuid are omitted by passing None, matching
        # the Java client's behaviour for a null form field.
        body, _ = multipart.encode(b"x", filename="a", fields=[("libraryUuid", "l1"), ("poolUuid", None)])
        self.assertIn(b"libraryUuid", body)
        self.assertNotIn(b"poolUuid", body)

    def test_default_mime_type(self):
        body, _ = multipart.encode(b"x", filename="a")
        self.assertIn(b"Content-Type: application/octet-stream", body)

    def test_boundary_is_random_per_call(self):
        # A fixed boundary silently corrupts any upload whose bytes contain it.
        _, first = multipart.encode(b"x", filename="a")
        _, second = multipart.encode(b"x", filename="a")
        self.assertNotEqual(first, second)

    def test_quotes_in_filenames_cannot_break_out_of_the_header(self):
        body, _ = multipart.encode(b"x", filename='evil".jpg')
        self.assertIn(b'filename="evil%22.jpg"', body)

    def test_newlines_in_filenames_are_stripped(self):
        body, _ = multipart.encode(b"x", filename="a\r\nContent-Type: text/html")
        self.assertNotIn(b"text/html\r\n\r\n", body)
        self.assertIn(b'filename="aContent-Type: text/html"', body)

    def test_binary_content_survives_intact(self):
        payload = bytes(range(256))
        body, _ = multipart.encode(payload, filename="a.bin")
        self.assertIn(payload, body)

    def test_oversized_upload_raises_a_clear_error(self):
        original = multipart.MAX_UPLOAD_BYTES
        multipart.MAX_UPLOAD_BYTES = 10
        self.addCleanup(setattr, multipart, "MAX_UPLOAD_BYTES", original)
        with self.assertRaises(LoomError) as caught:
            multipart.encode(b"x" * 11, filename="a")
        self.assertIn("MAX_UPLOAD_BYTES", str(caught.exception))


class EncodeFileTest(unittest.TestCase):
    def setUp(self):
        handle, self.path = tempfile.mkstemp(suffix=".txt")
        with os.fdopen(handle, "wb") as f:
            f.write(b"file content")
        self.addCleanup(os.unlink, self.path)

    def test_basename_is_used_as_the_filename(self):
        body, _ = multipart.encode_file(self.path)
        self.assertIn(os.path.basename(self.path).encode(), body)
        self.assertIn(b"file content", body)

    def test_filename_can_be_overridden(self):
        body, _ = multipart.encode_file(self.path, filename="renamed.txt")
        self.assertIn(b'filename="renamed.txt"', body)

    def test_oversized_file_is_rejected_before_being_read(self):
        original = multipart.MAX_UPLOAD_BYTES
        multipart.MAX_UPLOAD_BYTES = 1
        self.addCleanup(setattr, multipart, "MAX_UPLOAD_BYTES", original)
        with self.assertRaises(LoomError):
            multipart.encode_file(self.path)


if __name__ == "__main__":
    unittest.main()
