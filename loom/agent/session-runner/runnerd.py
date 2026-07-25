#!/usr/bin/env python3
"""runnerd — the session RPC daemon for a Loom Session Runner container.

This is the *only* process inside a Session Runner container. It is a tiny, stdlib-only
HTTP server that the Loom backend (SandboxClient) drives to run coding tasks in isolation.
It NEVER holds any credentials; it only receives concrete "run this command / write this
file" instructions and returns the output.

Routes (all confined to $RUNNER_WORKSPACE, default /workspace; all require the bearer
token in $RUNNER_TOKEN when that is set):

    GET  /healthz      -> 200 {"ok": true}
    GET  /download?path=...  -> raw file bytes (used by the download/preview proxy)
    POST /exec         {"command": str, "timeout"?: int}
                       -> chunked JSONL: {"stream":"stdout|stderr","data":"..."}\\n ...
                       -> final line:    {"exitCode":int,"truncated":bool,"timedOut":bool,"fullOutputPath":str|null}
    POST /write_file   {"path": str, "content": str}       -> {"ok":true,"bytes":int}
    POST /read_file    {"path": str, "offset"?:int, "limit"?:int} -> {"content":str,"truncated":bool,"totalLines":int}
    POST /list_files   {"path"?: str}                      -> {"entries":[{"name","type","size"}]}
    POST /memory_sync  {"files":[{"path":str,"content":str}], "prune"?:bool}
                       -> {"ok":true,"files":int,"bytes":int,"pruned":int}
                       (404 unless $RUNNER_MEMORY_STAGE is set)

Design notes:
- `/exec` runs `bash -lc <command>` in its own process group (start_new_session) so a
  timeout or abort can SIGKILL the whole tree.
- Output is streamed as it is produced; once the streamed volume passes MAX_BYTES the
  daemon stops streaming (sets truncated) but keeps writing the *full* output to a spill
  file under /workspace/.runnerd and returns its path.
- No third-party dependencies: this must run in a minimal python:3.12-slim image.
"""
from __future__ import annotations

import json
import os
import select
import signal
import subprocess
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WORKSPACE = os.path.realpath(os.environ.get("RUNNER_WORKSPACE", "/workspace"))
TOKEN = os.environ.get("RUNNER_TOKEN", "")
PORT = int(os.environ.get("RUNNER_PORT", "8080"))
SPILL_DIR = os.path.join(WORKSPACE, ".runnerd")

# The read-write side of the memory volume. The same volume is mounted read-only elsewhere
# (see the podman/kubernetes backends), which is what makes the agent-visible memory folder
# genuinely read-only: this daemon is unprivileged and could not enforce that itself.
# Empty => the memory route is disabled entirely.
MEMORY_STAGE = os.path.realpath(os.environ["RUNNER_MEMORY_STAGE"]) if os.environ.get("RUNNER_MEMORY_STAGE") else ""

# Output guards.
MAX_LINES = 2000
MAX_BYTES = 50 * 1024
DEFAULT_TIMEOUT = 120

# Memory sync guards.
MEMORY_MAX_FILES = 2000
MEMORY_MAX_BYTES = 64 * 1024 * 1024


def _safe_path(rel: str) -> str:
    """Resolve `rel` under WORKSPACE, rejecting `..` / absolute escapes."""
    rel = rel or "."
    target = os.path.realpath(os.path.join(WORKSPACE, rel))
    if target != WORKSPACE and not target.startswith(WORKSPACE + os.sep):
        raise ValueError(f"path escapes workspace: {rel!r}")
    return target


