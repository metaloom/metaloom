#!/usr/bin/env python3
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
"""
MetaLoom // Cortex — minimal Python processing daemon.

A single-file reference showing how to implement a Cortex-style worker in
Python. It talks Loom's wire protocol directly (there is no Python binding for
the JVM node SPI) and demonstrates the full round trip:

    connect  ->  register  ->  receive a NODE_TASK  ->  run a node
             ->  persist the payload (REST)  ->  answer with NODE_TASK_RESULT

There are two planes, and this daemon uses both:

  * Control plane  (WebSocket, /api/v1/processors/ws) — Loom pushes work and
    the worker reports outcomes. This is what makes the worker part of the
    fleet. See LoomChannel below.

  * Data plane     (REST, /api/v1/...) — the worker writes the actual computed
    payload into Loom, keyed by asset UUID. See LoomRest below.

The piece a customer replaces is `run_node()`. Everything else is protocol
plumbing that mirrors the Java reference implementation
(cortex/core/.../impl/loom/LoomControlChannel.java and PipelineTaskHandler.java).

Run it:

    pip install -r requirements.txt
    LOOM_HOST=localhost LOOM_PORT=8092 LOOM_TOKEN=... python daemon.py

Verify the node logic without a Loom server:

    python daemon.py --selftest path/to/some/file.txt
"""

import asyncio
import json
import logging
import os
import platform
import shutil
import signal
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field

# Imported lazily so `--selftest` and node development work with zero
# dependencies installed; it is only needed for the live control channel.
try:
    import websockets
except ImportError:
    websockets = None

log = logging.getLogger("cortex-python")

# Stamped onto everything this worker persists. Bump it whenever the node's
# output shape changes: Loom indexes the ledger on (node_kind, producer_version),
# which is how an operator finds and re-runs everything an older version wrote.
PRODUCER_VERSION = "cortex-python/1.0.0"


# ---------------------------------------------------------------------------
# Configuration
#
# Everything is driven from the environment so the daemon can be dropped into a
# container unchanged. Defaults point at a local `start-demo.sh` Loom.
# ---------------------------------------------------------------------------
# --------------------------------------------------------------------------- #
# Node contracts
# --------------------------------------------------------------------------- #
#
# What this worker's nodes look like, sent to Loom as NODE_REGISTRATION after it
# acknowledges our REGISTER. Without it `py-hello` would be *runnable but
# unauthorable*: Loom would happily dispatch tasks to us, but the pipeline editor
# could not place the node and the graph parser would reject it as unknown.
#
# Java workers derive this by reflecting over the node's port constants. There is
# nothing to reflect over here, and that is the point: the wire format is plain
# JSON and language-agnostic, so a hand-written dict is a first-class citizen.
#
# The fields are exactly the descriptor Loom serves from
# /api/v1/pipeline/node-descriptors — one contract type, in both directions.
NODE_SPECS = {
    "py-hello": {
        "nodeId": "py-hello",
        "version": "1.0.0",
        "name": "Python Hello",
        "description": "Example Python node: reports a file's size and a SHA-256 of its first bytes.",
        # Icon names resolve against a fixed map in the editor; an unknown one
        # falls back to the category icon, so a custom node cannot ship its own.
        "icon": "description",
        "category": "ANALYSIS",
        "inputPorts": [
            {
                "id": "media",
                "label": "Media",
                "contentType": "media/*",
                "cardinality": "ONE",
                "required": True,
                "description": "Any media file",
            }
        ],
        "outputPorts": [
            {
                "id": "file_size",
                "label": "File Size",
                "contentType": "scalar/integer",
                "cardinality": "ONE",
                "required": True,
                "description": "Size of the file in bytes",
            },
            {
                "id": "digest",
                "label": "Digest",
                "contentType": "hash/sha256",
                "cardinality": "ONE",
                "required": True,
                "description": "SHA-256 over the first bytes of the file",
            },
        ],
        "inputGroups": [],
        "outputGroups": [],
        "dynamicPorts": False,
        "parameters": [
            {
                "key": "enabled",
                "type": "BOOLEAN",
                "defaultValue": True,
                "label": "Enabled",
                "description": "Whether this node is active in the pipeline",
            },
            {
                "key": "maxBytes",
                "type": "INTEGER",
                "defaultValue": 65536,
                "label": "Max Bytes",
                "description": "How many leading bytes to digest",
                "min": 1,
            },
        ],
        "defaultConcurrency": 1,
        "defaultMode": "PARALLEL",
        "defaultBlocking": True,
        "events": ["NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS"],
    }
}


