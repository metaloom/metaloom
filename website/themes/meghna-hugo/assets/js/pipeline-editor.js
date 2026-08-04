/*
 * pipeline-editor — a self-contained, backend-free MetaLoom pipeline editor + simulator.
 *
 * Mounted on /pipeline-editor/ into <div id="ml-pipeline-editor" data-descriptors="…">. The node
 * catalogue is fetched from a static snapshot (data-descriptors) that mirrors
 * GET /api/v1/pipeline/node-descriptors, so nothing here talks to a server.
 *
 * The editor lets a visitor drag nodes onto an SVG canvas, wire typed ports (invalid connections
 * are rejected live), load demo pipelines, and press Play to watch synthetic assets flow from the
 * source node through the graph to the loom sink — modelling topological order, MANY fan-out, the
 * implicit gather on a MANY input, and filter PASS/REJECT branch routing. An action log records one
 * row per node execution.
 *
 * Vanilla, zero dependencies, one IIFE. It self-guards on the mount element and never throws to the
 * page. The content-type assignability rule and family colours are a verbatim mirror of
 * loom-ui/src/features/pipeline/contentTypes.ts.
 */
(function () {
	"use strict";

	var root = document.getElementById("ml-pipeline-editor");
	if (!root) { return; }

	var NS = "http://www.w3.org/2000/svg";

	// ---------------------------------------------------------------- content-type engine (mirror)
	var WILDCARD = "/*";
	function family(ct) { if (!ct) { return null; } var i = ct.indexOf("/"); return i < 0 ? null : ct.slice(0, i); }
	function isWildcard(ct) { return !!ct && ct.slice(-2) === WILDCARD; }
	function isAssignable(actual, declared) {
		if (!actual || !declared) { return false; }
		if (actual === declared) { return true; }
		var fa = family(actual), fd = family(declared);
		if (fa === null || fd === null || fa !== fd) { return false; }
		return isWildcard(declared) || isWildcard(actual);
	}
	function isProvisional(actual, declared) {
		return isAssignable(actual, declared) && isWildcard(actual) && !isWildcard(declared);
	}
	var FAMILY_COLORS = {
		media: "#66bb6a", text: "#42a5f5", detection: "#ec407a", hash: "#ab47bc",
		scalar: "#ffa726", artifact: "#26c6da", struct: "#ffca28", control: "#78909c"
	};
	function ctColor(ct) { var f = family(ct); return (f && FAMILY_COLORS[f]) || "#8fa3b8"; }

	var CATEGORY_COLORS = {
		SOURCE: "#4aa3ff", FILTER: "#e2a24e", ANALYSIS: "#57cbcc",
		TRANSFORM: "#c56be0", OUTPUT: "#2fd4b6"
	};
	var CATEGORY_ORDER = ["SOURCE", "FILTER", "ANALYSIS", "TRANSFORM", "OUTPUT"];

	// ---------------------------------------------------------------- small helpers
	function E(tag, attrs, kids) {
		var e = document.createElementNS(NS, tag);
		if (attrs) { for (var k in attrs) { if (attrs[k] != null) { e.setAttribute(k, attrs[k]); } } }
		if (kids) { kids.forEach(function (c) { if (c) { e.appendChild(c); } }); }
		return e;
	}
	function H(tag, attrs, kids) {
		var e = document.createElement(tag);
		if (attrs) {
			for (var k in attrs) {
				if (k === "class") { e.className = attrs[k]; }
				else if (k === "text") { e.textContent = attrs[k]; }
				else if (k === "html") { e.innerHTML = attrs[k]; }
				else if (attrs[k] != null) { e.setAttribute(k, attrs[k]); }
			}
		}
		if (kids) { kids.forEach(function (c) { if (c) { e.appendChild(c); } }); }
		return e;
	}
	function bez(x1, y1, x2, y2) {
		var mx = (x1 + x2) / 2;
		return "M" + x1 + "," + y1 + " C" + mx + "," + y1 + " " + mx + "," + y2 + " " + x2 + "," + y2;
	}
	function hashStr(s) { var h = 2166136261; for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
	function mulberry32(seed) {
		return function () {
			seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
			var t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
			t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
			return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
		};
	}
	function rand(seedStr) { return mulberry32(hashStr(seedStr))(); }
	function randInt(seedStr, lo, hi) { return lo + Math.floor(rand(seedStr) * (hi - lo + 1)); }

	// ---------------------------------------------------------------- geometry
	var NODE_W = 212, HEAD_H = 30, ROW_H = 24, BODY_PAD = 10, GRID = 15;
	function portY(idx) { return HEAD_H + BODY_PAD + idx * ROW_H + ROW_H / 2; }

	// ---------------------------------------------------------------- state
	var descriptorsByKind = {};   // kind -> descriptor
	var contentTypesById = {};    // id -> contentType (served labels)
	var model = { nodes: {}, edges: {}, seq: 1, sourceMode: "single", sourceCount: 3 };
	var selection = { nodeId: null, edgeId: null };
	var view = { tx: 40, ty: 40, scale: 1 };
	var els = {};                 // cached DOM
	/** Most recent firing per node, so results survive a re-render (a drag, a selection change). */
	var lastResults = {};
	/** Result rows shown on a node card before the "+n more" chip takes over. */
	var MAX_RESULT_ROWS = 3;
	var validationErrors = [];

	function nodeGeom(node) {
		var d = node.descriptor;
		var ins = (d && d.inputPorts) || [];
		var outs = (d && d.outputPorts) || [];
		var rows = Math.max(ins.length, outs.length, 1);
		return { ins: ins, outs: outs, rows: rows, w: NODE_W, h: HEAD_H + rows * ROW_H + BODY_PAD * 2 };
	}
	function portIndex(list, portId) { for (var i = 0; i < list.length; i++) { if (list[i].id === portId) { return i; } } return -1; }
	function portPos(node, portId, isOut) {
		var g = nodeGeom(node);
		var list = isOut ? g.outs : g.ins;
		var idx = portIndex(list, portId); if (idx < 0) { idx = 0; }
		return { x: node.x + (isOut ? g.w : 0), y: node.y + portY(idx) };
	}
	function portSpec(node, portId, isOut) {
		var g = nodeGeom(node);
		var list = isOut ? g.outs : g.ins;
		var idx = portIndex(list, portId);
		return idx < 0 ? null : list[idx];
	}
	function ctLabel(ct) { var c = contentTypesById[ct]; return (c && c.label) || ct || ""; }

	// ---------------------------------------------------------------- model mutation
	/**
	 * Fill in ports a node kind resolves at runtime rather than declaring.
	 *
	 * A few kinds compute their ports from their configuration, so the served descriptor carries
	 * none — `filter` is the one that matters here, and with an empty `outputPorts` it could not be
	 * wired to anything at all, in the demos or from the palette. This mirrors
	 * `FilterPortResolver.resolveOutputPorts` for the unconfigured case.
	 *
	 * Only the *fixed* ports are mirrored. The product also gives a filter one selective port per
	 * configured bucket; this editor has no bucket editor, so there are never any to add.
	 */
	function withResolvedPorts(d) {
		if (d.kind !== "filter" || (d.outputPorts && d.outputPorts.length)) { return d; }
		var copy = {};
		Object.keys(d).forEach(function (k) { copy[k] = d[k]; });
		copy.outputPorts = [
			// `other` is selective: with no buckets configured every item goes down it, which is
			// what makes it the way to pass an asset through a filter.
			{ id: "other", label: "Other", contentType: "media/*", cardinality: "ONE", required: true, selective: true, description: "Items that matched no bucket" },
			// These two carry a value for every item, so a node wired to them runs whichever way
			// the decision went — the escape hatch for "I want the verdict, not the item".
			{ id: "passed", label: "Passed", contentType: "control/verdict", cardinality: "ONE", required: true, description: "Whether the item passed" },
			{ id: "bucket", label: "Bucket", contentType: "scalar/string", cardinality: "ONE", required: true, description: "Which bucket the item landed in" }
		];
		return copy;
	}

	function genId(prefix) { return prefix + (model.seq++); }
	function addNode(kind, x, y, opts) {
		var d = descriptorsByKind[kind];
		var id = (opts && opts.id) || genId("pn");
		var node = {
			id: id, kind: kind,
			label: (opts && opts.label) || (d ? d.name : kind),
			description: (opts && opts.description) || (d ? d.description : ""),
			x: Math.round(x), y: Math.round(y),
			options: (opts && opts.options) || null,
			descriptor: d || null,
			unknownKind: !d
		};
		model.nodes[id] = node;
		return node;
	}
	function removeNode(id) {
		delete model.nodes[id];
		Object.keys(model.edges).forEach(function (eid) {
			var e = model.edges[eid];
			if (e.source === id || e.target === id) { delete model.edges[eid]; }
		});
		if (selection.nodeId === id) { selection.nodeId = null; }
	}
	function addEdge(source, sourcePort, target, targetPort, branch, id) {
		var eid = id || genId("pe");
		model.edges[eid] = { id: eid, source: source, sourcePort: sourcePort, target: target, targetPort: targetPort, branch: branch || "ANY" };
		return model.edges[eid];
	}
	function edgesInto(nodeId, portId) {
		return Object.keys(model.edges).map(function (k) { return model.edges[k]; })
			.filter(function (e) { return e.target === nodeId && (portId == null || e.targetPort === portId); });
	}
	function edgesFrom(nodeId) {
		return Object.keys(model.edges).map(function (k) { return model.edges[k]; })
			.filter(function (e) { return e.source === nodeId; });
	}
	function sourceNodes() {
		return Object.keys(model.nodes).map(function (k) { return model.nodes[k]; })
			.filter(function (n) { return n.descriptor && n.descriptor.category === "SOURCE"; });
	}

	// ---------------------------------------------------------------- JSON round-trip
	function toGraphJson() {
		var nodes = Object.keys(model.nodes).map(function (k) {
			var n = model.nodes[k];
			var o = { id: n.id, type: n.kind, label: n.label, description: n.description, position: { x: n.x, y: n.y } };
			if (n.options && Object.keys(n.options).length) { o.options = n.options; }
			return o;
		});
		var edges = Object.keys(model.edges).map(function (k) {
			var e = model.edges[k];
			return { id: e.id, source: e.source, sourcePort: e.sourcePort, target: e.target, targetPort: e.targetPort, branch: e.branch };
		});
		return { meta: { sourceMode: model.sourceMode, sourceCount: model.sourceCount }, nodes: nodes, edges: edges };
	}
	function fromGraphJson(obj) {
		var warnings = [];
		model.nodes = {}; model.edges = {}; model.seq = 1;
		selection.nodeId = null; selection.edgeId = null;
		if (!obj || !Array.isArray(obj.nodes)) { throw new Error("JSON has no 'nodes' array"); }
		if (obj.meta) {
			if (obj.meta.sourceMode === "single" || obj.meta.sourceMode === "multiple") { model.sourceMode = obj.meta.sourceMode; }
			if (obj.meta.sourceCount) { model.sourceCount = Math.max(1, Math.min(9, obj.meta.sourceCount | 0)); }
		}
		var auto = 0;
		obj.nodes.forEach(function (n) {
			var kind = n.type || n.kind;
			var pos = n.position || { x: n.x, y: n.y };
			if (typeof pos.x !== "number" || typeof pos.y !== "number") { pos = { x: 60 + (auto % 4) * 240, y: 60 + Math.floor(auto / 4) * 160 }; auto++; }
			if (!descriptorsByKind[kind]) { warnings.push("Unknown node kind: " + kind); }
			addNode(kind, pos.x, pos.y, { id: n.id, label: n.label || n.name || (descriptorsByKind[kind] ? descriptorsByKind[kind].name : kind), description: n.description, options: n.options || n.config || null });
			// keep seq ahead of numeric ids so generated ids never collide
			var m = /(\d+)$/.exec(n.id || "");
			if (m) { model.seq = Math.max(model.seq, parseInt(m[1], 10) + 1); }
		});
		(obj.edges || []).forEach(function (e) {
			if (!model.nodes[e.source] || !model.nodes[e.target]) { warnings.push("Edge references a missing node: " + e.id); return; }
			addEdge(e.source, e.sourcePort, e.target, e.targetPort, e.branch || "ANY", e.id);
		});
		return warnings;
	}

	// ---------------------------------------------------------------- validation
	// Order-independent full-graph check. Each error: {severity, code, message, node?, edge?}.
	function validate() {
		var errs = [];
		var nodes = Object.keys(model.nodes).map(function (k) { return model.nodes[k]; });
		var edges = Object.keys(model.edges).map(function (k) { return model.edges[k]; });

		// unknown kind
		nodes.forEach(function (n) {
			if (!n.descriptor) { errs.push({ severity: "error", code: "unknownKind", message: "Unknown node kind '" + n.kind + "'", node: n.id }); }
		});

		// exactly one source
		var srcs = nodes.filter(function (n) { return n.descriptor && n.descriptor.category === "SOURCE"; });
		if (srcs.length === 0) { errs.push({ severity: "error", code: "noSource", message: "Pipeline needs exactly one source node" }); }
		if (srcs.length > 1) { errs.push({ severity: "error", code: "multipleSource", message: "Only one source node is allowed (" + srcs.length + " present)", node: srcs[1].id }); }
		srcs.forEach(function (n) {
			var outs = (n.descriptor.outputPorts) || [];
			if (!outs.some(function (p) { return p.id === "media"; })) {
				errs.push({ severity: "warn", code: "sourcePort", message: n.label + ": a source's output port must be named 'media'", node: n.id });
			}
		});

		// per-edge: dangling, type mismatch, branch legality
		edges.forEach(function (e) {
			var sn = model.nodes[e.source], tn = model.nodes[e.target];
			if (!sn || !tn) { errs.push({ severity: "error", code: "dangling", message: "Edge references a missing node", edge: e.id }); return; }
			if (!sn.descriptor || !tn.descriptor) { return; }
			var sp = portSpec(sn, e.sourcePort, true), tp = portSpec(tn, e.targetPort, false);
			if (!sp) { errs.push({ severity: "error", code: "unknownPort", message: sn.label + " has no output port '" + e.sourcePort + "'", edge: e.id }); }
			if (!tp) { errs.push({ severity: "error", code: "unknownPort", message: tn.label + " has no input port '" + e.targetPort + "'", edge: e.id }); }
			if (sp && tp && !isAssignable(sp.contentType, tp.contentType)) {
				errs.push({ severity: "error", code: "typeMismatch", message: "Type mismatch: " + ctLabel(sp.contentType) + " (" + sp.contentType + ") not assignable to " + tp.contentType + " on " + tn.label, edge: e.id });
			}
			if (e.branch && e.branch !== "ANY" && sn.descriptor.category !== "FILTER") {
				errs.push({ severity: "error", code: "branch", message: e.branch + " branch is only valid on an edge leaving a filter node", edge: e.id });
			}
		});

		// cardinality: a ONE input with >1 incoming edge
		nodes.forEach(function (n) {
			if (!n.descriptor) { return; }
			((n.descriptor.inputPorts) || []).forEach(function (p) {
				if (p.cardinality !== "MANY") {
					if (edgesInto(n.id, p.id).length > 1) {
						errs.push({ severity: "error", code: "cardinality", message: n.label + ": input '" + p.id + "' is ONE but has multiple incoming edges", node: n.id });
					}
				}
			});
		});

		// required inputs + port groups (XOR / EXCLUSIVE)
		nodes.forEach(function (n) {
			if (!n.descriptor || n.descriptor.category === "SOURCE") { return; }
			var d = n.descriptor;
			var groups = {};
			((d.inputGroups) || []).forEach(function (g) { groups[g.id] = { mode: g.mode, required: g.required, wired: 0, label: g.label || g.id }; });
			((d.inputPorts) || []).forEach(function (p) {
				var wired = edgesInto(n.id, p.id).length > 0;
				if (p.group && groups[p.group]) { if (wired) { groups[p.group].wired++; } }
				else if (p.required && !wired) {
					errs.push({ severity: "error", code: "requiredInput", message: n.label + ": required input '" + p.id + "' is not connected", node: n.id });
				}
			});
			Object.keys(groups).forEach(function (gid) {
				var g = groups[gid];
				if (g.mode === "XOR") {
					if (g.required && g.wired === 0) { errs.push({ severity: "error", code: "xor", message: n.label + ": connect exactly one input of group '" + g.label + "'", node: n.id }); }
					if (g.wired > 1) { errs.push({ severity: "error", code: "xor", message: n.label + ": group '" + g.label + "' takes exactly one input (" + g.wired + " connected)", node: n.id }); }
				}
			});
			// EXCLUSIVE output groups
			var og = {};
			((d.outputGroups) || []).forEach(function (g) { og[g.id] = { wired: 0, label: g.label || g.id }; });
			((d.outputPorts) || []).forEach(function (p) {
				if (p.group && og[p.group] && edgesFrom(n.id).some(function (e) { return e.sourcePort === p.id; })) { og[p.group].wired++; }
			});
			Object.keys(og).forEach(function (gid) {
				if (og[gid].wired > 1) { errs.push({ severity: "error", code: "exclusive", message: n.label + ": exclusive output group '" + og[gid].label + "' allows at most one connection", node: n.id }); }
			});
		});

		// cycle (Kahn)
		var cyc = detectCycle();
		if (cyc.length) { errs.push({ severity: "error", code: "cycle", message: "Graph has a cycle through: " + cyc.map(function (id) { return model.nodes[id].label; }).join(" → ") }); }

		return errs;
	}

	function detectCycle(extraEdge) {
		var indeg = {}, adj = {};
		Object.keys(model.nodes).forEach(function (id) { indeg[id] = 0; adj[id] = []; });
		var all = Object.keys(model.edges).map(function (k) { return model.edges[k]; });
		if (extraEdge) { all = all.concat([extraEdge]); }
		all.forEach(function (e) {
			if (indeg[e.target] == null || adj[e.source] == null) { return; }
			adj[e.source].push(e.target); indeg[e.target]++;
		});
		var q = Object.keys(indeg).filter(function (id) { return indeg[id] === 0; });
		var seen = 0;
		while (q.length) {
			var id = q.shift(); seen++;
			adj[id].forEach(function (t) { if (--indeg[t] === 0) { q.push(t); } });
		}
		if (seen === Object.keys(model.nodes).length) { return []; }
		return Object.keys(indeg).filter(function (id) { return indeg[id] > 0; });
	}

	function topoOrder() {
		var indeg = {}, adj = {};
		Object.keys(model.nodes).forEach(function (id) { indeg[id] = 0; adj[id] = []; });
		Object.keys(model.edges).forEach(function (k) {
			var e = model.edges[k];
			if (indeg[e.target] == null) { return; }
			adj[e.source].push(e.target); indeg[e.target]++;
		});
		var q = Object.keys(indeg).filter(function (id) { return indeg[id] === 0; });
		var order = [];
		while (q.length) { var id = q.shift(); order.push(id); adj[id].forEach(function (t) { if (--indeg[t] === 0) { q.push(t); } }); }
		return order;
	}

	// Single-edge connect check used while dragging a connection.
	function canConnect(srcId, srcPort, tgtId, tgtPort) {
		if (srcId === tgtId) { return { ok: false, reason: "Cannot connect a node to itself" }; }
		var sn = model.nodes[srcId], tn = model.nodes[tgtId];
		var sp = portSpec(sn, srcPort, true), tp = portSpec(tn, tgtPort, false);
		if (!sp || !tp) { return { ok: false, reason: "Unknown port" }; }
		var dup = Object.keys(model.edges).some(function (k) {
			var e = model.edges[k];
			return e.source === srcId && e.sourcePort === srcPort && e.target === tgtId && e.targetPort === tgtPort;
		});
		if (dup) { return { ok: false, reason: "That connection already exists" }; }
		if (tp.cardinality !== "MANY" && edgesInto(tgtId, tgtPort).length >= 1) {
			return { ok: false, reason: "Input '" + tgtPort + "' is ONE and already connected" };
		}
		if (!isAssignable(sp.contentType, tp.contentType)) {
			return { ok: false, reason: sp.contentType + " is not assignable to " + tp.contentType };
		}
		if (detectCycle({ source: srcId, target: tgtId }).length) { return { ok: false, reason: "Connection would create a cycle" }; }
		return { ok: true, provisional: isProvisional(sp.contentType, tp.contentType) };
	}

	// ---------------------------------------------------------------- synthetic values
	function synthValue(ct, group, portId, seq) {
		var fam = family(ct);
		var key = group + ":" + portId + ":" + (seq == null ? "" : seq);
		switch (fam) {
			case "media": {
				var sub = ct.split("/")[1];
				var ext = { image: ".jpg", video: ".mp4", audio: ".wav", document: ".pdf" }[sub] || [".jpg", ".mp4", ".wav"][randInt(key, 0, 2)];
				return "asset_" + (1000 + group) + ext;
			}
			case "hash": {
				var sub2 = ct.split("/")[1];
				var len = { md5: 8, sha256: 10, sha512: 12, chunk: 8, fingerprint: 10 }[sub2] || 8;
				var hex = ""; var r = mulberry32(hashStr(key));
				for (var i = 0; i < len; i++) { hex += "0123456789abcdef"[Math.floor(r() * 16)]; }
				return (sub2 === "fingerprint" ? "phash:" : "") + hex + "…";
			}
			case "detection": return "{box:[" + randInt(key + "x", 10, 200) + "," + randInt(key + "y", 10, 200) + ",64,64], score:0." + randInt(key + "s", 70, 99) + "}";
			case "text": {
				var sub3 = ct.split("/")[1];
				if (sub3 === "transcript") { return "[00:0" + (group % 9) + "→00:0" + ((group + 4) % 9) + "] …spoken words…"; }
				if (sub3 === "caption") { return "\"a scene with subjects\""; }
				return "\"lorem ipsum sample text\"";
			}
			case "scalar": {
				var sub4 = ct.split("/")[1];
				if (sub4 === "integer") { return String(randInt(key, 1, 6)); }
				if (sub4 === "number") { return "0." + randInt(key, 10, 98); }
				if (sub4 === "boolean") { return rand(key) > 0.5 ? "true" : "false"; }
				return "OK";
			}
			case "struct": {
				var sub5 = ct.split("/")[1];
				if (sub5 === "segments") { return "[{start:0,end:4},…]"; }
				if (sub5 === "quality") { return "{sharpness:0." + randInt(key, 30, 95) + "}"; }
				if (sub5 === "color") { return "#" + ("00000" + Math.floor(rand(key) * 0xffffff).toString(16)).slice(-6); }
				if (sub5 === "embedding") { return "[0.12,-0.04,…] (dim 512)"; }
				if (sub5 === "depthmap") { return "depth 512×512"; }
				return "{…json…}";
			}
			case "control": return "PASS";
			case "artifact": return "artifact/" + (ct.split("/")[1] || "file") + " out_" + group + ".bin";
			default: return "value";
		}
	}
	function summarize(elements) {
		if (!elements.length) { return "∅"; }
		if (elements.length === 1) { return elements[0].value; }
		return "[" + elements.slice(0, 3).map(function (e) { return e.value; }).join(", ") + (elements.length > 3 ? ", …" : "") + "] ×" + elements.length;
	}

	// ---------------------------------------------------------------- rendering
	function buildShell() {
		root.innerHTML = "";
		var wrap = H("div", { class: "pe-root" });

		// toolbar
		var tb = H("div", { class: "pe-toolbar" });
		tb.appendChild(H("div", { class: "pe-title", text: "Pipeline Editor" }));

		var gFile = H("div", { class: "pe-tb-group" });
		var demoSel = H("select", { class: "pe-select", title: "Load a demo pipeline" });
		demoSel.appendChild(H("option", { value: "", text: "Demo pipelines…" }));
		DEMOS.forEach(function (d, i) { demoSel.appendChild(H("option", { value: String(i), text: d.name })); });
		demoSel.addEventListener("change", function () { if (demoSel.value !== "") { loadDemo(parseInt(demoSel.value, 10)); demoSel.value = ""; } });
		var savedSel = H("select", { class: "pe-select", title: "Load a saved pipeline" });
		gFile.appendChild(demoSel);
		gFile.appendChild(savedSel);
		gFile.appendChild(H("button", { class: "pe-btn", text: "Save", title: "Save to this browser" }, []));
		gFile.appendChild(H("button", { class: "pe-btn", text: "Download", title: "Download as JSON" }));
		gFile.appendChild(H("button", { class: "pe-btn", text: "Open", title: "Open / paste pipeline JSON" }));
		gFile.appendChild(H("button", { class: "pe-btn", text: "Clear", title: "Empty the canvas" }));
		var fbtns = gFile.querySelectorAll("button");
		fbtns[0].addEventListener("click", doSave);
		fbtns[1].addEventListener("click", doDownload);
		fbtns[2].addEventListener("click", openJsonModal);
		fbtns[3].addEventListener("click", function () { if (confirm("Clear the canvas?")) { model.nodes = {}; model.edges = {}; commit(); renderAll(); } });
		savedSel.addEventListener("change", function () { if (savedSel.value) { loadSaved(savedSel.value); savedSel.value = ""; } });
		els.savedSel = savedSel;

		var gSrc = H("div", { class: "pe-tb-group" });
		gSrc.appendChild(H("label", { class: "pe-lbl", text: "Emit" }));
		var modeSel = H("select", { class: "pe-select" });
		modeSel.appendChild(H("option", { value: "single", text: "single asset" }));
		modeSel.appendChild(H("option", { value: "multiple", text: "multiple assets" }));
		modeSel.value = model.sourceMode;
		var countInp = H("input", { class: "pe-num", type: "number", min: "1", max: "9", value: String(model.sourceCount) });
		countInp.disabled = model.sourceMode !== "multiple";
		modeSel.addEventListener("change", function () { model.sourceMode = modeSel.value; countInp.disabled = model.sourceMode !== "multiple"; });
		countInp.addEventListener("change", function () { model.sourceCount = Math.max(1, Math.min(9, parseInt(countInp.value, 10) || 1)); countInp.value = String(model.sourceCount); });
		gSrc.appendChild(modeSel);
		gSrc.appendChild(countInp);

		var gSim = H("div", { class: "pe-tb-group" });
		els.playBtn = H("button", { class: "pe-btn is-primary", text: "▶ Play" });
		els.pauseBtn = H("button", { class: "pe-btn", text: "⏸ Pause" });
		els.stepBtn = H("button", { class: "pe-btn", text: "⤳ Step" });
		els.resetBtn = H("button", { class: "pe-btn", text: "⟲ Reset" });
		els.playBtn.addEventListener("click", function () { sim.play(); });
		els.pauseBtn.addEventListener("click", function () { sim.pause(); });
		els.stepBtn.addEventListener("click", function () { sim.step(); });
		els.resetBtn.addEventListener("click", function () { sim.reset(); });
		// Only shown while the run is actually halted — a control that is almost always
		// meaningless is just clutter.
		els.continueBtn = H("button", { class: "pe-btn is-held", text: "▷ Continue" });
		els.continueBtn.hidden = true;
		els.continueBtn.addEventListener("click", function () { sim.release(); });
		els.heldChip = H("span", { class: "pe-held-chip" });
		els.heldChip.hidden = true;
		var speed = H("input", { class: "pe-slider", type: "range", min: "0.3", max: "3", step: "0.1", value: "1", title: "Simulation speed" });
		speed.addEventListener("input", function () { sim.speed = parseFloat(speed.value); });
		gSim.appendChild(els.playBtn); gSim.appendChild(els.pauseBtn); gSim.appendChild(els.stepBtn); gSim.appendChild(els.resetBtn);
		gSim.appendChild(els.continueBtn); gSim.appendChild(els.heldChip); gSim.appendChild(speed);

		tb.appendChild(gFile); tb.appendChild(gSrc); tb.appendChild(gSim);
		wrap.appendChild(tb);

		// body: palette + canvas
		var body = H("div", { class: "pe-body" });
		els.palette = H("div", { class: "pe-palette" });
		buildPalette();
		body.appendChild(els.palette);

		var cwrap = H("div", { class: "pe-canvas-wrap" });
		els.svg = E("svg", { "class": "pe-canvas" });
		els.viewport = E("g", { "class": "pe-viewport" });
		els.gEdges = E("g", { "class": "pe-layer-edges" });
		els.gConn = E("g", { "class": "pe-layer-conn" });
		els.gNodes = E("g", { "class": "pe-layer-nodes" });
		els.gTokens = E("g", { "class": "pe-layer-tokens" });
		els.viewport.appendChild(els.gEdges); els.viewport.appendChild(els.gConn);
		els.viewport.appendChild(els.gNodes); els.viewport.appendChild(els.gTokens);
		els.svg.appendChild(els.viewport);
		cwrap.appendChild(els.svg);
		els.toast = H("div", { class: "pe-toast", hidden: "hidden" });
		cwrap.appendChild(els.toast);
		els.hint = H("div", { class: "pe-hint", text: "Add nodes from the palette, drag output → input to wire them, then press Play." });
		cwrap.appendChild(els.hint);
		body.appendChild(cwrap);
		wrap.appendChild(body);

		// bottom: errors + log
		var bottom = H("div", { class: "pe-bottom" });
		var errPanel = H("div", { class: "pe-panel pe-panel-errors" });
		els.errHead = H("div", { class: "pe-panel-head", text: "Design errors" });
		errPanel.appendChild(els.errHead);
		els.errBox = H("div", { class: "pe-errors" });
		errPanel.appendChild(els.errBox);
		bottom.appendChild(errPanel);

		var logPanel = H("div", { class: "pe-panel pe-panel-log" });
		logPanel.appendChild(H("div", { class: "pe-panel-head", text: "Action log" }));
		els.logWrap = H("div", { class: "pe-log-wrap" });
		els.logBody = H("tbody", {});
		var table = H("table", { class: "pe-log" }, [
			H("thead", {}, [H("tr", {}, [
				H("th", { text: "time" }), H("th", { text: "node" }), H("th", { text: "description" }), H("th", { text: "input" }), H("th", { text: "output" })
			])]),
			els.logBody
		]);
		els.logWrap.appendChild(table);
		logPanel.appendChild(els.logWrap);
		bottom.appendChild(logPanel);
		wrap.appendChild(bottom);

		root.appendChild(wrap);
		wireCanvasEvents();
		refreshSavedList();
	}

	function buildPalette() {
		els.palette.innerHTML = "";
		els.palette.appendChild(H("div", { class: "pe-palette-title", text: "Nodes" }));
		CATEGORY_ORDER.forEach(function (cat) {
			var kinds = Object.keys(descriptorsByKind).filter(function (k) { return descriptorsByKind[k].category === cat; });
			if (!kinds.length) { return; }
			var grp = H("div", { class: "pe-palette-group" });
			grp.appendChild(H("div", { class: "pe-palette-head", text: cat, style: "color:" + CATEGORY_COLORS[cat] }));
			kinds.forEach(function (kind) {
				var d = descriptorsByKind[kind];
				var item = H("button", { class: "pe-palette-item", title: d.description || kind });
				item.appendChild(H("span", { class: "pe-swatch", style: "background:" + CATEGORY_COLORS[cat] }));
				item.appendChild(H("span", { class: "pe-palette-name", text: d.name }));
				item.addEventListener("click", function () {
					var p = screenCenterWorld();
					var n = addNode(kind, p.x - NODE_W / 2 + (Object.keys(model.nodes).length % 3) * 24, p.y - 40 + (Object.keys(model.nodes).length % 3) * 24);
					selection.nodeId = n.id; selection.edgeId = null;
					commit(); renderAll();
				});
				grp.appendChild(item);
			});
			els.palette.appendChild(grp);
		});
	}

	function screenCenterWorld() {
		var r = els.svg.getBoundingClientRect();
		return { x: (r.width / 2 - view.tx) / view.scale, y: (r.height / 2 - view.ty) / view.scale };
	}
	function clientToWorld(clientX, clientY) {
		var r = els.svg.getBoundingClientRect();
		return { x: (clientX - r.left - view.tx) / view.scale, y: (clientY - r.top - view.ty) / view.scale };
	}
	function applyViewTransform() { els.viewport.setAttribute("transform", "translate(" + view.tx + "," + view.ty + ") scale(" + view.scale + ")"); }

	function renderAll() {
		applyViewTransform();
		renderNodes();
		renderEdges();
		els.hint.hidden = Object.keys(model.nodes).length > 0;
	}

	function renderNodes() {
		els.gNodes.innerHTML = "";
		Object.keys(model.nodes).forEach(function (id) {
			var n = model.nodes[id];
			var g = nodeGeom(n);
			var hasErr = validationErrors.some(function (e) { return e.node === id; });
			var cls = "pe-node" + (selection.nodeId === id ? " is-selected" : "") + (hasErr ? " is-error" : "") + (n.unknownKind ? " is-unknown" : "");
			var grp = E("g", { "class": cls, transform: "translate(" + n.x + "," + n.y + ")", "data-node": id });
			var cat = n.descriptor ? n.descriptor.category : "OUTPUT";
			var col = CATEGORY_COLORS[cat] || "#8fa3b8";
			grp.appendChild(E("rect", { "class": "pe-node-rect", x: 0, y: 0, width: g.w, height: g.h, rx: 9 }));
			grp.appendChild(E("rect", { "class": "pe-node-head", x: 0, y: 0, width: g.w, height: HEAD_H, rx: 9 }));
			grp.appendChild(E("rect", { "class": "pe-node-stripe", x: 0, y: 0, width: 4, height: g.h, rx: 2, fill: col }));
			var title = E("text", { "class": "pe-node-title", x: 12, y: HEAD_H / 2 + 4 });
			title.textContent = n.label + (n.unknownKind ? " (?)" : "");
			grp.appendChild(title);
			// delete button
			var del = E("g", { "class": "pe-node-del", "data-del": id, transform: "translate(" + (g.w - 18) + ",6)" });
			del.appendChild(E("rect", { x: 0, y: 0, width: 16, height: 16, rx: 4, opacity: 0 }));
			del.appendChild(E("path", { d: "M4 4l8 8M12 4l-8 8", stroke: "currentColor", "stroke-width": 1.6, "stroke-linecap": "round" }));
			grp.appendChild(del);

			g.ins.forEach(function (p, i) { grp.appendChild(portGroup(n, p, i, false)); });
			g.outs.forEach(function (p, i) { grp.appendChild(portGroup(n, p, i, true)); });

			// Breakpoint gutter, in the left margin where a debugger's would be. Always drawn —
			// faint when unarmed — because an affordance that only appears once used cannot be
			// discovered.
			var armed = !!sim.breakpoints[id];
			var gut = E("g", { "class": "pe-bp" + (armed ? " is-armed" : ""), "data-bp": id, transform: "translate(-14,14)" });
			gut.appendChild(E("rect", { x: -6, y: -7, width: 16, height: 16, rx: 4, opacity: 0 }));
			gut.appendChild(E("circle", { "class": "pe-bp-dot", cx: 1, cy: 1, r: 5 }));
			var bt = E("title"); bt.textContent = armed
				? "Stop halting here"
				: "Halt here — the node still runs, but nothing downstream starts until you continue";
			gut.appendChild(bt);
			grp.appendChild(gut);

			if (sim.heldFiring && sim.heldFiring.nodeId === id) { grp.classList.add("is-held"); }

			els.gNodes.appendChild(grp);
		});
		// Repaint whatever each node last produced. renderNodes() rebuilds the whole layer, so
		// without this every drag would wipe the results the simulation just showed.
		Object.keys(lastResults).forEach(function (id) {
			if (model.nodes[id]) { sim.showResults(lastResults[id]); }
		});
	}

	function portGroup(node, p, idx, isOut) {
		var y = portY(idx);
		var x = isOut ? NODE_W : 0;
		var col = ctColor(p.contentType);
		var many = p.cardinality === "MANY";
		var wild = isWildcard(p.contentType);
		var blocked = isOut ? false : (p.group ? groupBlocked(node, p) : false);
		var g = E("g", {
			"class": "pe-port" + (isOut ? " is-out" : " is-in") + (blocked ? " is-blocked" : ""),
			"data-node": node.id, "data-port": p.id, "data-out": isOut ? "1" : "0", "data-ct": p.contentType,
			transform: "translate(" + x + "," + y + ")"
		});
		// hit target
		g.appendChild(E("rect", { "class": "pe-port-hit", x: isOut ? -10 : -8, y: -9, width: 18, height: 18, rx: 4 }));
		var handleAttrs = { "class": "pe-handle" + (many ? " is-many" : "") + (wild ? " is-wild" : ""), x: -4, y: -4, width: 8, height: 8, rx: many ? 1 : 4, fill: wild ? "#1a1f26" : col, stroke: col };
		g.appendChild(E("rect", handleAttrs));
		if (many) { g.appendChild(E("rect", { "class": "pe-handle-many", x: isOut ? 2 : -6, y: -4, width: 8, height: 8, rx: 1, fill: "none", stroke: col, opacity: 0.6 })); }
		var lbl = E("text", { "class": "pe-port-label", x: isOut ? -12 : 12, y: 3, "text-anchor": isOut ? "end" : "start" });
		lbl.textContent = (p.label || p.id) + (many ? " ⋯" : "");
		g.appendChild(lbl);
		// tooltip
		var tt = E("title"); tt.textContent = (isOut ? "output" : "input") + " · " + p.id + " · " + p.contentType + " · " + (many ? "MANY" : "ONE") + (p.required ? "" : " · optional") + (p.description ? "\n" + p.description : "");
		g.appendChild(tt);
		return g;
	}

	// Is this grouped input blocked because a sibling in an XOR group is already wired?
	function groupBlocked(node, p) {
		if (!node.descriptor) { return false; }
		var grp = ((node.descriptor.inputGroups) || []).filter(function (x) { return x.id === p.group; })[0];
		if (!grp || grp.mode !== "XOR") { return false; }
		if (edgesInto(node.id, p.id).length) { return false; } // itself wired → not blocked
		return ((node.descriptor.inputPorts) || []).some(function (o) { return o.group === p.group && o.id !== p.id && edgesInto(node.id, o.id).length; });
	}

	function renderEdges() {
		els.gEdges.innerHTML = "";
		Object.keys(model.edges).forEach(function (id) {
			var e = model.edges[id];
			var sn = model.nodes[e.source], tn = model.nodes[e.target];
			if (!sn || !tn) { return; }
			var a = portPos(sn, e.sourcePort, true), b = portPos(tn, e.targetPort, false);
			var sp = portSpec(sn, e.sourcePort, true);
			var col = sp ? ctColor(sp.contentType) : "#8fa3b8";
			var tp = portSpec(tn, e.targetPort, false);
			var prov = sp && tp && isProvisional(sp.contentType, tp.contentType);
			var hasErr = validationErrors.some(function (x) { return x.edge === id; });
			var d = bez(a.x, a.y, b.x, b.y);
			var cls = "pe-edge branch-" + e.branch + (prov ? " is-provisional" : "") + (hasErr ? " is-error" : "") + (selection.edgeId === id ? " is-selected" : "");
			els.gEdges.appendChild(E("path", { "class": "pe-edge-hit", d: d, "data-edge": id }));
			els.gEdges.appendChild(E("path", { "class": cls, d: d, "data-edge": id, stroke: hasErr ? null : col }));
			if (e.branch !== "ANY") {
				var mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
				var chip = E("g", { "class": "pe-branch-chip branch-" + e.branch, transform: "translate(" + mx + "," + my + ")" });
				chip.appendChild(E("rect", { x: -22, y: -9, width: 44, height: 18, rx: 9 }));
				var t = E("text", { x: 0, y: 4, "text-anchor": "middle" }); t.textContent = e.branch; chip.appendChild(t);
				els.gEdges.appendChild(chip);
			}
		});
	}

	function updateNodeAndEdges(id) {
		var grp = els.gNodes.querySelector('[data-node="' + id + '"]');
		var n = model.nodes[id];
		if (grp && n) { grp.setAttribute("transform", "translate(" + n.x + "," + n.y + ")"); }
		renderEdges();
	}

	// ---------------------------------------------------------------- errors panel
	function renderErrors() {
		els.errBox.innerHTML = "";
		var errs = validationErrors;
		els.errHead.textContent = "Design errors" + (errs.length ? " (" + errs.length + ")" : "");
		els.errHead.classList.toggle("has-errors", errs.some(function (e) { return e.severity === "error"; }));
		if (!errs.length) { els.errBox.appendChild(H("div", { class: "pe-error-empty", text: "✓ No design errors" })); return; }
		errs.forEach(function (e) {
			var row = H("div", { class: "pe-error-row sev-" + e.severity });
			row.appendChild(H("span", { class: "pe-error-dot" }));
			row.appendChild(H("span", { class: "pe-error-msg", text: e.message }));
			row.addEventListener("click", function () {
				selection.nodeId = e.node || null; selection.edgeId = e.edge || null;
				renderNodes(); renderEdges();
			});
			els.errBox.appendChild(row);
		});
	}

	function hasBlockingErrors() { return validationErrors.some(function (e) { return e.severity === "error"; }); }

	// commit = validate + refresh error UI + gate Play. Call after any structural change.
	function commit() {
		Object.keys(model.nodes).forEach(function (id) { model.nodes[id].descriptor = descriptorsByKind[model.nodes[id].kind] || null; model.nodes[id].unknownKind = !model.nodes[id].descriptor; });
		validationErrors = validate();
		renderErrors();
		if (sim.state === "idle") { sim.syncControls(); }
	}

	// ---------------------------------------------------------------- toast
	var toastTimer = null;
	function toast(msg, x, y) {
		els.toast.textContent = msg;
		els.toast.hidden = false;
		els.toast.style.left = x + "px";
		els.toast.style.top = y + "px";
		if (toastTimer) { clearTimeout(toastTimer); }
		toastTimer = setTimeout(function () { els.toast.hidden = true; }, 2200);
	}

	// ---------------------------------------------------------------- canvas interactions
	var drag = null;   // node drag / pan
	var conn = null;   // connection drag

	function wireCanvasEvents() {
		els.svg.addEventListener("pointerdown", onPointerDown);
		window.addEventListener("pointermove", onPointerMove);
		window.addEventListener("pointerup", onPointerUp);
		els.svg.addEventListener("wheel", onWheel, { passive: false });
		els.svg.addEventListener("dblclick", onDblClick);
		document.addEventListener("keydown", onKeyDown);
	}

	// Double-clicking an edge that leaves a filter node cycles its branch ANY → PASS → REJECT.
	function onDblClick(ev) {
		var hit = ev.target.closest ? ev.target.closest(".pe-edge-hit") : null;
		if (!hit) { return; }
		var e = model.edges[hit.getAttribute("data-edge")];
		if (!e) { return; }
		var sn = model.nodes[e.source];
		if (!sn || !sn.descriptor || sn.descriptor.category !== "FILTER") { toast("Branch routing only applies to edges leaving a filter", ev.clientX - els.svg.getBoundingClientRect().left, ev.clientY - els.svg.getBoundingClientRect().top); return; }
		e.branch = e.branch === "ANY" ? "PASS" : e.branch === "PASS" ? "REJECT" : "ANY";
		commit(); renderEdges();
	}

	function findPort(target) {
		var g = target.closest ? target.closest(".pe-port") : null;
		if (!g) { return null; }
		return { node: g.getAttribute("data-node"), port: g.getAttribute("data-port"), out: g.getAttribute("data-out") === "1", ct: g.getAttribute("data-ct"), el: g };
	}

	function onPointerDown(ev) {
		var t = ev.target;
		var portInfo = findPort(t);
		if (portInfo && portInfo.out) {
			// start a connection from an output port
			ev.preventDefault();
			conn = { srcId: portInfo.node, srcPort: portInfo.port, ct: portInfo.ct };
			highlightTargets(portInfo);
			var w = clientToWorld(ev.clientX, ev.clientY);
			conn.path = E("path", { "class": "pe-conn-preview", d: bez(0, 0, 0, 0) });
			els.gConn.appendChild(conn.path);
			updateConnPreview(w);
			return;
		}
		var delEl = t.closest ? t.closest(".pe-node-del") : null;
		if (delEl) { ev.preventDefault(); removeNode(delEl.getAttribute("data-del")); commit(); renderAll(); return; }

		// Both of these live inside .pe-node, so they must be handled before the node-drag
		// branch below — otherwise arming a breakpoint would select and drag the node.
		var bpEl = t.closest ? t.closest(".pe-bp") : null;
		if (bpEl) { ev.preventDefault(); sim.toggleBreakpoint(bpEl.getAttribute("data-bp")); return; }

		var resEl = t.closest ? t.closest(".pe-result-row") : null;
		if (resEl) {
			ev.preventDefault();
			openResultDetail(resEl.getAttribute("data-node"), resEl.getAttribute("data-port"));
			return;
		}

		var nodeEl = t.closest ? t.closest(".pe-node") : null;
		if (nodeEl && !portInfo) {
			ev.preventDefault();
			var id = nodeEl.getAttribute("data-node");
			selection.nodeId = id; selection.edgeId = null;
			var n = model.nodes[id];
			var w2 = clientToWorld(ev.clientX, ev.clientY);
			drag = { kind: "node", id: id, dx: w2.x - n.x, dy: w2.y - n.y, moved: false };
			renderNodes();
			return;
		}
		var edgeEl = t.closest ? t.closest(".pe-edge-hit") : null;
		if (edgeEl) { selection.edgeId = edgeEl.getAttribute("data-edge"); selection.nodeId = null; renderEdges(); renderNodes(); return; }

		// background → pan
		selection.nodeId = null; selection.edgeId = null; renderNodes(); renderEdges();
		drag = { kind: "pan", sx: ev.clientX, sy: ev.clientY, tx: view.tx, ty: view.ty };
	}

	function onPointerMove(ev) {
		if (conn) { updateConnPreview(clientToWorld(ev.clientX, ev.clientY)); return; }
		if (!drag) { return; }
		if (drag.kind === "node") {
			var n = model.nodes[drag.id]; if (!n) { return; }
			var w = clientToWorld(ev.clientX, ev.clientY);
			n.x = w.x - drag.dx; n.y = w.y - drag.dy; drag.moved = true;
			updateNodeAndEdges(drag.id);
		} else if (drag.kind === "pan") {
			view.tx = drag.tx + (ev.clientX - drag.sx);
			view.ty = drag.ty + (ev.clientY - drag.sy);
			applyViewTransform();
		}
	}

	function onPointerUp(ev) {
		if (conn) {
			var over = document.elementFromPoint(ev.clientX, ev.clientY);
			var pi = over ? findPort(over) : null;
			if (pi && !pi.out) {
				var res = canConnect(conn.srcId, conn.srcPort, pi.node, pi.port);
				if (res.ok) {
					addEdge(conn.srcId, conn.srcPort, pi.node, pi.port, "ANY");
					commit(); renderAll();
				} else {
					toast(res.reason, ev.clientX - els.svg.getBoundingClientRect().left + 8, ev.clientY - els.svg.getBoundingClientRect().top + 8);
				}
			}
			clearTargets();
			if (conn.path && conn.path.parentNode) { conn.path.parentNode.removeChild(conn.path); }
			conn = null;
			return;
		}
		if (drag && drag.kind === "node" && drag.moved) {
			var n = model.nodes[drag.id];
			n.x = Math.round(n.x / GRID) * GRID; n.y = Math.round(n.y / GRID) * GRID;
			updateNodeAndEdges(drag.id);
		}
		drag = null;
	}

	function updateConnPreview(w) {
		var sn = model.nodes[conn.srcId];
		var a = portPos(sn, conn.srcPort, true);
		conn.path.setAttribute("d", bez(a.x, a.y, w.x, w.y));
	}

	function highlightTargets(srcInfo) {
		var ports = els.gNodes.querySelectorAll('.pe-port.is-in');
		Array.prototype.forEach.call(ports, function (g) {
			var nodeId = g.getAttribute("data-node");
			var portId = g.getAttribute("data-port");
			var res = canConnect(srcInfo.node, srcInfo.port, nodeId, portId);
			if (res.ok) { g.classList.add(res.provisional ? "is-candidate-prov" : "is-candidate"); }
			else { g.classList.add("is-incompatible"); }
		});
	}
	function clearTargets() {
		var ports = els.gNodes.querySelectorAll('.pe-port');
		Array.prototype.forEach.call(ports, function (g) { g.classList.remove("is-candidate", "is-candidate-prov", "is-incompatible"); });
	}

	function onWheel(ev) {
		ev.preventDefault();
		var r = els.svg.getBoundingClientRect();
		var mx = ev.clientX - r.left, my = ev.clientY - r.top;
		var wx = (mx - view.tx) / view.scale, wy = (my - view.ty) / view.scale;
		var factor = ev.deltaY < 0 ? 1.1 : 1 / 1.1;
		var ns = Math.max(0.4, Math.min(2, view.scale * factor));
		view.tx = mx - wx * ns; view.ty = my - wy * ns; view.scale = ns;
		applyViewTransform();
	}

	function onKeyDown(ev) {
		if (ev.key === "Delete" || ev.key === "Backspace") {
			var tag = (ev.target && ev.target.tagName) || "";
			if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") { return; }
			if (selection.nodeId) { removeNode(selection.nodeId); commit(); renderAll(); ev.preventDefault(); }
			else if (selection.edgeId) { delete model.edges[selection.edgeId]; selection.edgeId = null; commit(); renderAll(); ev.preventDefault(); }
		} else if (ev.key === "Escape") {
			if (conn) { clearTargets(); if (conn.path && conn.path.parentNode) { conn.path.parentNode.removeChild(conn.path); } conn = null; }
			els.toast.hidden = true;
			if (els.modal) { closeModal(); }
		}
	}

	// ---------------------------------------------------------------- simulation
	var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
	var TRAVEL = 1, NODE_DELAY = 0.18, GROUP_STAGGER = 0.7, MS_PER_TICK = 850;

	var sim = {
		state: "idle",     // idle | running | paused
		clock: 0, maxT: 0, speed: 1, raf: null, lastTs: null,
		tokens: [], firings: [], firedCount: 0,

		build: function () {
			// Discrete dataflow computation producing an ordered timeline of firings + travelling tokens.
			this.tokens = []; this.firings = []; this.firedCount = 0; this.clock = 0;
			var order = topoOrder();
			var count = model.sourceMode === "multiple" ? model.sourceCount : 1;
			var self = this;
			// per-node: outputElements[group][portId] = [{value, seq}], fireTimes[group] = number (for SINGLE) or the max
			var out = {};       // nodeId -> group -> portId -> [elements]
			var lastFire = {};  // nodeId -> group -> latest firing time (for chaining)
			order.forEach(function (id) { out[id] = {}; lastFire[id] = {}; });

			function ensure(map, g) { if (!map[g]) { map[g] = {}; } return map[g]; }

			for (var g = 0; g < count; g++) {
				var base = g * GROUP_STAGGER;
				order.forEach(function (id) {
					var node = model.nodes[id];
					var d = node.descriptor;
					if (!d) { return; }
					var isSource = d.category === "SOURCE";
					if (isSource) {
						// emit one media asset for this group
						var t = base;
						ensure(out[id], g);
						(d.outputPorts || []).forEach(function (p) {
							out[id][g][p.id] = [{ value: synthValue(p.contentType, g, p.id, 0), seq: 0, ct: p.contentType }];
						});
						var mediaPort = (d.outputPorts || []).filter(function (p) { return p.id === "media"; })[0] || (d.outputPorts || [])[0];
						var assetVal = mediaPort ? out[id][g][mediaPort.id][0].value : "asset";
						self.pushFiring(id, t, node.label, "Emit asset " + (g + 1), "—", assetVal);
						lastFire[id][g] = t;
						self.emitTokens(id, g, t, out);
						return;
					}
					// gather inputs that arrived for this node/group from upstream out[] via edges
					var inbox = {};        // portId -> [{value, seq, ct, arriveT, edgeId, from}]
					var arriveMax = base;
					(d.inputPorts || []).forEach(function (p) { inbox[p.id] = []; });
					edgesInto(id).forEach(function (e) {
						var srcOut = out[e.source] && out[e.source][g] && out[e.source][g][e.sourcePort];
						if (!srcOut) { return; }
						// branch routing: only carry when the branch admits (source is a filter → verdict)
						var admit = true;
						if (e.branch === "PASS" || e.branch === "REJECT") {
							var verdict = self.filterVerdict(e.source, g);
							admit = (e.branch === "PASS") === verdict;
						}
						if (!admit) { return; }
						var tp = portSpec(node, e.targetPort, false);
						if (!tp || !inbox[e.targetPort]) { return; }
						srcOut.forEach(function (el) {
							inbox[e.targetPort].push({ value: el.value, seq: el.seq, ct: el.ct, from: e.source });
						});
						var ft = (lastFire[e.source] && lastFire[e.source][g]) || base;
						arriveMax = Math.max(arriveMax, ft + TRAVEL);
					});

					// does this node have any input at all?
					var anyInput = Object.keys(inbox).some(function (k) { return inbox[k].length; });
					if (!anyInput) { return; }  // upstream rejected / not connected → node does not run this group

					// PER_ELEMENT? a ONE input receiving >1 element
					var driverPort = null, driverCount = 1;
					(d.inputPorts || []).forEach(function (p) {
						if (p.cardinality !== "MANY" && inbox[p.id].length > 1) { driverPort = p.id; driverCount = inbox[p.id].length; }
					});

					ensure(out[id], g);
					(d.outputPorts || []).forEach(function (p) { out[id][g][p.id] = []; });

					if (driverPort) {
						// fan-out: one firing per element
						for (var k = 0; k < driverCount; k++) {
							var fin = {};
							(d.inputPorts || []).forEach(function (p) {
								if (p.id === driverPort) { fin[p.id] = [inbox[p.id][k]]; }
								else if (p.cardinality === "MANY") { fin[p.id] = inbox[p.id]; }
								else { fin[p.id] = inbox[p.id].slice(0, 1); }
							});
							var ft2 = arriveMax + NODE_DELAY;
							self.fire(id, g, ft2, node, fin, k, out);
						}
						lastFire[id][g] = arriveMax + NODE_DELAY;
					} else {
						// SINGLE / gather: one firing with everything
						var fin2 = {};
						(d.inputPorts || []).forEach(function (p) { fin2[p.id] = inbox[p.id]; });
						var ft3 = arriveMax + NODE_DELAY;
						self.fire(id, g, ft3, node, fin2, null, out);
						lastFire[id][g] = ft3;
					}
					self.emitTokens(id, g, lastFire[id][g], out);
				});
			}

			this.firings.sort(function (a, b) { return a.t - b.t; });
			this.maxT = 0;
			this.tokens.forEach(function (tk) { self.maxT = Math.max(self.maxT, tk.endT); });
			this.firings.forEach(function (f) { self.maxT = Math.max(self.maxT, f.t); });
			this.maxT += 0.6;
		},

		filterVerdict: function (nodeId, g) {
			return rand("verdict:" + nodeId + ":" + g) > 0.28; // ~72% pass
		},

		// produce a firing's outputs into out[] and log it
		fire: function (id, g, t, node, inputs, elemIdx, out) {
			var d = node.descriptor;
			var inSummary = Object.keys(inputs).filter(function (k) { return inputs[k].length; })
				.map(function (k) { return k + "=" + summarize(inputs[k]); }).join("  ") || "—";
			var desc, outSummary = [];
			var isFilter = d.category === "FILTER";
			var verdict = isFilter ? this.filterVerdict(id, g) : null;

			(d.outputPorts || []).forEach(function (p) {
				var els2 = [];
				if (p.cardinality === "MANY") {
					var n = d.kind === "facedetect" ? randInt("fd:" + id + ":" + g, 1, 4)
						: (p.contentType.indexOf("text/") === 0 && inputs["detections"]) ? inputs["detections"].length
						: randInt("many:" + id + ":" + p.id + ":" + g, 2, 3);
					for (var i = 0; i < n; i++) { els2.push({ value: synthValue(p.contentType, g, p.id, i), seq: i, ct: p.contentType }); }
				} else {
					var v;
					if (isFilter && family(p.contentType) === "control") { v = verdict ? "PASS" : "REJECT"; }
					else if (family(p.contentType) === "media") {
						// a media pass-through carries the incoming asset unchanged, so an asset keeps its identity along the graph
						var mi = null;
						Object.keys(inputs).forEach(function (k) { inputs[k].forEach(function (el) { if (!mi && family(el.ct) === "media") { mi = el; } }); });
						v = mi ? mi.value : synthValue(p.contentType, g, p.id, elemIdx || 0);
					}
					else { v = synthValue(p.contentType, g, p.id, elemIdx || 0); }
					els2.push({ value: v, seq: elemIdx || 0, ct: p.contentType });
				}
				// A PER_ELEMENT firing appends its single element to the aggregate output (seq = element index)
				if (elemIdx != null && p.cardinality !== "MANY") { els2[0].seq = elemIdx; out[id][g][p.id].push(els2[0]); }
				else { out[id][g][p.id] = els2; }
				outSummary.push(p.id + "=" + summarize(out[id][g][p.id].slice(-els2.length)));
			});

			if (isFilter) { desc = verdict ? "PASS" : "REJECT"; }
			else if (d.kind === "facedetect") { desc = "Detected " + (out[id][g]["detections"] ? out[id][g]["detections"].length : 0) + " faces"; }
			else if (d.kind === "facedescription") { desc = "Gathered " + (inputs["detections"] ? inputs["detections"].length : 0) + " faces → descriptions"; }
			else if (elemIdx != null) { desc = "Process element " + (elemIdx + 1); }
			else if (d.category === "OUTPUT") { desc = "Synced to " + node.label; }
			else { desc = node.label; }

			// The structured outputs travel with the firing, not just their one-line summary: the
			// result strip and the detail overlay both need the elements themselves.
			var ports = {};
			(d.outputPorts || []).forEach(function (p) {
				ports[p.id] = { contentType: p.contentType, cardinality: p.cardinality || "ONE", elements: (out[id][g][p.id] || []).slice() };
			});
			this.pushFiring(id, t, node.label + (elemIdx != null ? " [" + (elemIdx + 1) + "]" : ""), desc, inSummary, outSummary.join("  ") || "—", ports);
		},

		pushFiring: function (id, t, name, desc, inS, outS, ports) {
			this.firings.push({ nodeId: id, t: t, name: name, desc: desc, input: inS, output: outS, ports: ports || {}, logged: false, released: false });
		},

		// create travelling-token animations for each outgoing edge of a node firing
		emitTokens: function (id, g, tFire, out) {
			var self = this;
			edgesFrom(id).forEach(function (e) {
				var srcOut = out[id] && out[id][g] && out[id][g][e.sourcePort];
				if (!srcOut || !srcOut.length) { return; }
				// branch routing for animation
				if (e.branch === "PASS" || e.branch === "REJECT") {
					var verdict = self.filterVerdict(id, g);
					if ((e.branch === "PASS") !== verdict) { return; }
				}
				var sp = portSpec(model.nodes[id], e.sourcePort, true);
				var col = sp ? ctColor(sp.contentType) : "#8fa3b8";
				srcOut.forEach(function (el, i) {
					var start = tFire + i * 0.12;   // stagger MANY elements into a stream
					self.tokens.push({ edgeId: e.id, startT: start, endT: start + TRAVEL, color: col, group: g });
				});
			});
		},

		play: function () {
			if (hasBlockingErrors()) { toast("Fix design errors before running", 20, 20); return; }
			// Play on a held run means "carry on from here", which is what Continue does.
			if (this.state === "held") { this.release(); return; }
			if (this.state === "idle") { this.reset(true); this.build(); if (!this.firings.length) { toast("Nothing to simulate — add a source", 20, 20); return; } }
			this.state = "running";
			this.syncControls();
			if (reduceMotion) { this.clock = this.maxT; this.flushFirings(); this.drawTokens(); this.finish(); return; }
			this.lastTs = null;
			this.loop();
		},
		pause: function () { if (this.state === "running") { this.state = "paused"; els.playBtn.textContent = "▶ Play"; this.syncControls(); if (this.raf) { cancelAnimationFrame(this.raf); } } },
		step: function () {
			if (this.state === "idle") { this.reset(true); this.build(); if (!this.firings.length) { return; } this.state = "paused"; }
			// A step out of a hold lets exactly that one firing through and stops at the next
			// event boundary — which, if the next node is also halted, is the next hold.
			if (this.state === "held") { this.heldFiring.released = true; this.clearHold(); this.state = "paused"; }
			// advance to the next event boundary (next firing or token end after clock)
			var next = this.maxT;
			this.firings.forEach(function (f) { if (f.t > this.clock + 1e-6 && f.t < next) { next = f.t; } }, this);
			this.tokens.forEach(function (tk) { if (tk.endT > this.clock + 1e-6 && tk.endT < next) { next = tk.endT; } }, this);
			this.clock = next;
			this.flushFirings();
			this.drawTokens();
			els.playBtn.textContent = "▶ Play";
			this.syncControls();
			if (this.state !== "held" && this.clock >= this.maxT - 1e-6) { this.finish(); }
		},
		reset: function (keepState) {
			if (this.raf) { cancelAnimationFrame(this.raf); this.raf = null; }
			this.clock = 0; this.tokens = []; this.firings = []; this.firedCount = 0;
			els.gTokens.innerHTML = "";
			els.logBody.innerHTML = "";
			this.clearHold();
			this.clearResults();
			Array.prototype.forEach.call(els.gNodes.querySelectorAll(".pe-node.is-firing"), function (n) { n.classList.remove("is-firing"); });
			if (!keepState) { this.state = "idle"; els.playBtn.textContent = "▶ Play"; }
			// Breakpoints survive a reset. They are how you set the run up before pressing Play,
			// so clearing them here would make arming one before a run impossible.
			this.syncControls();
		},
		finish: function () {
			this.state = "idle"; els.playBtn.textContent = "▶ Play";
			this.clearHold();
			this.syncControls();
			if (this.raf) { cancelAnimationFrame(this.raf); this.raf = null; }
			els.gTokens.innerHTML = "";
		},
		loop: function () {
			var self = this;
			function frame(ts) {
				if (self.state !== "running") { return; }
				if (self.lastTs === null) { self.lastTs = ts; }
				var dt = (ts - self.lastTs) / (MS_PER_TICK / self.speed);
				self.lastTs = ts;
				self.clock += dt;
				self.flushFirings();
				self.drawTokens();
				if (self.clock >= self.maxT) { self.finish(); return; }
				self.raf = requestAnimationFrame(frame);
			}
			self.raf = requestAnimationFrame(frame);
		},
		/**
		 * Log every firing the clock has reached — and stop at the first one a breakpoint holds.
		 *
		 * The halt lands *after* the firing is logged and its results are painted, mirroring the
		 * product: the node ran, what it produced is on screen, and only what comes next is
		 * withheld. The clock is wound back to the held firing so resuming does not skip work
		 * that would have happened in the meantime.
		 */
		flushFirings: function () {
			var self = this;
			var held = null;
			this.firings.forEach(function (f) {
				if (held) { return; }
				if (!f.logged && f.t <= self.clock + 1e-6) {
					f.logged = true;
					self.appendLog(f);
					self.flashNode(f.nodeId);
					self.showResults(f);
					if (self.breakpoints[f.nodeId] && !f.released) { held = f; }
				}
			});
			if (held) { this.hold(held); }
		},

		/** Node ids the simulator halts at, as a set. */
		breakpoints: {},
		/** The firing currently being held, or null. */
		heldFiring: null,

		hold: function (f) {
			this.heldFiring = f;
			this.state = "held";
			this.clock = f.t;
            if (this.raf) { cancelAnimationFrame(this.raf); this.raf = null; }
			els.gTokens.innerHTML = "";
			var g = els.gNodes.querySelector('[data-node="' + f.nodeId + '"]');
			if (g) { g.classList.add("is-held"); }
			this.syncControls();
		},

		/** Let the held firing through and carry on at full speed. */
		release: function () {
			if (!this.heldFiring) { return; }
			this.heldFiring.released = true;
			this.clearHold();
			this.state = "paused";
			this.play();
		},

		clearHold: function () {
			if (this.heldFiring) {
				var g = els.gNodes.querySelector('[data-node="' + this.heldFiring.nodeId + '"]');
				if (g) { g.classList.remove("is-held"); }
			}
			this.heldFiring = null;
		},

		toggleBreakpoint: function (nodeId) {
			if (this.breakpoints[nodeId]) { delete this.breakpoints[nodeId]; }
			else { this.breakpoints[nodeId] = true; }
			// Disarming the node currently holding must let the run continue, or clearing a
			// breakpoint would strand the simulation with nothing left on screen to explain it.
			if (!this.breakpoints[nodeId] && this.heldFiring && this.heldFiring.nodeId === nodeId) {
				this.heldFiring.released = true;
				this.clearHold();
				this.state = "paused";
			}
			renderNodes();
			this.syncControls();
		},

		/** Enable exactly the controls that mean something in the current state. */
		syncControls: function () {
			// Called from renderErrors, which can run before the toolbar exists on first paint.
			if (!els.playBtn || !els.continueBtn) { return; }
			var isHeld = this.state === "held";
			els.playBtn.disabled = isHeld ? true : (this.state === "running" || hasBlockingErrors());
			els.continueBtn.hidden = !isHeld;
			els.heldChip.hidden = !isHeld;
			if (isHeld && this.heldFiring) {
				els.heldChip.textContent = "‖ held at " + this.heldFiring.name;
			}
		},
		appendLog: function (f) {
			var tr = H("tr", {});
			tr.appendChild(H("td", { class: "pe-log-t", text: fmtTime(f.t) }));
			tr.appendChild(H("td", { class: "pe-log-node", text: f.name }));
			tr.appendChild(H("td", { text: f.desc }));
			tr.appendChild(H("td", { class: "pe-log-io", text: f.input }));
			tr.appendChild(H("td", { class: "pe-log-io", text: f.output }));
			els.logBody.appendChild(tr);
			els.logWrap.scrollTop = els.logWrap.scrollHeight;
		},
		flashNode: function (id) {
			var g = els.gNodes.querySelector('[data-node="' + id + '"]');
			if (!g) { return; }
			g.classList.add("is-firing");
			setTimeout(function () { g.classList.remove("is-firing"); }, 450);
		},
		/**
		 * Paint a firing's outputs onto its node, one row per output port.
		 *
		 * The same idea as the product's node result strip, and deliberately the same visual
		 * language: a family-coloured dot, the port id, and a short rendering of the value. What
		 * differs is only that these values are synthetic — see `synthValue`.
		 */
		showResults: function (f) {
			var g = els.gNodes.querySelector('[data-node="' + f.nodeId + '"]');
			if (!g) { return; }
			var old = g.querySelector(".pe-results");
			if (old) { g.removeChild(old); }
			var portIds = Object.keys(f.ports).filter(function (k) { return f.ports[k].elements.length; });
			if (!portIds.length) { return; }
			var geom = nodeGeom(model.nodes[f.nodeId]);
			var strip = E("g", { "class": "pe-results", transform: "translate(0," + (geom.h + 6) + ")" });
			portIds.slice(0, MAX_RESULT_ROWS).forEach(function (portId, i) {
				var port = f.ports[portId];
				var row = E("g", { "class": "pe-result-row", "data-node": f.nodeId, "data-port": portId, transform: "translate(0," + (i * 15) + ")" });
				row.appendChild(E("rect", { "class": "pe-result-hit", x: 0, y: -10, width: geom.w, height: 14, rx: 3 }));
				row.appendChild(E("circle", { "class": "pe-result-dot", cx: 8, cy: -3, r: 3, fill: ctColor(port.contentType) }));
				var label = E("text", { "class": "pe-result-label", x: 16, y: 0 });
				label.textContent = portId + " " + summarize(port.elements);
				row.appendChild(label);
				var tt = E("title"); tt.textContent = portId + " · " + port.contentType + " — click to enlarge";
				row.appendChild(tt);
				strip.appendChild(row);
			});
			if (portIds.length > MAX_RESULT_ROWS) {
				var more = E("text", { "class": "pe-result-more", x: 16, y: MAX_RESULT_ROWS * 15 });
				more.textContent = "+" + (portIds.length - MAX_RESULT_ROWS) + " more";
				strip.appendChild(more);
			}
			g.appendChild(strip);
			// Remembered per node so a re-render (a drag, a selection) does not wipe the results.
			lastResults[f.nodeId] = f;
		},

		clearResults: function () {
			lastResults = {};
			Array.prototype.forEach.call(els.gNodes.querySelectorAll(".pe-results"), function (n) {
				n.parentNode.removeChild(n);
			});
		},

		drawTokens: function () {
			els.gTokens.innerHTML = "";
			var self = this;
			this.tokens.forEach(function (tk) {
				if (self.clock < tk.startT || self.clock > tk.endT) { return; }
				var pathEl = els.gEdges.querySelector('path.pe-edge[data-edge="' + tk.edgeId + '"]');
				if (!pathEl) { return; }
				var p = (self.clock - tk.startT) / (tk.endT - tk.startT);
				var len = pathEl.getTotalLength();
				var pt = pathEl.getPointAtLength(p * len);
				els.gTokens.appendChild(E("circle", { "class": "pe-token", cx: pt.x, cy: pt.y, r: 5, fill: tk.color }));
			});
		}
	};
	// ---------------------------------------------------------------- result detail overlay

	/**
	 * Enlarge one output port, mirroring the product's detail view.
	 *
	 * Which tabs are offered is decided by *what the payload carries*, not by its declared
	 * family — the same rule the product uses, and for the same reason: a `media/*` port that
	 * produced nothing has no player to offer. Raw is always present and always last, because
	 * whatever else failed to apply the payload is still readable.
	 */
	function openResultDetail(nodeId, portId) {
		var f = lastResults[nodeId];
		if (!f || !f.ports[portId]) { return; }
		var port = f.ports[portId];
		closeResultDetail();

		var views = [];
		if (port.elements.length > 1) { views.push({ id: "table", label: "Table" }); }
		if (family(port.contentType) === "media" || family(port.contentType) === "artifact") {
			views.push({ id: "image", label: "Preview" });
		}
		if (port.elements.length) { views.push({ id: "value", label: "Value" }); }
		views.push({ id: "raw", label: "Raw" });

		var ov = H("div", { class: "pe-detail" });
		var card = H("div", { class: "pe-detail-card" });

		var head = H("div", { class: "pe-detail-head" });
		var dot = H("span", { class: "pe-detail-dot" });
		dot.style.background = ctColor(port.contentType);
		head.appendChild(dot);
		var titles = H("div", { class: "pe-detail-titles" });
		titles.appendChild(H("div", { class: "pe-detail-title", text: f.name + " · " + portId }));
		titles.appendChild(H("div", {
			class: "pe-detail-sub",
			text: port.contentType + " · " + port.cardinality
				+ (port.elements.length > 1 ? " · " + port.elements.length + " elements" : "")
		}));
		head.appendChild(titles);
		var close = H("button", { class: "pe-btn pe-detail-close", text: "×", title: "Close" });
		close.addEventListener("click", closeResultDetail);
		head.appendChild(close);
		card.appendChild(head);

		var tabs = H("div", { class: "pe-detail-tabs" });
		var bodyEl = H("div", { class: "pe-detail-body" });
		views.forEach(function (v, i) {
			var b = H("button", { class: "pe-detail-tab" + (i === 0 ? " is-active" : ""), text: v.label });
			b.addEventListener("click", function () {
				Array.prototype.forEach.call(tabs.children, function (c) { c.classList.remove("is-active"); });
				b.classList.add("is-active");
				renderDetailView(bodyEl, v.id, port);
			});
			tabs.appendChild(b);
		});
		if (views.length > 1) { card.appendChild(tabs); }
		card.appendChild(bodyEl);
		renderDetailView(bodyEl, views[0].id, port);

		ov.appendChild(card);
		// A click on the backdrop closes; a click inside must not.
		ov.addEventListener("click", function (ev) { if (ev.target === ov) { closeResultDetail(); } });
		document.body.appendChild(ov);
		els.detail = ov;
		document.addEventListener("keydown", detailKeyHandler);
	}

	function detailKeyHandler(ev) { if (ev.key === "Escape") { closeResultDetail(); } }

	function closeResultDetail() {
		if (els.detail && els.detail.parentNode) { els.detail.parentNode.removeChild(els.detail); }
		els.detail = null;
		document.removeEventListener("keydown", detailKeyHandler);
	}

	function renderDetailView(host, viewId, port) {
		host.innerHTML = "";
		if (viewId === "table") {
			var tbl = H("table", { class: "pe-detail-table" });
			var thead = H("thead", {});
			var hr = H("tr", {});
			hr.appendChild(H("th", { text: "#" }));
			hr.appendChild(H("th", { text: "value" }));
			thead.appendChild(hr); tbl.appendChild(thead);
			var tb = H("tbody", {});
			port.elements.forEach(function (el, i) {
				var tr = H("tr", {});
				tr.appendChild(H("td", { text: String(el.seq != null ? el.seq : i) }));
				tr.appendChild(H("td", { text: String(el.value) }));
				tb.appendChild(tr);
			});
			tbl.appendChild(tb);
			host.appendChild(tbl);
			return;
		}
		if (viewId === "image") {
			// No bytes exist here — this editor has no worker and no files. Saying so beats a
			// permanently broken image, and it is exactly what the product shows for a port whose
			// value is a path on a machine the browser cannot reach.
			host.appendChild(H("div", { class: "pe-detail-note", text: "In a real run this is a thumbnail of what the node produced, sent back by the worker that made it. Nothing is rendered here — the browser editor has no files." }));
			host.appendChild(H("div", { class: "pe-detail-path", text: String(port.elements.length ? port.elements[0].value : "") }));
			return;
		}
		if (viewId === "value") {
			host.appendChild(H("pre", {
				class: "pe-detail-pre",
				text: port.elements.length === 1
					? String(port.elements[0].value)
					: JSON.stringify(port.elements.map(function (e) { return e.value; }), null, 2)
			}));
			return;
		}
		host.appendChild(H("pre", { class: "pe-detail-pre", text: JSON.stringify(port, null, 2) }));
	}

	function fmtTime(t) {
		var ms = Math.round(t * MS_PER_TICK);
		var s = Math.floor(ms / 1000), mm = Math.floor(s / 60);
		return (mm < 10 ? "0" : "") + mm + ":" + ((s % 60) < 10 ? "0" : "") + (s % 60) + "." + ("00" + (ms % 1000)).slice(-3);
	}

	// ---------------------------------------------------------------- persistence
	var LS_INDEX = "ml.pipeline.index";
	function lsGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
	function lsSet(k, v) { try { window.localStorage.setItem(k, v); return true; } catch (e) { return false; } }
	function savedNames() { try { return JSON.parse(lsGet(LS_INDEX) || "[]"); } catch (e) { return []; } }
	function refreshSavedList() {
		var names = savedNames();
		els.savedSel.innerHTML = "";
		els.savedSel.appendChild(H("option", { value: "", text: names.length ? "Saved…" : "Saved (none)" }));
		names.forEach(function (n) { els.savedSel.appendChild(H("option", { value: n, text: n })); });
	}
	function doSave() {
		var name = prompt("Save pipeline as:", "my-pipeline");
		if (!name) { return; }
		var ok = lsSet("ml.pipeline.saved." + name, JSON.stringify(toGraphJson()));
		if (!ok) { toast("Could not save (storage unavailable)", 20, 20); return; }
		var names = savedNames(); if (names.indexOf(name) < 0) { names.push(name); lsSet(LS_INDEX, JSON.stringify(names)); }
		refreshSavedList();
		toast("Saved '" + name + "'", 20, 20);
	}
	function loadSaved(name) {
		var raw = lsGet("ml.pipeline.saved." + name);
		if (!raw) { return; }
		try { var warns = fromGraphJson(JSON.parse(raw)); syncSourceControls(); commit(); renderAll(); if (warns.length) { toast(warns[0], 20, 20); } }
		catch (e) { toast("Failed to load: " + e.message, 20, 20); }
	}
	function doDownload() {
		var blob = new Blob([JSON.stringify(toGraphJson(), null, 2)], { type: "application/json" });
		var url = URL.createObjectURL(blob);
		var a = document.createElement("a");
		a.href = url; a.download = "pipeline.json";
		document.body.appendChild(a); a.click(); document.body.removeChild(a);
		setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
	}
	function openJsonModal() {
		var backdrop = H("div", { class: "pe-modal-backdrop" });
		var modal = H("div", { class: "pe-modal" });
		modal.appendChild(H("div", { class: "pe-modal-head", text: "Open pipeline JSON" }));
		var ta = H("textarea", { class: "pe-textarea", placeholder: "Paste pipeline JSON here, or choose a file…" });
		modal.appendChild(ta);
		var err = H("div", { class: "pe-modal-err" });
		modal.appendChild(err);
		var actions = H("div", { class: "pe-modal-actions" });
		var file = H("input", { type: "file", accept: "application/json,.json", class: "pe-file" });
		file.addEventListener("change", function () {
			var f = file.files[0]; if (!f) { return; }
			var rd = new FileReader(); rd.onload = function () { ta.value = rd.result; }; rd.readAsText(f);
		});
		var loadBtn = H("button", { class: "pe-btn is-primary", text: "Load" });
		var cancelBtn = H("button", { class: "pe-btn", text: "Cancel" });
		loadBtn.addEventListener("click", function () {
			try {
				var obj = JSON.parse(ta.value);
				var warns = fromGraphJson(obj);
				syncSourceControls(); commit(); renderAll();
				closeModal();
				if (warns.length) { toast(warns.length + " import warning(s): " + warns[0], 20, 20); }
			} catch (e) { err.textContent = "Invalid: " + e.message; }
		});
		cancelBtn.addEventListener("click", closeModal);
		actions.appendChild(file); actions.appendChild(cancelBtn); actions.appendChild(loadBtn);
		modal.appendChild(actions);
		backdrop.appendChild(modal);
		backdrop.addEventListener("click", function (ev) { if (ev.target === backdrop) { closeModal(); } });
		root.appendChild(backdrop);
		els.modal = backdrop;
		ta.focus();
	}
	function closeModal() { if (els.modal && els.modal.parentNode) { els.modal.parentNode.removeChild(els.modal); } els.modal = null; }

	function syncSourceControls() {
		var modeSel = root.querySelector(".pe-toolbar .pe-select"); // first select is demo; find by option value
		var selects = root.querySelectorAll(".pe-toolbar select");
		Array.prototype.forEach.call(selects, function (s) {
			if (s.options.length && s.options[0].value === "single") { s.value = model.sourceMode; }
		});
		var num = root.querySelector(".pe-num");
		if (num) { num.value = String(model.sourceCount); num.disabled = model.sourceMode !== "multiple"; }
	}

	// ---------------------------------------------------------------- demo pipelines
	//
	// Note there is no "sink" node. Writing a node's output back to Loom is a per-node flag
	// (`syncToLoom`) and not a downstream node kind, so a hash node is legitimately terminal.
	// The demos used to end in a node of kind "loom", which is not in the descriptor snapshot:
	// every demo opened with a "(?)" node, an unknownKind error and a disabled Play button.
	var DEMOS = [
		{
			name: "Basic — hash & sync",
			data: {
				meta: { sourceMode: "single", sourceCount: 1 },
				nodes: [
					{ id: "src", type: "filesystem-source", position: { x: 40, y: 120 } },
					{ id: "h", type: "sha512", position: { x: 320, y: 120 } }
				],
				edges: [
					{ id: "e1", source: "src", sourcePort: "media", target: "h", targetPort: "media", branch: "ANY" }
				]
			}
		},
		{
			name: "Complex — faces (fan-out & gather)",
			data: {
				meta: { sourceMode: "multiple", sourceCount: 3 },
				nodes: [
					{ id: "src", type: "filesystem-source", position: { x: 30, y: 200 } },
					{ id: "mime", type: "filter", position: { x: 270, y: 200 } },
					{ id: "fd", type: "facedetect", position: { x: 520, y: 80 } },
					{ id: "fdesc", type: "facedescription", position: { x: 780, y: 80 } },
					{ id: "h", type: "sha512", position: { x: 520, y: 330 } }
				],
				edges: [
					{ id: "e1", source: "src", sourcePort: "media", target: "mime", targetPort: "media", branch: "ANY" },
					{ id: "e2", source: "mime", sourcePort: "other", target: "fd", targetPort: "image", branch: "ANY" },
					{ id: "e3", source: "fd", sourcePort: "detections", target: "fdesc", targetPort: "detections", branch: "ANY" },
					{ id: "e4", source: "mime", sourcePort: "other", target: "h", targetPort: "media", branch: "ANY" }
				]
			}
		},
		{
			name: "Use-case — transcribe & sentiment",
			data: {
				meta: { sourceMode: "multiple", sourceCount: 3 },
				nodes: [
					{ id: "src", type: "filesystem-source", position: { x: 30, y: 200 } },
					{ id: "mime", type: "filter", position: { x: 270, y: 200 } },
					{ id: "w", type: "whisper", position: { x: 520, y: 80 } },
					{ id: "sent", type: "sentiment", position: { x: 790, y: 80 } },
					{ id: "h", type: "md5", position: { x: 520, y: 340 } }
				],
				edges: [
					{ id: "e1", source: "src", sourcePort: "media", target: "mime", targetPort: "media", branch: "ANY" },
					{ id: "e2", source: "mime", sourcePort: "other", target: "w", targetPort: "audio", branch: "ANY" },
					{ id: "e3", source: "w", sourcePort: "transcript", target: "sent", targetPort: "text", branch: "ANY" },
					{ id: "e4", source: "mime", sourcePort: "other", target: "h", targetPort: "media", branch: "ANY" }
				]
			}
		}
	];
	function loadDemo(i) {
		sim.reset();
		try { fromGraphJson(JSON.parse(JSON.stringify(DEMOS[i].data))); } catch (e) { toast("Demo failed: " + e.message, 20, 20); return; }
		syncSourceControls();
		view = { tx: 30, ty: 20, scale: 1 };
		commit(); renderAll();
	}

	// ---------------------------------------------------------------- bootstrap
	function init(data) {
		(data.nodeDescriptors || []).forEach(function (d) { descriptorsByKind[d.kind] = withResolvedPorts(d); });
		(data.contentTypes || []).forEach(function (c) { contentTypesById[c.id] = c; });
		buildShell();
		loadDemo(0);
	}

	function fail(msg) {
		root.innerHTML = "";
		root.appendChild(H("div", { class: "pe-fatal", text: "Pipeline editor could not load: " + msg }));
	}

	var url = root.getAttribute("data-descriptors");
	if (!url) { fail("no descriptor source configured"); return; }
	fetch(url)
		.then(function (r) { if (!r.ok) { throw new Error("HTTP " + r.status); } return r.json(); })
		.then(function (data) { try { init(data); } catch (e) { fail(e.message); } })
		.catch(function (e) { fail(e.message); });
})();
