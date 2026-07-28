/*
 * Scroll reveal for the design-led pages (/, /tour/, /studio/, /features/).
 *
 * Contract — three hooks, nothing page-specific:
 *
 *   [data-reveal-scope]  a container to scan (the page root)
 *   .reveal              an element to reveal; optional data-reveal-delay="<n>"
 *                        staggers it by n × 90 ms
 *   [data-count-up]      a number that counts up from zero when it scrolls in
 *
 * Revealing an element adds .is-visible to it, which is also what starts the
 * illustrations — their keyframes are all written as `.is-visible .foo`.
 *
 * The hidden start state lives behind the .reveal-js class that an inline snippet
 * in the layout sets during parse. That snippet also drops the class again after a
 * moment if this script never runs, so a blocked or failed script degrades to "no
 * animation", never to "no content".
 */
(function () {
	'use strict';

	var scopes = Array.prototype.slice.call(document.querySelectorAll('[data-reveal-scope]'));
	if (!scopes.length) return;

	window.__revealReady = true;

	var root = document.documentElement;
	var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

	var items = [];
	var counters = [];
	scopes.forEach(function (scope) {
		items = items.concat(Array.prototype.slice.call(scope.querySelectorAll('.reveal')));
		counters = counters.concat(Array.prototype.slice.call(scope.querySelectorAll('[data-count-up]')));
	});

	if (reduceMotion || !('IntersectionObserver' in window)) {
		root.classList.remove('reveal-js');
		items.forEach(function (el) { el.classList.add('is-visible'); });
		return;
	}

	// Stagger neighbouring items (grid cells, steps) via their data-reveal-delay index.
	items.forEach(function (el) {
		var step = parseInt(el.getAttribute('data-reveal-delay') || '0', 10);
		if (step > 0) el.style.transitionDelay = (step * 90) + 'ms';
	});

	var observer = new IntersectionObserver(function (entries) {
		entries.forEach(function (entry) {
			if (!entry.isIntersecting) return;
			entry.target.classList.add('is-visible');
			observer.unobserve(entry.target);
		});
	}, { rootMargin: '0px 0px -10% 0px', threshold: 0.15 });

	items.forEach(function (el) { observer.observe(el); });

	/* ---- counters: count up once, when they are on screen ---- */
	if (!counters.length) return;

	function countUp(el) {
		var target = parseInt(el.textContent.replace(/\D/g, ''), 10);
		var suffix = el.textContent.replace(/[\d\s]/g, '');
		if (isNaN(target) || target === 0) return;
		var started = null;
		var duration = 900;
		function frame(now) {
			if (started === null) started = now;
			var progress = Math.min((now - started) / duration, 1);
			// ease-out so the last digits settle instead of snapping
			var eased = 1 - Math.pow(1 - progress, 3);
			el.textContent = Math.round(target * eased) + suffix;
			if (progress < 1) window.requestAnimationFrame(frame);
		}
		window.requestAnimationFrame(frame);
	}

	var counterObserver = new IntersectionObserver(function (entries) {
		entries.forEach(function (entry) {
			if (!entry.isIntersecting) return;
			countUp(entry.target);
			counterObserver.unobserve(entry.target);
		});
	}, { threshold: 0.6 });

	counters.forEach(function (el) { counterObserver.observe(el); });
})();
