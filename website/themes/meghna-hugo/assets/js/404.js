/*
 * The animated background on /404.html — a drifting node graph, which is what MetaLoom's own mark
 * and its whole vocabulary are made of: nodes, the edges between them, and something travelling
 * along an edge.
 *
 * Hand-written against a 2D canvas rather than pulled from a graph library. The site vendors no
 * bundler and every plugin it ships is paid for by more than one page; a general force-directed
 * layout engine would be ~250 KB to draw thirty dots on the one page a reader hopes never to see.
 * What is here is the part such a library would be used for — repulsion-free drift, proximity
 * edges, a pulse travelling one edge at a time — in about a hundred lines.
 *
 * Rules it follows, all of them the site's rather than this file's:
 *
 *   * It is decoration. The heading, the note and the three links are ordinary markup and are
 *     unaffected by anything below; blocking this script leaves the CSS washes and a still page.
 *   * `prefers-reduced-motion` paints ONE frame and stops. Not a blank canvas — the same picture,
 *     holding still.
 *   * It stops when the tab is hidden and when the page is scrolled past, because a 404 left open
 *     in a background tab should not be a fan spinning up.
 */
(function () {
	'use strict';

	var canvas = document.querySelector('[data-nf-graph]');
	if (!canvas || !canvas.getContext) return;

	var ctx = canvas.getContext('2d');
	var still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

	var TEAL = '87, 203, 204';
	var BLUE = '96, 148, 224';
	/* Beyond this distance two nodes are not related and no line is drawn. It is in CSS pixels
	   and deliberately generous: a graph that is mostly unconnected dots reads as static. */
	var LINK = 168;

	var nodes = [];
	var pulses = [];
	var w = 0, h = 0, dpr = 1;
	var running = false;
	var frame = null;

	function size() {
		var rect = canvas.getBoundingClientRect();
		dpr = Math.min(window.devicePixelRatio || 1, 2);
		w = rect.width;
		h = rect.height;
		canvas.width = Math.round(w * dpr);
		canvas.height = Math.round(h * dpr);
		ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
	}

	function seed() {
		/* Enough nodes to look like a graph, few enough that every line is legible. Scaled by
		   area so a phone does not get a laptop's worth of dots in a quarter of the space. */
		var count = Math.max(14, Math.min(40, Math.round((w * h) / 26000)));
		nodes = [];
		for (var i = 0; i < count; i++) {
			nodes.push({
				x: Math.random() * w,
				y: Math.random() * h,
				/* Slow. The reader is meant to notice this after they have read the page. */
				vx: (Math.random() - 0.5) * 0.22,
				vy: (Math.random() - 0.5) * 0.22,
				r: 1.6 + Math.random() * 2.4,
				warm: Math.random() < 0.22   /* a few blue ones among the teal */
			});
		}
		pulses = [];
	}

	/* One travelling pulse at a time per few seconds: the streaks are punctuation, not weather. */
	function spawnPulse() {
		if (nodes.length < 2 || pulses.length > 2) return;
		var a = nodes[Math.floor(Math.random() * nodes.length)];
		var near = [];
		for (var i = 0; i < nodes.length; i++) {
			var b = nodes[i];
			if (b === a) continue;
			if (Math.hypot(b.x - a.x, b.y - a.y) < LINK) near.push(b);
		}
		if (!near.length) return;
		pulses.push({ a: a, b: near[Math.floor(Math.random() * near.length)], t: 0 });
	}

	function step() {
		for (var i = 0; i < nodes.length; i++) {
			var n = nodes[i];
			n.x += n.vx;
			n.y += n.vy;
			/* Wrap rather than bounce: a bounce puts every node on the edges eventually, which is
			   where the text is. */
			if (n.x < -20) n.x = w + 20; else if (n.x > w + 20) n.x = -20;
			if (n.y < -20) n.y = h + 20; else if (n.y > h + 20) n.y = -20;
		}
		for (var p = pulses.length - 1; p >= 0; p--) {
			pulses[p].t += 0.012;
			if (pulses[p].t >= 1) pulses.splice(p, 1);
		}
	}

	function draw() {
		ctx.clearRect(0, 0, w, h);

		// edges
		for (var i = 0; i < nodes.length; i++) {
			for (var j = i + 1; j < nodes.length; j++) {
				var a = nodes[i], b = nodes[j];
				var d = Math.hypot(b.x - a.x, b.y - a.y);
				if (d > LINK) continue;
				var strength = 1 - d / LINK;
				ctx.strokeStyle = 'rgba(' + TEAL + ',' + (strength * 0.3).toFixed(3) + ')';
				ctx.lineWidth = 1;
				ctx.beginPath();
				ctx.moveTo(a.x, a.y);
				ctx.lineTo(b.x, b.y);
				ctx.stroke();
			}
		}

		// the streaks: a short bright segment sliding from one node to the next
		for (var p = 0; p < pulses.length; p++) {
			var pu = pulses[p];
			var t = pu.t;
			var tail = Math.max(0, t - 0.28);
			var x1 = pu.a.x + (pu.b.x - pu.a.x) * tail;
			var y1 = pu.a.y + (pu.b.y - pu.a.y) * tail;
			var x2 = pu.a.x + (pu.b.x - pu.a.x) * t;
			var y2 = pu.a.y + (pu.b.y - pu.a.y) * t;
			var grad = ctx.createLinearGradient(x1, y1, x2, y2);
			grad.addColorStop(0, 'rgba(' + TEAL + ',0)');
			grad.addColorStop(1, 'rgba(126, 255, 244,' + (0.85 * (1 - Math.abs(0.5 - t) * 1.2)).toFixed(3) + ')');
			ctx.strokeStyle = grad;
			ctx.lineWidth = 1.8;
			ctx.beginPath();
			ctx.moveTo(x1, y1);
			ctx.lineTo(x2, y2);
			ctx.stroke();
		}

		// nodes, each with a soft halo — the glow is the only thing here that is not a hairline
		for (var k = 0; k < nodes.length; k++) {
			var n = nodes[k];
			var colour = n.warm ? BLUE : TEAL;
			var halo = ctx.createRadialGradient(n.x, n.y, 0, n.x, n.y, n.r * 6);
			halo.addColorStop(0, 'rgba(' + colour + ',0.34)');
			halo.addColorStop(1, 'rgba(' + colour + ',0)');
			ctx.fillStyle = halo;
			ctx.beginPath();
			ctx.arc(n.x, n.y, n.r * 6, 0, Math.PI * 2);
			ctx.fill();

			ctx.fillStyle = 'rgba(' + colour + ',0.9)';
			ctx.beginPath();
			ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2);
			ctx.fill();
		}
	}

	function loop() {
		step();
		draw();
		frame = window.requestAnimationFrame(loop);
	}

	function start() {
		if (running || still) return;
		running = true;
		loop();
	}

	function stop() {
		running = false;
		if (frame) window.cancelAnimationFrame(frame);
		frame = null;
	}

	function reset() {
		size();
		seed();
		draw();
	}

	reset();
	if (still) return;   // one frame, drawn, done

	start();
	window.setInterval(function () { if (running) spawnPulse(); }, 1400);

	var resizeTimer = null;
	window.addEventListener('resize', function () {
		window.clearTimeout(resizeTimer);
		resizeTimer = window.setTimeout(reset, 180);
	});

	document.addEventListener('visibilitychange', function () {
		if (document.hidden) stop(); else start();
	});

	/* The canvas is the height of the viewport at the top of the page; once it is scrolled away
	   there is nothing to animate. */
	if ('IntersectionObserver' in window) {
		new IntersectionObserver(function (entries) {
			if (entries[0].isIntersecting) start(); else stop();
		}).observe(canvas);
	}
})();