@dataclass
class Config:
    host: str = field(default_factory=lambda: os.environ.get("LOOM_HOST", "localhost"))
    # 8092 is the Loom REST+WebSocket port (start-demo.sh / start-server.sh).
    # Cortex's own docs mention 7733 as a historical default, but the running
    # Loom server and start-cortex.sh both use 8092.
    port: int = field(default_factory=lambda: int(os.environ.get("LOOM_PORT", "8092")))
    # A pre-issued JWT. The same token authenticates both the WebSocket
    # (?token=) and every REST call (Authorization: Bearer). If absent, and
    # user/password are set, the daemon logs in for one.
    token: str = field(default_factory=lambda: os.environ.get("LOOM_TOKEN", ""))
    user: str = field(default_factory=lambda: os.environ.get("LOOM_USER", ""))
    password: str = field(default_factory=lambda: os.environ.get("LOOM_PASSWORD", ""))
    # A stable id survives a restart; Loom keys leases and attribution on it, so
    # a generated-per-boot id would look like a brand new worker every restart.
    node_id: str = field(default_factory=lambda: os.environ.get("CORTEX_NODE_ID", ""))
    # The node kinds this worker advertises and can actually run. Loom will only
    # dispatch a NODE_TASK whose `nodeKind` is in this whitelist.
    node_kinds: list = field(
        default_factory=lambda: [
            k.strip()
            for k in os.environ.get("CORTEX_NODE_KINDS", "py-hello").split(",")
            if k.strip()
        ]
    )

    def __post_init__(self):
        if not self.node_id:
            self.node_id = "cortex-py-" + uuid.uuid4().hex[:12]

    @property
    def rest_base(self) -> str:
        return f"http://{self.host}:{self.port}/api/v1"

    @property
    def ws_url(self) -> str:
        base = f"ws://{self.host}:{self.port}/api/v1/processors/ws"
        if self.token:
            return base + "?token=" + urllib.parse.quote(self.token, safe="")
        return base


# ---------------------------------------------------------------------------
# The node — THIS is the part a customer replaces.
#
# A node is just: given a media path (plus per-task options and the outputs of
# upstream nodes), compute something and return a result. It has no knowledge of
# WebSockets or REST. The `py-hello` node below mirrors the Java HelloWorldNode
# example: it emits `file_size` and `word_count`.
# ---------------------------------------------------------------------------
class NodeResult:
    """Outcome of running a node against one media item.

    A failure is a *value*, never a raised exception that escapes the daemon:
    one bad item must not take down the run. The three states mirror Loom's
    NodeState enum (COMPLETED / FAILED / SKIPPED).
    """

    def __init__(self, state: str, outputs: dict = None, message: str = None):
        self.state = state
        self.outputs = outputs or {}
        self.message = message

    @staticmethod
    def completed(outputs: dict):
        return NodeResult("COMPLETED", outputs)

    @staticmethod
    def failed(message: str):
        return NodeResult("FAILED", message=message)

    @staticmethod
    def skipped(reason: str):
        return NodeResult("SKIPPED", message=reason)


# The control plane and the ledger use DIFFERENT enums for the same outcome:
#
#   NODE_TASK_RESULT.state (WebSocket)  COMPLETED | FAILED | SKIPPED
#   node-results.state     (REST)       SUCCESS   | FAILED | SKIPPED
#
# `asset_node_result` has a CHECK constraint on the column, so posting the wire
# value is rejected by the database — and because the json-comp is written first
# and succeeds, the failure mode is a stored payload with no ledger row saying
# the node ran. Map it here, once.
LEDGER_STATE = {"COMPLETED": "SUCCESS", "FAILED": "FAILED", "SKIPPED": "SKIPPED"}


def ledger_state(wire_state: str) -> str:
    return LEDGER_STATE.get(wire_state, wire_state)


