/*
 * nodeviz — renders the "node in a pipeline" diagram on the docs node pages.
 *
 * A page carries only a small JSON spec:
 *
 *   <div class="ml-nodeviz" data-nodeviz='{"kind":"ocr","applies":"Image","configs":[...]}'></div>
 *
 * and this script draws the SVG: typed inputs on the left, the node in the middle, typed outputs on
 * the right, plus a tab per alternative configuration. Keeping the geometry here (rather than in 19
 * hand-drawn SVGs) is what makes the pages consistent when the design changes.
 *
 * Loaded globally by config.toml, so it must no-op on pages without a diagram.
 */
(function () {
	var roots = document.querySelectorAll(".ml-nodeviz");
	var legends = document.querySelectorAll(".ml-nodeviz-legend");
	if (!roots.length && !legends.length) {
		return;
	}

	var NS = "http://www.w3.org/2000/svg";
	var MUTED = "#7f8b98";

	// Data-type palette. The type also picks the icon, so a reader learns "amber braces = JSON"
	// once and then recognises it on every node page.
	var TYPES = {
		image: { c: "#5ec98f", n: "image" },
		video: { c: "#4aa3ff", n: "video" },
		audio: { c: "#a48be0", n: "audio" },
		document: { c: "#8fa3b8", n: "document" },
		file: { c: "#8fa3b8", n: "file" },
		text: { c: "#4fd0e0", n: "text" },
		json: { c: "#e2a24e", n: "json" },
		number: { c: "#7fb4ff", n: "number" },
		boolean: { c: "#5ec98f", n: "boolean" },
		hash: { c: "#2fd4b6", n: "hash" },
		vector: { c: "#a48be0", n: "vector" },
		face: { c: "#e2a24e", n: "face" },
		bbox: { c: "#e2a24e", n: "bounding box" },
		timeframe: { c: "#4fd0e0", n: "timeframe" },
		segments: { c: "#4fd0e0", n: "segments" },
		path: { c: "#8fa3b8", n: "path" },
		flag: { c: "#8fa3b8", n: "flag" },
		action: { c: "#e2726e", n: "side effect" },
		branch: { c: "#5ec98f", n: "branch" }
	};

	function E(tag, attrs, kids) {
		var e = document.createElementNS(NS, tag);
		if (attrs) {
			for (var k in attrs) {
				e.setAttribute(k, attrs[k]);
			}
		}
		if (kids) {
			kids.forEach(function (c) { if (c) { e.appendChild(c); } });
		}
		return e;
	}

	function T(x, y, str, cls, anchor, fill) {
		var t = E("text", { x: x, y: y, "class": cls || "" });
		if (anchor) { t.setAttribute("text-anchor", anchor); }
		if (fill) { t.setAttribute("fill", fill); }
		t.textContent = str;
		return t;
	}

	/*
	 * Icons are drawn in a 24x24 box whose origin the caller translates into place.
	 * Glyph-based icons (braces, hash) use <text> deliberately — a hand-drawn "{" path reads worse
	 * at 24px than the font does.
	 */
	function icon(type, color) {
		var g = E("g", { "class": "nv-ico", stroke: color, fill: "none", "stroke-width": 1.6, "stroke-linecap": "round", "stroke-linejoin": "round" });
		function p(d, extra) {
			var a = { d: d };
			if (extra) { for (var k in extra) { a[k] = extra[k]; } }
			g.appendChild(E("path", a));
		}
		function glyph(s, size) {
			var t = T(12, 17, s, "nv-ico-glyph", "middle", color);
			t.setAttribute("stroke", "none");
			t.setAttribute("font-size", size || 15);
			g.appendChild(t);
		}
		switch (type) {
			case "image": // picture frame with a horizon and a sun
				p("M3 5h18v14H3z");
				g.appendChild(E("circle", { cx: 8.5, cy: 10, r: 1.7 }));
				p("M3.5 17l5-5 3.5 3.5L16 12l4.5 5");
				break;
			case "video": // film strip
				p("M3 5h18v14H3z");
				p("M7 5v14M17 5v14M3 9.7h4M3 14.3h4M17 9.7h4M17 14.3h4");
				break;
			case "audio": // waveform
				p("M3 12h2M7 7.5v9M11 4.5v15M15 8.5v7M19 10.5v3");
				break;
			case "document":
			case "file": // page with a folded corner
				p("M6 3h8l5 5v13H6z");
				p("M14 3v5h5");
				p("M9 13h7M9 16.5h7");
				break;
			case "text": // paragraph
				p("M4 6h16M4 10h16M4 14h16M4 18h9");
				break;
			case "json":
				glyph("{ }", 13);
				break;
			case "number":
				glyph("42", 12);
				break;
			case "hash":
				glyph("#", 16);
				break;
			case "boolean": // toggle
				p("M4 8h10a4 4 0 0 1 0 8H4a4 4 0 0 1 0-8z");
				g.appendChild(E("circle", { cx: 14, cy: 12, r: 2.4, fill: color, stroke: "none" }));
				break;
			case "vector": // embedding — a bracketed row of magnitudes
				p("M4 6.5V17.5M4 6.5h2M4 17.5h2M20 6.5V17.5M20 6.5h-2M20 17.5h-2");
				p("M8.5 14.5v-3M11.5 16v-6M14.5 13v-1.5M17.5 15.5v-5");
				break;
			case "face": // face outline with eyes and a smile
				g.appendChild(E("circle", { cx: 12, cy: 12, r: 8.4 }));
				g.appendChild(E("circle", { cx: 9.2, cy: 10.2, r: 0.9, fill: color, stroke: "none" }));
				g.appendChild(E("circle", { cx: 14.8, cy: 10.2, r: 0.9, fill: color, stroke: "none" }));
				p("M8.6 14.4a4.2 4.2 0 0 0 6.8 0");
				break;
			case "bbox": // dashed detection box with solid corner ticks around a subject
				p("M3.5 4.5h17v15h-17z", { "stroke-dasharray": "2.5 2.6", "stroke-width": 1.2, opacity: 0.75 });
				p("M3.5 8V4.5H7M17 4.5h3.5V8M20.5 16v3.5H17M7 19.5H3.5V16", { "stroke-width": 1.9 });
				g.appendChild(E("circle", { cx: 12, cy: 10.2, r: 2.1 }));
				p("M8.4 16.4a3.9 3.9 0 0 1 7.2 0");
				break;
			case "timeframe": // timeline with a highlighted span between two markers
				p("M2.5 12h19", { opacity: 0.55 });
				p("M7 6.5v11M17 6.5v11");
				g.appendChild(E("rect", { x: 7, y: 9.6, width: 10, height: 4.8, rx: 1.4, fill: color, stroke: "none", "fill-opacity": 0.35 }));
				break;
			case "segments": // consecutive scene blocks
				g.appendChild(E("rect", { x: 2.5, y: 8, width: 5, height: 8, rx: 1.2 }));
				g.appendChild(E("rect", { x: 9.5, y: 8, width: 5, height: 8, rx: 1.2, fill: color, "fill-opacity": 0.3 }));
				g.appendChild(E("rect", { x: 16.5, y: 8, width: 5, height: 8, rx: 1.2 }));
				break;
			case "path": // folder
				p("M3 6.5h6l2 2.5h10V19H3z");
				break;
			case "flag":
				p("M6 3.5v17");
				p("M6 5h11l-2.2 3.4L17 12H6z");
				break;
			case "action": // moved / written elsewhere
				p("M4 12h12M12 7.5l4.5 4.5L12 16.5");
				p("M18.5 5.5h2v13h-2", { opacity: 0.7 });
				break;
			case "branch": // pass / reject fork
				p("M3.5 12h6");
				p("M9.5 12l5-5h6M9.5 12l5 5h6");
				break;
			default:
				g.appendChild(E("circle", { cx: 12, cy: 12, r: 6 }));
		}
		return g;
	}

	function bez(x1, y1, x2, y2) {
		var mx = (x1 + x2) / 2;
		return "M" + x1 + "," + y1 + " C" + mx + "," + y1 + " " + mx + "," + y2 + " " + x2 + "," + y2;
	}

	// Geometry. Everything scales from these, so the diagram stays balanced when rows are added.
	var W = 900, COL = 268, ROW = 50, NODE_X = 342, NODE_W = 216, GAP_TOP = 46;

	function build(spec, cfg) {
		var ins = cfg.inputs || [], outs = cfg.outputs || [];
		var rows = Math.max(ins.length, outs.length, 1);
		// A one-port node still needs a box tall enough to carry the badge, the kind and the
		// "applies to" line without crowding, hence the floor on the row block.
		var blockH = Math.max(rows * ROW, 104);
		var h = GAP_TOP + blockH + 34;
		var nodeH = Math.min(blockH - 8, 128);
		var nodeY = GAP_TOP + (blockH - nodeH) / 2;
		var svg = E("svg", { viewBox: "0 0 " + W + " " + h, role: "img" });
		var uid = "nv" + Math.abs(hash(spec.kind + (cfg.name || "") + h));
		var defs = E("defs");
		var marker = E("marker", { id: uid + "-a", viewBox: "0 0 10 10", refX: 8, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto-start-reverse" });
		marker.appendChild(E("path", { d: "M0,0 L10,5 L0,10 z", fill: "#5b6771" }));
		defs.appendChild(marker);
		// The processing sweep fades out at its leading edge, so a half-filled node reads as work in
		// progress rather than as a box drawn in two colours.
		var grad = E("linearGradient", { id: uid + "-g", x1: "0", x2: "1", y1: "0", y2: "0" });
		grad.appendChild(E("stop", { offset: "0", "stop-color": "#57cbcc", "stop-opacity": ".22" }));
		grad.appendChild(E("stop", { offset: "0.72", "stop-color": "#57cbcc", "stop-opacity": ".2" }));
		grad.appendChild(E("stop", { offset: "1", "stop-color": "#57cbcc", "stop-opacity": "0" }));
		defs.appendChild(grad);
		svg.appendChild(defs);
		var desc = (cfg.name ? cfg.name + ": " : "") +
			ins.map(function (i) { return i.l; }).join(", ") + " → " + spec.kind + " → " +
			outs.map(function (o) { return o.l; }).join(", ");
		svg.appendChild(E("title", {}, [])).textContent = desc;

		var edges = [];
		function rowY(i, n) { return GAP_TOP + (blockH * (i + 0.5)) / n; }
		function portY(i, n) { return nodeY + (nodeH * (i + 1)) / (n + 1); }

		// ---- edges first so the boxes paint over them ----
		ins.forEach(function (p, i) {
			var e = E("path", { d: bez(COL + 6, rowY(i, ins.length), NODE_X - 7, portY(i, ins.length)), "class": "nv-edge", "marker-end": "url(#" + uid + "-a)" });
			var f = E("path", { d: e.getAttribute("d"), "class": "nv-flow" });
			svg.appendChild(e); svg.appendChild(f);
			edges.push({ el: f, path: e, side: "in", optional: !!p.opt });
			if (p.opt) { e.setAttribute("stroke-dasharray", "4 5"); }
		});
		outs.forEach(function (p, i) {
			var e = E("path", { d: bez(NODE_X + NODE_W, portY(i, outs.length), W - COL - 13, rowY(i, outs.length)), "class": "nv-edge", "marker-end": "url(#" + uid + "-a)" });
			var f = E("path", { d: e.getAttribute("d"), "class": "nv-flow" });
			svg.appendChild(e); svg.appendChild(f);
			edges.push({ el: f, path: e, side: "out", optional: !!p.opt });
			if (p.opt) { e.setAttribute("stroke-dasharray", "4 5"); }
		});

		// ---- port rows ----
		function portRow(p, i, n, right) {
			var ty = TYPES[p.t] || { c: MUTED, n: p.t || "" };
			var cy = rowY(i, n);
			var g = E("g", { "class": "nv-port" + (p.opt ? " is-opt" : "") });
			var x = right ? W - COL : 0;
			g.appendChild(E("rect", { x: x + (right ? 0 : 4), y: cy - 20, width: COL - 4, height: 40, rx: 8, "class": "nv-port-bg" }));
			var ig = icon(p.t, ty.c);
			ig.setAttribute("transform", "translate(" + (x + (right ? 12 : 16)) + "," + (cy - 12) + ")");
			g.appendChild(ig);
			var tx = x + (right ? 46 : 50);
			g.appendChild(T(tx, cy - 1, p.l, "nv-port-label"));
			g.appendChild(T(tx, cy + 14, (p.d || ty.n) + (p.opt ? " · optional" : ""), "nv-port-type"));
			svg.appendChild(g);
		}
		ins.forEach(function (p, i) { portRow(p, i, ins.length, false); });
		outs.forEach(function (p, i) { portRow(p, i, outs.length, true); });

		// ---- the node itself ----
		var node = E("g", { "class": "nv-node" });
		node.appendChild(E("rect", { x: NODE_X, y: nodeY, width: NODE_W, height: nodeH, rx: 12, "class": "nv-node-box" }));
		var clipId = uid + "-c";
		var clip = E("clipPath", { id: clipId });
		clip.appendChild(E("rect", { x: NODE_X, y: nodeY, width: NODE_W, height: nodeH, rx: 12 }));
		node.appendChild(clip);
		var fill = E("rect", { x: NODE_X, y: nodeY, width: 0, height: nodeH, "class": "nv-node-fill", fill: "url(#" + uid + "-g)", "clip-path": "url(#" + clipId + ")" });
		node.appendChild(fill);
		var cx = NODE_X + NODE_W / 2;
		node.appendChild(T(cx, nodeY + nodeH / 2 - 6, spec.kind, "nv-node-kind", "middle"));
		node.appendChild(T(cx, nodeY + nodeH / 2 + 14, spec.applies, "nv-node-applies", "middle"));
		if (spec.badge) {
			var bw = spec.badge.length * 6.4 + 22;
			node.appendChild(E("rect", { x: cx - bw / 2, y: nodeY - 13, width: bw, height: 24, rx: 12, "class": "nv-badge-box" }));
			node.appendChild(T(cx, nodeY + 3, spec.badge, "nv-badge", "middle"));
		}
		if (cfg.note) {
			node.appendChild(T(cx, nodeY + nodeH + 22, cfg.note, "nv-node-note", "middle"));
		}
		svg.appendChild(node);

		// port dots last — they sit on top of both edge and box
		ins.forEach(function (p, i) { svg.appendChild(E("circle", { cx: NODE_X, cy: portY(i, ins.length), r: 4, "class": "nv-dot" })); });
		outs.forEach(function (p, i) { svg.appendChild(E("circle", { cx: NODE_X + NODE_W, cy: portY(i, outs.length), r: 4, "class": "nv-dot" })); });

		return { svg: svg, edges: edges, fill: fill, nodeW: NODE_W };
	}

	function hash(s) {
		var h = 0;
		for (var i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
		return h;
	}

	var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
	var live = [];

	function mount(root) {
		var spec;
		try {
			spec = JSON.parse(root.getAttribute("data-nodeviz"));
		} catch (err) {
			return; // a malformed spec must not take the page down
		}
		var configs = spec.configs && spec.configs.length ? spec.configs : [{ inputs: spec.inputs, outputs: spec.outputs }];
		var stage = document.createElement("div");
		stage.className = "nv-stage";
		var tabs = null;

		function draw(idx) {
			var built = build(spec, configs[idx]);
			stage.innerHTML = "";
			stage.appendChild(built.svg);
			if (spec.persist) {
				var foot = document.createElement("div");
				foot.className = "nv-foot";
				foot.textContent = "persisted to " + spec.persist;
				stage.appendChild(foot);
			}
			var inst = { built: built, t0: null };
			live = live.filter(function (l) { return l.root !== root; });
			live.push({ root: root, inst: inst });
			if (reduce) {
				built.fill.setAttribute("width", built.nodeW);
				built.edges.forEach(function (e) { e.el.classList.add("is-on"); });
			}
		}

		if (configs.length > 1) {
			tabs = document.createElement("div");
			tabs.className = "nv-tabs";
			configs.forEach(function (c, i) {
				var b = document.createElement("button");
				b.type = "button";
				b.className = "nv-tab" + (i === 0 ? " is-active" : "");
				b.textContent = c.name || "config " + (i + 1);
				b.addEventListener("click", function () {
					Array.prototype.forEach.call(tabs.children, function (n) { n.classList.remove("is-active"); });
					b.classList.add("is-active");
					draw(i);
				});
				tabs.appendChild(b);
			});
			root.appendChild(tabs);
		}
		root.appendChild(stage);
		draw(0);
	}

	Array.prototype.forEach.call(roots, mount);

	// The legend teaches the icon vocabulary once, on the nodes index, instead of repeating a key
	// on all 19 node pages.
	var LEGEND = ["image", "video", "audio", "document", "text", "json", "number", "boolean", "hash",
		"vector", "face", "bbox", "timeframe", "segments", "path", "flag", "branch", "action"];
	Array.prototype.forEach.call(legends, function (root) {
		LEGEND.forEach(function (t) {
			var ty = TYPES[t];
			var cell = document.createElement("div");
			cell.className = "nv-legend-item";
			var svg = E("svg", { viewBox: "0 0 24 24", width: 24, height: 24, "aria-hidden": "true" });
			svg.appendChild(icon(t, ty.c));
			cell.appendChild(svg);
			var label = document.createElement("span");
			label.textContent = ty.n;
			cell.appendChild(label);
			root.appendChild(cell);
		});
	});

	if (reduce) {
		return;
	}

	/*
	 * One rAF loop drives every diagram on the page: input edges flow, the node fills, then the
	 * output edges flow. Diagrams outside the viewport are skipped so an idle page costs nothing.
	 */
	var CYCLE = 4200, IN_END = 1200, WORK_END = 2900, OUT_END = 3900;
	function visible(el) {
		var r = el.getBoundingClientRect();
		return r.bottom > 0 && r.top < (window.innerHeight || document.documentElement.clientHeight);
	}
	function frame(ts) {
		live.forEach(function (l) {
			if (!visible(l.root)) { return; }
			if (l.inst.t0 === null) { l.inst.t0 = ts; }
			var t = (ts - l.inst.t0) % CYCLE;
			var b = l.inst.built;
			var p = t < IN_END ? 0 : t < WORK_END ? (t - IN_END) / (WORK_END - IN_END) : t < OUT_END ? 1 : 1;
			b.fill.setAttribute("width", (b.nodeW * Math.min(1, p)).toFixed(1));
			b.edges.forEach(function (e) {
				var on = e.side === "in" ? t < IN_END : t >= WORK_END && t < OUT_END;
				if (e._on !== on) { e._on = on; e.el.classList.toggle("is-on", on); }
			});
		});
		requestAnimationFrame(frame);
	}
	requestAnimationFrame(frame);
})();
