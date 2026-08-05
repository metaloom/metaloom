/*
 * detectionplayer — plays a clip with a detection node's own boxes painted in.
 *
 * Why this exists
 * ---------------
 * A detector that runs over a video reports one detection *per sampled frame*, and the debugging
 * view can only show one still. Drawing every detection on that one still is how two people become
 * ten stacked boxes, and no caption really fixes it: the reader is looking at a picture that says
 * the detector found five faces where there are two. The boxes are not wrong, they are *from
 * different moments*, and the only honest way to show a time series is over time.
 *
 * So: the clip plays, and each box appears on the frame it was measured on and fades out again.
 * The fade is not decoration — it is the sampling interval made visible. The node looks at every
 * n-th frame, so between two samples there is nothing new to draw, and a box that lingers and
 * dissolves says "this is the last thing that was seen here" rather than pretending to track.
 *
 * Contract
 * --------
 *   <div class="ml-detplayer" data-track-url="…json" data-video-url="…mp4" data-recent="10">
 *     <video controls muted loop playsinline preload="metadata" poster="…" src="…mp4"></video>
 *   </div>
 *
 * The <video> is real markup, not built here. With JavaScript off, or if the track fails to load,
 * the reader still gets a playable clip — the page never depends on this file to have content.
 *
 * The track is the generator's output (integration-test DetectionPlayerFixtureGenerator) and its
 * `detections` are the node's own encoded elements, untouched. Nothing in here knows what a face
 * is: it reads `label`, `bbox`, `frame` and the dimensions the box was measured against, which is
 * the shape *every* `detection/*` port emits. An object detector's track plays through the same
 * code with no changes.
 *
 * Loaded globally by config.toml, so it must no-op on pages without a player.
 */
