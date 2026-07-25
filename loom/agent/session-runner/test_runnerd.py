#!/usr/bin/env python3
"""Tests for runnerd's memory stage handling.

Run with: python3 -m unittest discover -s loom/agent/session-runner

These cover the two properties that matter for the read-only memory folder: the sync is
idempotent (so a note written mid-run shows up, and a deleted one disappears), and the memory
path guard is separate from — and does not widen — the workspace guard.
"""
import importlib
import os
import sys
import tempfile
import unittest


def load_runnerd(workspace, stage):
    """Import runnerd with the given roots. The module reads them at import time."""
    os.environ["RUNNER_WORKSPACE"] = workspace
    if stage is None:
        os.environ.pop("RUNNER_MEMORY_STAGE", None)
    else:
        os.environ["RUNNER_MEMORY_STAGE"] = stage
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    if "runnerd" in sys.modules:
        del sys.modules["runnerd"]
    return importlib.import_module("runnerd")


class MemoryStageTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.workspace = os.path.join(self.tmp.name, "workspace")
        self.stage = os.path.join(self.tmp.name, "memory")
        os.makedirs(self.workspace)
        os.makedirs(self.stage)
        self.runnerd = load_runnerd(self.workspace, self.stage)
        self.handler = self.runnerd.Handler.__new__(self.runnerd.Handler)

    def tearDown(self):
        self.tmp.cleanup()

    def sync(self, files, prune=True):
        return self.handler._memory_sync({"files": files, "prune": prune})

    def read(self, rel):
        with open(os.path.join(self.stage, rel), encoding="utf-8") as f:
            return f.read()

    # -- path guard ------------------------------------------------------

    def test_rejects_traversal(self):
        for bad in ["../escape.md", "a/../../escape.md", "/etc/passwd"]:
            with self.assertRaises(ValueError, msg=bad):
                self.runnerd._safe_memory_path(bad)

    def test_rejects_empty_path(self):
        with self.assertRaises(ValueError):
            self.runnerd._safe_memory_path("")

    def test_workspace_guard_cannot_reach_the_memory_stage(self):
        # The workspace tools must stay confined to /workspace even though both roots exist.
        with self.assertRaises(ValueError):
            self.runnerd._safe_path(os.path.relpath(self.stage, self.workspace))

    # -- sync ------------------------------------------------------------

    def test_writes_nested_files(self):
        result = self.sync([
            {"path": "user/notes.md", "content": "hello"},
            {"path": "user/projects/loom-db.md", "content": "db notes"},
        ])
        self.assertTrue(result["ok"])
        self.assertEqual(2, result["files"])
        self.assertEqual("hello", self.read("user/notes.md"))
        self.assertEqual("db notes", self.read("user/projects/loom-db.md"))

    def test_sync_is_idempotent_and_files_stay_rewritable(self):
        # The second sync must be able to reopen the files it wrote — a read-only stage
        # would break every refresh after the first.
        self.sync([{"path": "user/notes.md", "content": "v1"}])
        self.sync([{"path": "user/notes.md", "content": "v2"}])
        self.assertEqual("v2", self.read("user/notes.md"))

    def test_prune_removes_notes_that_are_gone(self):
        self.sync([
            {"path": "user/keep.md", "content": "a"},
            {"path": "user/drop.md", "content": "b"},
        ])
        result = self.sync([{"path": "user/keep.md", "content": "a"}])

        self.assertEqual(1, result["pruned"])
        self.assertTrue(os.path.exists(os.path.join(self.stage, "user/keep.md")))
        self.assertFalse(os.path.exists(os.path.join(self.stage, "user/drop.md")))

    def test_prune_removes_emptied_directories(self):
        self.sync([{"path": "user/projects/a.md", "content": "a"}])
        self.sync([{"path": "user/notes.md", "content": "b"}])
        self.assertFalse(os.path.exists(os.path.join(self.stage, "user/projects")))

    def test_prune_can_be_disabled(self):
        self.sync([{"path": "user/keep.md", "content": "a"}])
        result = self.sync([{"path": "user/new.md", "content": "b"}], prune=False)
        self.assertEqual(0, result["pruned"])
        self.assertTrue(os.path.exists(os.path.join(self.stage, "user/keep.md")))

    def test_rejects_too_many_files(self):
        files = [{"path": f"user/n{i}.md", "content": "x"} for i in range(self.runnerd.MEMORY_MAX_FILES + 1)]
        with self.assertRaises(ValueError):
            self.sync(files)

    def test_rejects_oversized_payload(self):
        big = "x" * (self.runnerd.MEMORY_MAX_BYTES // 2 + 1)
        with self.assertRaises(ValueError):
            self.sync([{"path": "user/a.md", "content": big}, {"path": "user/b.md", "content": big}])

    def test_rejects_traversal_inside_a_sync(self):
        with self.assertRaises(ValueError):
            self.sync([{"path": "../escape.md", "content": "x"}])


class MemoryDisabledTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.runnerd = load_runnerd(self.tmp.name, None)

    def tearDown(self):
        self.tmp.cleanup()

    def test_memory_is_off_without_the_stage_env(self):
        self.assertEqual("", self.runnerd.MEMORY_STAGE)
        with self.assertRaises(ValueError):
            self.runnerd._safe_memory_path("user/notes.md")


if __name__ == "__main__":
    unittest.main()