def _safe_memory_path(rel: str) -> str:
    """Resolve `rel` under MEMORY_STAGE, rejecting `..` / absolute escapes.

    Deliberately separate from _safe_path: the workspace tools (write_file/read_file/
    list_files/download) must stay confined to /workspace and must never be able to reach
    the memory stage.
    """
    if not MEMORY_STAGE:
        raise ValueError("memory is not enabled for this runner")
    if not rel:
        raise ValueError("a path is required")
    target = os.path.realpath(os.path.join(MEMORY_STAGE, rel))
    if target != MEMORY_STAGE and not target.startswith(MEMORY_STAGE + os.sep):
        raise ValueError(f"path escapes memory stage: {rel!r}")
    return target


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "loom-runnerd/1.0"

    # -- low level helpers -------------------------------------------------
    def log_message(self, fmt, *args):  # keep the container logs quiet/structured
        pass

    def _authed(self) -> bool:
        if not TOKEN:
            return True  # dev mode: no token configured
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length", "0") or 0)
        if not n:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}

    def _json(self, obj: dict, status: int = 200) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _begin_chunked(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

    def _chunk(self, obj: dict) -> None:
        payload = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
        self.wfile.write(f"{len(payload):X}\r\n".encode("ascii"))
        self.wfile.write(payload)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def _end_chunked(self) -> None:
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    # -- routing -----------------------------------------------------------
    def do_GET(self):
        route, _, qs = self.path.partition("?")
        if route == "/healthz":
            self._json({"ok": True})
            return
        if route == "/download":
            if not self._authed():
                self._json({"error": "unauthorized"}, 401)
                return
            rel = (urllib.parse.parse_qs(qs).get("path") or [""])[0]
            try:
                self._download(rel)
            except ValueError as e:
                self._json({"error": str(e)}, 400)
            return
        self._json({"error": "not found"}, 404)

    def _download(self, rel: str) -> None:
        """Stream raw file bytes (for the backend download/preview proxy)."""
        path = _safe_path(rel)
        if not os.path.isfile(path):
            raise ValueError(f"not a file: {rel!r}")
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def do_POST(self):
        if not self._authed():
            self._json({"error": "unauthorized"}, 401)
            return
        route = self.path.split("?", 1)[0]
        try:
            if route == "/exec":
                self._exec(self._body())
            elif route == "/write_file":
                self._json(self._write_file(self._body()))
            elif route == "/read_file":
                self._json(self._read_file(self._body()))
            elif route == "/list_files":
                self._json(self._list_files(self._body()))
            elif route == "/memory_sync":
                if not MEMORY_STAGE:
                    self._json({"error": "not found"}, 404)
                else:
                    self._json(self._memory_sync(self._body()))
            else:
                self._json({"error": "not found"}, 404)
        except ValueError as e:
            self._json({"error": str(e)}, 400)
        except Exception as e:  # pragma: no cover - defensive
            self._json({"error": f"{type(e).__name__}: {e}"}, 500)

    # -- handlers ----------------------------------------------------------
    def _exec(self, body: dict) -> None:
        command = (body.get("command") or "").strip()
        timeout = int(body.get("timeout") or DEFAULT_TIMEOUT)
        if not command:
            self._json({"error": "command is required"}, 400)
            return

        os.makedirs(SPILL_DIR, exist_ok=True)
        spill_path = os.path.join(SPILL_DIR, f"exec-{int(time.time()*1000)}.log")

        proc = subprocess.Popen(
            ["bash", "-lc", command],
            cwd=WORKSPACE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,  # own process group -> killpg on timeout
            bufsize=0,
        )

        self._begin_chunked()
        sent_bytes = 0
        truncated = False
        timed_out = False
        start = time.monotonic()
        fds = {proc.stdout.fileno(): ("stdout", proc.stdout),
               proc.stderr.fileno(): ("stderr", proc.stderr)}
        open_fds = set(fds)

        with open(spill_path, "wb") as spill:
            while open_fds:
                remaining = timeout - (time.monotonic() - start)
                if remaining <= 0:
                    timed_out = True
                    break
                ready, _, _ = select.select(list(open_fds), [], [], min(remaining, 1.0))
                for fd in ready:
                    stream, reader = fds[fd]
                    data = os.read(fd, 65536)
                    if not data:
                        open_fds.discard(fd)
                        continue
                    spill.write(data)  # always keep the full output on disk
                    if not truncated:
                        text = data.decode("utf-8", errors="replace")
                        self._chunk({"stream": stream, "data": text})
                        sent_bytes += len(data)
                        if sent_bytes >= MAX_BYTES:
                            truncated = True
                            self._chunk({"stream": "stdout",
                                         "data": f"\n… [output truncated at {MAX_BYTES} bytes] …\n"})

        if timed_out:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            exit_code = -signal.SIGKILL
        else:
            exit_code = proc.wait()

        self._chunk({
            "exitCode": exit_code,
            "truncated": truncated or timed_out,
            "timedOut": timed_out,
            "fullOutputPath": os.path.relpath(spill_path, WORKSPACE) if (truncated or timed_out) else None,
        })
        self._end_chunked()

    def _write_file(self, body: dict) -> dict:
        path = _safe_path(body.get("path", ""))
        content = body.get("content", "") or ""
        os.makedirs(os.path.dirname(path) or WORKSPACE, exist_ok=True)
        data = content.encode("utf-8")
        with open(path, "wb") as f:
            f.write(data)
        return {"ok": True, "bytes": len(data)}

    def _read_file(self, body: dict) -> dict:
        path = _safe_path(body.get("path", ""))
        offset = int(body.get("offset") or 0)
        limit = int(body.get("limit") or MAX_LINES)
        if not os.path.isfile(path):
            raise ValueError(f"not a file: {body.get('path')!r}")
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().split("\n")
        window = lines[offset:offset + limit]
        truncated = (offset + limit) < len(lines)
        return {"content": "\n".join(window), "truncated": truncated,
                "totalLines": len(lines)}

    def _memory_sync(self, body: dict) -> dict:
        """Materialize the caller's memory notes into the read-write stage.

        Full-tree and idempotent: every posted file is written, and with `prune` anything else
        under the stage is removed, so a deleted note disappears here too.

        The stage is deliberately left writable. What makes memory read-only for the agent is the
        *other* mount of this same volume, which the container runtime mounts `readOnly` — marking
        the files 0444 here would add no real protection (a same-uid chmod is reversible from the
        agent's own shell) while breaking every subsequent sync, since the owner cannot reopen a
        0444 file for writing.
        """
        files = body.get("files") or []
        prune = bool(body.get("prune", True))
        if not isinstance(files, list):
            raise ValueError("files must be a list")
        if len(files) > MEMORY_MAX_FILES:
            raise ValueError(f"too many files: {len(files)} > {MEMORY_MAX_FILES}")

        total = 0
        written = set()
        for item in files:
            if not isinstance(item, dict):
                raise ValueError("each file must be an object")
            path = _safe_memory_path(item.get("path", ""))
            data = (item.get("content") or "").encode("utf-8")
            total += len(data)
            if total > MEMORY_MAX_BYTES:
                raise ValueError(f"memory payload exceeds {MEMORY_MAX_BYTES} bytes")
            os.makedirs(os.path.dirname(path) or MEMORY_STAGE, exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            written.add(path)

        pruned = 0
        if prune:
            for root, dirs, names in os.walk(MEMORY_STAGE, topdown=False):
                for name in names:
                    full = os.path.join(root, name)
                    if full not in written:
                        os.unlink(full)
                        pruned += 1
                for name in dirs:
                    full = os.path.join(root, name)
                    if not os.listdir(full):
                        os.rmdir(full)

        return {"ok": True, "files": len(written), "bytes": total, "pruned": pruned}

    def _list_files(self, body: dict) -> dict:
        path = _safe_path(body.get("path", "."))
        if not os.path.isdir(path):
            raise ValueError(f"not a directory: {body.get('path')!r}")
        entries = []
        for name in sorted(os.listdir(path)):
            full = os.path.join(path, name)
            try:
                st = os.stat(full)
            except OSError:
                continue
            entries.append({
                "name": name,
                "type": "dir" if os.path.isdir(full) else "file",
                "size": st.st_size,
            })
        return {"entries": entries}


def main() -> None:
    os.makedirs(WORKSPACE, exist_ok=True)
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"loom-runnerd listening on :{PORT} workspace={WORKSPACE} auth={'on' if TOKEN else 'off'}",
          flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