(function () {
	"use strict";

	var roots = document.querySelectorAll(".ml-detplayer");
	if (!roots.length) {
		return;
	}

	var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

	/** Seconds a box stays fully drawn, and the total it stays visible at all. */
	var SOLID_SECONDS = 0.5;
	var HOLD_SECONDS = 1.6;

	function el(tag, cls, attrs) {
		var e = document.createElement(tag);
		if (cls) { e.className = cls; }
		if (attrs) {
			for (var k in attrs) { e.setAttribute(k, attrs[k]); }
		}
		return e;
	}

	/** Resolve a track-relative file name against the track's own URL, so page bundles just work. */
	function resolve(base, name) {
		try {
			return new URL(name, new URL(base, window.location.href)).href;
		} catch (e) {
			return name;
		}
	}

	function mount(root) {
		var trackUrl = root.getAttribute("data-track-url");
		var video = root.querySelector("video");
		if (!trackUrl || !video) {
			return;
		}
		fetch(trackUrl)
			.then(function (r) { return r.ok ? r.json() : Promise.reject(new Error(r.status)); })
			.then(function (track) { build(root, video, track, trackUrl); })
			.catch(function () {
				// A missing or malformed track leaves the plain video in place. Better a clip with no
				// boxes than an error where a figure should be.
			});
	}

	function build(root, video, track, trackUrl) {
		var info = track.video || {};
		var fps = Number(info.fps) || 25;
		var offset = Number(info.frameOffset) || 0;
		var dets = (track.detections || []).filter(function (d) {
			return d && d.bbox && d.imageWidth > 0 && d.imageHeight > 0;
		});
		if (!dets.length) {
			return;
		}
		// Normalise once, exactly the way the product's own overlay does: against the dimensions the
		// element carries, never against whatever the video is being displayed at.
		dets = dets.map(function (d, i) {
			return {
				i: typeof d.index === "number" ? d.index : i,
				frame: Number(d.frame) || 0,
				label: d.label || d.type || "detection",
				score: typeof d.confidence === "number" ? d.confidence : null,
				x: d.bbox.x / d.imageWidth,
				y: d.bbox.y / d.imageHeight,
				w: d.bbox.w / d.imageWidth,
				h: d.bbox.h / d.imageHeight
			};
		}).sort(function (a, b) { return a.frame - b.frame || a.i - b.i; });

		var hold = Math.max(1, Math.round(fps * HOLD_SECONDS));
		var solid = Math.max(1, Math.round(fps * SOLID_SECONDS));
		var recentMax = parseInt(root.getAttribute("data-recent"), 10) || 10;

		// ---- structure ----
		var stage = el("div", "dp-stage");
		video.parentNode.insertBefore(stage, video);
		stage.appendChild(video);
		var canvas = el("canvas", "dp-overlay", { "aria-hidden": "true" });
		stage.appendChild(canvas);

		var bar = el("div", "dp-bar");
		var counter = el("span", "dp-frame");
		var legend = el("span", "dp-legend");
		legend.textContent = (track.node || "detector") + " · every "
			+ ((track.options && track.options.videoChopRate) || "n") + "th frame sampled";
		bar.appendChild(counter);
		bar.appendChild(legend);
		root.appendChild(bar);

		var strip = el("div", "dp-strip", { "aria-label": "Most recent detections" });
		root.appendChild(strip);

		// ---- the recent strip ----
		// One button per detection, built once and reordered as the clip plays. Rebuilding the list
		// every frame would restart the CSS transition on every surviving tile, so nothing would ever
		// finish animating in.
		var sprite = track.sprite || {};
		var spriteUrl = sprite.file ? resolve(trackUrl, sprite.file) : null;
		var tiles = {};
		dets.forEach(function (d) {
			var b = el("button", "dp-tile", { type: "button" });
			b.setAttribute("aria-label", "Jump to " + d.label + " found on frame " + d.frame);
			if (spriteUrl && sprite.tile) {
				var img = el("span", "dp-tile-img");
				img.style.backgroundImage = "url(" + spriteUrl + ")";
				// The strip is one image: tile n is detection n, so the offset is the element's own
				// position and no index has to be kept in step.
				img.style.backgroundSize = (sprite.count * 100) + "% 100%";
				img.style.backgroundPosition = (sprite.count > 1 ? (d.i * 100) / (sprite.count - 1) : 0) + "% 0";
				b.appendChild(img);
			}
			var cap = el("span", "dp-tile-cap");
			cap.textContent = "frame " + d.frame;
			b.appendChild(cap);
			b.addEventListener("click", function () {
				// Land a little before the detection so the reader sees it arrive rather than
				// arriving mid-fade.
				video.currentTime = Math.max(0, (d.frame - offset - solid) / fps);
				if (video.paused) { video.play().catch(function () { }); }
			});
			tiles[d.i] = b;
		});

		var shownOrder = "";
		function updateStrip(frame) {
			var recent = dets.filter(function (d) { return d.frame <= frame; }).slice(-recentMax).reverse();
			var key = recent.map(function (d) { return d.i; }).join(",");
			if (key === shownOrder) {
				return;
			}
			shownOrder = key;

			var keep = {}, entering = [];
			// Re-append in order rather than rebuilding: appendChild on an element already in the
			// strip moves it without recreating it, so a tile that survives keeps its identity and
			// only the ones that genuinely entered play the entry animation.
			recent.forEach(function (d) {
				keep[d.i] = true;
				var tile = tiles[d.i];
				if (!tile.parentNode) {
					// Start state applied *before* insertion, then dropped on the next frame so the
					// transition has somewhere to move from. Setting it afterwards would slide the
					// newest tile out instead of in.
					tile.classList.add("dp-enter");
					entering.push(tile);
				}
				strip.appendChild(tile);
			});
			Object.keys(tiles).forEach(function (k) {
				if (!keep[k] && tiles[k].parentNode) {
					tiles[k].parentNode.removeChild(tiles[k]);
				}
			});
			if (entering.length) {
				requestAnimationFrame(function () {
					entering.forEach(function (tile) { tile.classList.remove("dp-enter"); });
				});
			}
		}

		// ---- the overlay ----
		var ctx = canvas.getContext("2d");
		function sizeCanvas() {
			var r = video.getBoundingClientRect();
			if (!r.width || !r.height) { return false; }
			var dpr = window.devicePixelRatio || 1;
			canvas.width = Math.round(r.width * dpr);
			canvas.height = Math.round(r.height * dpr);
			canvas.style.width = r.width + "px";
			canvas.style.height = r.height + "px";
			ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
			return true;
		}

		function draw(frame) {
			var w = canvas.width / (window.devicePixelRatio || 1);
			var h = canvas.height / (window.devicePixelRatio || 1);
			ctx.clearRect(0, 0, w, h);
			ctx.lineWidth = 2;
			ctx.font = "600 12px 'Quattrocento Sans', sans-serif";
			ctx.textBaseline = "alphabetic";

			for (var i = 0; i < dets.length; i++) {
				var d = dets[i];
				var age = frame - d.frame;
				if (age < 0 || age > hold) {
					continue;
				}
				// Full strength while the sample is fresh, then dissolving. What the dissolve says is
				// "nothing new has been reported here since" — the detector is not tracking anything,
				// it is sampling, and the gaps are the sampling interval.
				var alpha = reduce ? 1 : (age <= solid ? 1 : 1 - (age - solid) / (hold - solid));
				var bx = d.x * w, by = d.y * h, bw = d.w * w, bh = d.h * h;

				ctx.globalAlpha = alpha;
				ctx.strokeStyle = "#57cbcc";
				ctx.shadowColor = "rgba(0,0,0,.55)";
				ctx.shadowBlur = 0;
				ctx.shadowOffsetX = 1;
				ctx.shadowOffsetY = 1;
				ctx.strokeRect(bx, by, bw, bh);
				ctx.shadowOffsetX = 0;
				ctx.shadowOffsetY = 0;

				// Only the fresh box is labelled. Two samples of the same face sit almost on top of
				// each other, so labelling both puts one caption over the other — and the caption is
				// exactly the thing that should distinguish "this is the current answer" from "this is
				// the last answer, going stale".
				if (age > solid) {
					continue;
				}
				var text = d.label + (d.score !== null ? " " + d.score.toFixed(2) : "");
				var tw = ctx.measureText(text).width;
				var ty = by - 4 < 12 ? by + bh + 15 : by - 4;
				ctx.fillStyle = "#57cbcc";
				ctx.fillRect(bx - 1, ty - 12, tw + 8, 16);
				ctx.fillStyle = "#08181a";
				ctx.fillText(text, bx + 3, ty);
			}
			ctx.globalAlpha = 1;
		}

		var lastFrame = -1;
		function tick() {
			// Stateless per frame: everything drawn is a function of the current time alone, so
			// scrubbing backwards rebuilds the picture instead of leaving stale boxes behind.
			var frame = offset + Math.round(video.currentTime * fps);
			if (frame !== lastFrame) {
				lastFrame = frame;
				if (sizeCanvas()) {
					draw(frame);
				}
				counter.textContent = "frame " + frame;
				updateStrip(frame);
			}
			requestAnimationFrame(tick);
		}

		function reset() {
			lastFrame = -1;
			shownOrder = "";
			while (strip.firstChild) { strip.removeChild(strip.firstChild); }
		}
		video.addEventListener("seeking", reset);
		video.addEventListener("loadedmetadata", function () { lastFrame = -1; });
		window.addEventListener("resize", function () { lastFrame = -1; });

		root.classList.add("is-live");
		requestAnimationFrame(tick);
	}

	Array.prototype.forEach.call(roots, mount);
})();