def run_node(node_kind: str, media_path: str, options: dict, upstream: dict) -> NodeResult:
    """Run one node against one media file.

    :param node_kind: which node to run (a worker may serve several kinds)
    :param media_path: absolute path on shared storage; the worker reads it itself
    :param options:    per-node options from the pipeline definition
    :param upstream:   outputs of upstream nodes this node declared inputs for,
                       shaped {nodeId: {key: value}} — e.g. a sha256 produced earlier
    :returns: a NodeResult whose `outputs` are forwarded to downstream nodes
    """
    if node_kind != "py-hello":
        # Loom should not dispatch a kind we did not advertise, but be defensive.
        return NodeResult.skipped(f"Unknown node kind '{node_kind}'")

    if not os.path.isfile(media_path):
        # Cannot see the file — usually means shared storage is not mounted here.
        return NodeResult.failed(f"Media not found on this worker: {media_path}")

    # Example of consuming an upstream output. If a "sha256" node ran before this
    # one in the pipeline, its hash is available here.
    upstream_sha256 = _upstream_output(upstream, "sha256", "sha256")
    if upstream_sha256:
        log.info("Upstream sha256 available: %s", upstream_sha256)

    file_size = os.path.getsize(media_path)
    word_count = _count_words(media_path)

    log.info("Computed file_size=%d word_count=%d for %s", file_size, word_count, media_path)
    # Output keys become the node's contribution to the pipeline; downstream
    # nodes read them by name, and they are what we persist to Loom.
    return NodeResult.completed({"file_size": file_size, "word_count": word_count})


def _upstream_output(upstream: dict, node_id: str, key: str):
    node = upstream.get(node_id) if upstream else None
    return node.get(key) if isinstance(node, dict) else None


def _count_words(path: str) -> int:
    """Naive word count. Returns 0 for binary files (images, video, ...)."""
    count = 0
    try:
        with open(path, "r", encoding="utf-8", errors="strict") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    count += len(line.split())
    except (UnicodeDecodeError, OSError) as e:
        log.debug("Could not read %s as text (binary?): %s", path, e)
    return count


