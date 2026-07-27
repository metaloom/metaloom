/*
 * /studios/ page motion.
 *
 * Two jobs: reveal blocks as they scroll into view (which also starts the art
 * animations, all of which are keyed on an .is-visible ancestor in studios.css)
 * and count the numbers strip up from zero.
 *
 * The hidden start state lives behind the .st-js class that the inline snippet in
 * layouts/studios/list.html sets. If this script never runs, that snippet drops the
 * class again after a moment and the page shows in its finished state — so a
 * blocked or failed script degrades to "no animation", never to "no content".
 */
(function () {
	'use strict';

	var page = document.querySelector('.st-page');
	if (!page) return;

	window.__stReady = true;

	var root = document.documentElement;
	var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
	var items = Array.prototype.slice.call(page.querySelectorAll('.st-reveal'));

	function showAll() {
		root.classList.remove('st-js');
		items.forEach(function (el) { el.classList.add('is-visible'); });
	}

	if (reduceMotion || !('IntersectionObserver' in window)) {
		showAll();
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

	/* ---- numbers strip: count up once, when it is on screen ---- */
	var values = Array.prototype.slice.call(page.querySelectorAll('.st-number-value'));
	if (!values.length) return;

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

	var numberObserver = new IntersectionObserver(function (entries) {
		entries.forEach(function (entry) {
			if (!entry.isIntersecting) return;
			countUp(entry.target);
			numberObserver.unobserve(entry.target);
		});
	}, { threshold: 0.6 });

	values.forEach(function (el) { numberObserver.observe(el); });
})();