# ---------------------------------------------------------------------------
# Data plane — the REST client.
#
# This is how the *payload* reaches Loom's database, keyed by asset UUID. The
# control-plane NODE_TASK_RESULT only tells the engine the node finished; the
# actual data is written here. Uses only the stdlib (urllib) to stay minimal.
# ---------------------------------------------------------------------------
class LoomRest:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.token = cfg.token

    def ensure_token(self) -> bool:
        """Obtain a token if we don't have one but were given credentials."""
        if self.token:
            return True
        if not (self.cfg.user and self.cfg.password):
            return False
        try:
            resp = self._request(
                "POST", "/login", {"username": self.cfg.user, "password": self.cfg.password}, auth=False
            )
            self.token = resp.get("token", "")
            return bool(self.token)
        except LoomHttpError as e:
            log.warning("Login failed: %s", e)
            return False

    def asset_uuid_by_sha512(self, sha512: str):
        """Resolve the asset UUID from the media hash carried on the task.

        `sha512` is only present after a hash node has run upstream, which is why
        persistence is best-effort: without it (or without a matching asset) we
        skip the write rather than fail the task.
        """
        if not sha512:
            return None
        try:
            asset = self._request("GET", f"/assets/sha512/{sha512}")
            return asset.get("uuid")
        except LoomHttpError as e:
            if e.status == 404:
                log.info("No asset registered yet for sha512=%s; skipping persistence", sha512[:12])
            else:
                log.warning("Asset lookup failed: %s", e)
            return None

    def post_json_comp(self, asset_uuid: str, node_kind: str, schema_type: str, data: dict,
                       variant: str = "", producer_version: str = None):
        """Persist an opaque JSON payload into the generic `asset_json_comp` sink.

        The lightweight, customer-facing persistence path — no dedicated table
        required. Re-posting the same (nodeKind, schemaType, variant) upserts.
        """
        body = {
            "nodeKind": node_kind,
            "schemaType": schema_type,
            "variant": variant,
            "data": data,
        }
        if producer_version:
            body["producerVersion"] = producer_version
        return self._request("POST", f"/assets/{asset_uuid}/json-comps", body)

    def post_node_result(self, asset_uuid: str, node_kind: str, node_id: str, state: str,
                         duration_ms: int, result_ref: dict = None, reason: str = None,
                         producer_version: str = None):
        """Record a node-result ledger entry.

        This is the "what ran, and where its output lives" audit row that Loom's
        WhisperNode writes after storing its transcript. `result_ref` points at
        the payload row(s) written by post_json_comp (or a typed endpoint).

        `state` must already be a LEDGER state — see ledger_state(). Passing the
        wire state through unmapped is rejected by the database.
        """
        body = {
            "nodeKind": node_kind,
            "nodeId": node_id,
            "state": state,
            "origin": "COMPUTED",
            "durationMs": duration_ms,
        }
        # Stamped so an operator can sweep everything an older version produced:
        # asset_node_result is indexed on (node_kind, producer_version).
        if producer_version:
            body["producerVersion"] = producer_version
        if reason:
            body["reason"] = reason
        if result_ref:
            body["resultRef"] = result_ref
        return self._request("POST", f"/assets/{asset_uuid}/node-results", body)

    def _request(self, method: str, path: str, body: dict = None, auth: bool = True):
        url = self.cfg.rest_base + path
        payload = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=payload, method=method)
        req.add_header("Accept", "application/json")
        if payload is not None:
            req.add_header("Content-Type", "application/json")
        if auth and self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")
            raise LoomHttpError(e.code, f"{method} {path} -> {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            raise LoomHttpError(0, f"{method} {path} failed: {e.reason}") from e


class LoomHttpError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


# ---------------------------------------------------------------------------
# Control plane — the WebSocket channel.
#
# Mirrors LoomControlChannel.java: connect with exponential-backoff reconnect,
# REGISTER, heartbeat every 10s, status every 20s, and dispatch inbound
# messages. The only work message handled here is NODE_TASK (the live path);
# SOURCE_TASK / SEGMENT_TASK are advanced paths intentionally left out.
# ---------------------------------------------------------------------------
HEARTBEAT_INTERVAL_S = 10
STATUS_INTERVAL_S = 20
RECONNECT_BASE_S = 2
RECONNECT_MAX_S = 30


class LoomChannel:
    def __init__(self, cfg: Config, rest: LoomRest):
        self.cfg = cfg
        self.rest = rest
        self.ws = None
        self.registered = False
        self._stop = asyncio.Event()

    async def run(self):
        """Connect-forever loop with exponential backoff, until stopped."""
        if websockets is None:
            raise RuntimeError("The 'websockets' package is required to connect. "
                               "Run: pip install -r requirements.txt")
        attempt = 0
        while not self._stop.is_set():
            try:
                async with websockets.connect(self.cfg.ws_url, ping_interval=None) as ws:
                    self.ws = ws
                    self.registered = False
                    attempt = 0
                    log.info("Connected to Loom control channel %s:%d", self.cfg.host, self.cfg.port)
                    await self._on_connected(ws)
            except (OSError, websockets.exceptions.WebSocketException) as e:
                if self._stop.is_set():
                    break
                attempt += 1
                delay = min(RECONNECT_BASE_S * attempt, RECONNECT_MAX_S)
                log.warning("Control channel disconnected (%s). Reconnecting in %ds (attempt %d)",
                            e, delay, attempt)
                try:
                    await asyncio.wait_for(self._stop.wait(), timeout=delay)
                except asyncio.TimeoutError:
                    pass
        log.info("Control channel stopped")

    async def stop(self):
        self._stop.set()
        if self.ws is not None:
            await self.ws.close()

    async def _on_connected(self, ws):
        await self._send_register(ws)
        # Heartbeat and status run as background tasks so a long-running node
        # (handled off-loop) never stalls them. They end when the socket closes.
        hb = asyncio.create_task(self._heartbeat_loop(ws))
        st = asyncio.create_task(self._status_loop(ws))
        try:
            async for raw in ws:
                await self._handle_message(ws, raw)
        finally:
            hb.cancel()
            st.cancel()

    async def _send_register(self, ws):
        registration = {
            "nodeId": self.cfg.node_id,
            "name": "cortex-python",
            "priority": 100,
            "host": self._self_host(),
            "capabilities": ["CPU", "IO"],
            # Advertising exactly what we can run keeps Loom's pool honest: it
            # will never dispatch a kind that is not in this list.
            "nodeWhitelist": self.cfg.node_kinds,
        }
        await self._send(ws, "REGISTER", registration)
        log.info("Sent REGISTER (nodeId=%s, kinds=%s)", self.cfg.node_id, self.cfg.node_kinds)

    async def _send_node_registration(self, ws):
        """Tell Loom what our nodes look like, so they can be authored in the editor.

        Only the kinds this worker actually advertises are announced. Announcing a
        contract for something we cannot run would put it in the palette and then
        fail at dispatch.
        """
        nodes = [NODE_SPECS[kind] for kind in self.cfg.node_kinds if kind in NODE_SPECS]
        missing = [kind for kind in self.cfg.node_kinds if kind not in NODE_SPECS]
        if missing:
            log.warning(
                "No contract defined for %s; those nodes will run but stay unauthorable. "
                "Add them to NODE_SPECS.",
                missing,
            )
        if not nodes:
            return
        await self._send(ws, "NODE_REGISTRATION", {"cortexId": self.cfg.node_id, "nodes": nodes})
        log.info("Announced %d node contract(s): %s", len(nodes), [n["nodeId"] for n in nodes])

    def _log_node_registration_ack(self, body: dict):
        """Report what Loom made of the announcement.

        Every rejection is logged by name and reason. A silent ack is how an author
        ends up editing a node's ports, seeing no effect in the editor, and losing
        an afternoon to it — and this file is what a custom-node author copies.
        """
        accepted = body.get("accepted") or []
        rejected = body.get("rejected") or []
        log.info("Loom accepted %d node contract(s): %s", len(accepted), accepted)
        for entry in rejected:
            reason = entry.get("reason")
            message = entry.get("message")
            if reason == "BUILTIN":
                # Routine: Loom ships its own contract for this node id and it wins.
                log.info("Node '%s': %s", entry.get("nodeId"), message)
            else:
                log.warning("Node '%s' was not adopted (%s): %s", entry.get("nodeId"), reason, message)

    async def _heartbeat_loop(self, ws):
        while True:
            await asyncio.sleep(HEARTBEAT_INTERVAL_S)
            await self._send(ws, "HEARTBEAT")

    async def _status_loop(self, ws):
        while True:
            await asyncio.sleep(STATUS_INTERVAL_S)
            if self.registered:
                await self._send(ws, "STATUS_UPDATE", _system_status())

    async def _handle_message(self, ws, raw):
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            log.warning("Ignoring malformed message: %s", raw)
            return
        mtype = msg.get("type")
        body = msg.get("body") or {}

        if mtype == "REGISTERED":
            self.registered = True
            log.info("Registration acknowledged by Loom")
            # Announce *after* REGISTERED, not inside REGISTER. Registration is a
            # cheap in-memory operation on Loom's side; ingesting contracts
            # validates and writes to Postgres. Keeping them apart is what stops a
            # reconnect storm becoming a database problem — and the gap in between
            # is harmless, because dispatch reads the whitelist, not the registry.
            await self._send_node_registration(ws)
        elif mtype == "NODE_REGISTRATION_ACK":
            self._log_node_registration_ack(body)
        elif mtype == "HEARTBEAT_ACK":
            pass  # keepalive confirmed
        elif mtype == "NODE_TASK":
            # Run off the socket loop; a slow node must not stall heartbeats or
            # block the next inbound message.
            asyncio.create_task(self._handle_node_task(ws, body))
        elif mtype == "ERROR":
            log.warning("Loom reported an error: %s", body.get("message"))
        else:
            # SOURCE_TASK, SEGMENT_TASK, ... are out of scope here.
            log.debug("Ignoring message type %s", mtype)

    async def _handle_node_task(self, ws, task: dict):
        """Turn one NODE_TASK into a node run, a REST persist, and one result.

        Every task produces exactly one result message — including failures —
        because Loom's engine blocks on an answer for each task it dispatched.
        """
        task_uuid = task.get("taskUuid")
        run_uuid = task.get("runUuid")
        item_id = task.get("itemId")
        node_id = task.get("nodeId")
        node_kind = task.get("nodeKind")
        media = task.get("media") or {}
        options = task.get("options") or {}
        upstream = task.get("upstreamOutputs") or {}

        log.info("NODE_TASK %s kind=%s item=%s path=%s", task_uuid, node_kind, item_id, media.get("path"))

        loop = asyncio.get_running_loop()
        started = loop.time()
        try:
            result = await asyncio.to_thread(
                run_node, node_kind, media.get("path"), options, upstream
            )
        except Exception as e:  # a node bug is still just a FAILED result
            log.exception("Node raised while processing %s", item_id)
            result = NodeResult.failed(str(e))
        duration_ms = int((loop.time() - started) * 1000)

        # Data plane: persist the payload when online and the asset is known.
        if result.state == "COMPLETED":
            await asyncio.to_thread(self._persist, media, node_kind, node_id, result, duration_ms)

        # Control plane: report the outcome so the engine can advance the DAG.
        await self._send_node_task_result(ws, run_uuid, item_id, task_uuid, node_id, result, duration_ms)

    def _persist(self, media: dict, node_kind: str, node_id: str, result: NodeResult, duration_ms: int):
        """Best-effort write of the result payload into Loom (runs in a thread)."""
        if not self.rest.token:
            log.debug("No token; running offline, skipping persistence")
            return
        asset_uuid = self.rest.asset_uuid_by_sha512(media.get("sha512"))
        if not asset_uuid:
            return
        try:
            self.rest.post_json_comp(
                asset_uuid, node_kind, node_kind, result.outputs,
                producer_version=PRODUCER_VERSION,
            )
            self.rest.post_node_result(
                asset_uuid, node_kind, node_id or "", ledger_state(result.state), duration_ms,
                result_ref={"table": "asset_json_comp", "nodeKind": node_kind},
                producer_version=PRODUCER_VERSION,
            )
            log.info("Persisted %s result for asset %s", node_kind, asset_uuid)
        except LoomHttpError as e:
            log.warning("Persistence failed (result still reported to engine): %s", e)

    async def _send_node_task_result(self, ws, run_uuid, item_id, task_uuid, node_id, result, duration_ms):
        message = {
            "runUuid": run_uuid,
            "itemId": item_id,
            "result": {
                "taskUuid": task_uuid,
                "nodeId": node_id,
                "state": result.state,
                "durationMs": duration_ms,
                "message": result.message,
                "outputs": result.outputs,
            },
        }
        await self._send(ws, "NODE_TASK_RESULT", message)
        log.info("Reported NODE_TASK_RESULT item=%s state=%s (%dms)", item_id, result.state, duration_ms)

    async def _send(self, ws, mtype: str, body: dict = None):
        envelope = {"type": mtype}
        if body is not None:
            envelope["body"] = body
        await ws.send(json.dumps(envelope))

    def _self_host(self) -> str:
        try:
            return socket.gethostname()
        except OSError:
            return "unknown"


def _system_status() -> dict:
    """A best-effort SystemStatusInfo. All fields are optional on the wire."""
    status = {}
    try:
        load1, _, _ = os.getloadavg()
        status["cpuLoad"] = min(100.0, max(0.0, load1 * 100.0))
    except (OSError, AttributeError):
        pass  # getloadavg is unavailable on some platforms
    try:
        total, used, _ = shutil.disk_usage(os.getcwd())
        status["diskTotal"] = total
        status["diskUsed"] = used
    except OSError:
        pass
    return status


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
async def _amain(cfg: Config):
    rest = LoomRest(cfg)
    if not rest.ensure_token():
        log.warning("No LOOM_TOKEN and no login credentials — running WITHOUT result "
                    "persistence. The worker will still register and answer tasks.")
    channel = LoomChannel(cfg, rest)

    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            pass  # e.g. Windows

    runner = asyncio.create_task(channel.run())
    await stop.wait()
    log.info("Shutting down ...")
    await channel.stop()
    await runner


def _selftest(path: str) -> int:
    """Run the node against a local file, print the result, and exit.

    Lets a customer verify their node logic without any Loom server.
    """
    result = run_node("py-hello", path, {}, {})
    print(json.dumps({"state": result.state, "outputs": result.outputs, "message": result.message}, indent=2))
    return 0 if result.state == "COMPLETED" else 1


def main():
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)-5s %(name)s - %(message)s",
    )
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        if len(sys.argv) < 3:
            print("usage: python daemon.py --selftest <file>", file=sys.stderr)
            return 2
        return _selftest(sys.argv[2])

    cfg = Config()
    log.info("Cortex Python daemon starting (Python %s)", platform.python_version())
    log.info("Loom REST base: %s", cfg.rest_base)
    try:
        asyncio.run(_amain(cfg))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
