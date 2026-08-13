
jQuery(function ($) {
	"use strict";

	/* ========================================================================= */
	/*	lazy load initialize
	/* ========================================================================= */

	const observer = lozad(); // lazy loads elements with default selector as ".lozad"
	observer.observe();

	/* ========================================================================= */
	/*	Magnific popup
	/* =========================================================================  */
	$('.image-popup').magnificPopup({
		type: 'image',
		removalDelay: 160, //delay removal by X to allow out-animation
		callbacks: {
			beforeOpen: function () {
				// just a hack that adds mfp-anim class to markup
				this.st.image.markup = this.st.image.markup.replace('mfp-figure', 'mfp-figure mfp-with-anim');
				this.st.mainClass = this.st.el.attr('data-effect');
			}
		},
		closeOnContentClick: true,
		midClick: true,
		fixedContentPos: false,
		fixedBgPos: true
	});

	/* ========================================================================= */
	/*	Portfolio Filtering Hook
	/* =========================================================================  */

	var containerEl = document.querySelector('.shuffle-wrapper');
	if (containerEl) {
		var Shuffle = window.Shuffle;
		var myShuffle = new Shuffle(document.querySelector('.shuffle-wrapper'), {
			itemSelector: '.shuffle-item',
			buffer: 1
		});

		jQuery('input[name="shuffle-filter"]').on('change', function (evt) {
			var input = evt.currentTarget;
			if (input.checked) {
				myShuffle.filter(input.value);
			}
		});
	}

	/* ========================================================================= */
	/*	Testimonial Carousel
	/* =========================================================================  */

	$("#testimonials").slick({
		infinite: true,
		arrows: false,
		autoplay: true,
		autoplaySpeed: 4000
	});

	/* ========================================================================= */
	/*	animation scroll js
	/* ========================================================================= */



	function myFunction(x) {
		if (x.matches) {
			var topOf = 50
		} else {
			var topOf = 350
		}
	}

	var html_body = $('html, body');
	$('nav a, .page-scroll').on('click', function () { //use page-scroll class in any HTML tag for scrolling
		if (location.pathname.replace(/^\//, '') === this.pathname.replace(/^\//, '') && location.hostname === this.hostname) {
			var target = $(this.hash);
			target = target.length ? target : $('[name=' + this.hash.slice(1) + ']');
			if (target.length) {
				html_body.animate({
					scrollTop: target.offset().top
				}, 1000, 'easeInOutExpo');
				return false;
			}
		}
	});

	// easeInOutExpo Declaration
	jQuery.extend(jQuery.easing, {
		easeInOutExpo: function (x, t, b, c, d) {
			if (t === 0) {
				return b;
			}
			if (t === d) {
				return b + c;
			}
			if ((t /= d / 2) < 1) {
				return c / 2 * Math.pow(2, 10 * (t - 1)) + b;
			}
			return c / 2 * (-Math.pow(2, -10 * --t) + 2) + b;
		}
	});

	/* ========================================================================= */
	/*	counter up
	/* ========================================================================= */
	function counter() {
		var oTop;
		if ($('.count').length !== 0) {
			oTop = $('.count').offset().top - window.innerHeight;
		}
		if ($(window).scrollTop() > oTop) {
			$('.count').each(function () {
				var $this = $(this),
					countTo = $this.attr('data-count');
				$({
					countNum: $this.text()
				}).animate({
					countNum: countTo
				}, {
					duration: 1000,
					easing: 'swing',
					step: function () {
						$this.text(Math.floor(this.countNum));
					},
					complete: function () {
						$this.text(this.countNum);
					}
				});
			});
		}
	}
	$(window).on('scroll', function () {
		counter();
	});

});
/* ---- Docs screenshot lightbox: click a docs image to view it enlarged in a modal overlay ---- */
(function () {
	function initDocsLightbox() {
		var imgs = document.querySelectorAll('.docs-main-content .imageblock img');
		if (!imgs.length) { return; }

		var overlay = document.createElement('div');
		overlay.className = 'ml-lightbox';
		overlay.setAttribute('role', 'dialog');
		overlay.setAttribute('aria-modal', 'true');

		var big = document.createElement('img');
		big.className = 'ml-lightbox-img';

		var closeBtn = document.createElement('button');
		closeBtn.type = 'button';
		closeBtn.className = 'ml-lightbox-close';
		closeBtn.setAttribute('aria-label', 'Close');
		closeBtn.innerHTML = '&times;';

		overlay.appendChild(big);
		overlay.appendChild(closeBtn);
		document.body.appendChild(overlay);

		function openLightbox(src, alt) {
			big.setAttribute('src', src);
			big.setAttribute('alt', alt || '');
			overlay.classList.add('is-open');
			document.body.style.overflow = 'hidden';
		}
		function closeLightbox() {
			overlay.classList.remove('is-open');
			document.body.style.overflow = '';
			big.removeAttribute('src');
		}

		for (var i = 0; i < imgs.length; i++) {
			(function (im) {
				im.addEventListener('click', function () {
					openLightbox(im.currentSrc || im.src, im.getAttribute('alt'));
				});
			})(imgs[i]);
		}

		overlay.addEventListener('click', closeLightbox);
		closeBtn.addEventListener('click', function (e) { e.stopPropagation(); closeLightbox(); });
		document.addEventListener('keydown', function (e) {
			if ((e.key === 'Escape' || e.keyCode === 27) && overlay.classList.contains('is-open')) {
				closeLightbox();
			}
		});
	}

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', initDocsLightbox);
	} else {
		initDocsLightbox();
	}
})();

/* Site header behaviour on scroll. Two effects, one scroll listener.
 *
 * 1. `.is-scrolled` (every viewport) solidifies the translucent bar and — see the "Site header"
 *    block in custom.less — draws it in its compact state: less padding, a smaller logo, a shorter
 *    search box. It is the same header, closer to the edge of the screen.
 *
 *    It is a SCHMITT TRIGGER, not a threshold: compact above COMPACT_AT, expand again only below
 *    EXPAND_AT, and hold whatever it is between the two. A single threshold made the logo jitter,
 *    and not only for the obvious reason that a reader hovering on the line flips it every few
 *    pixels. The header is `position: sticky`, so it is IN FLOW: compacting it takes ~15px out of
 *    the document, everything below moves up, and the browser's scroll anchoring corrects for that
 *    by moving the scroll position back — across the same threshold, which expands the header,
 *    which puts the 15px back, which trips the anchor again. That loop runs at frame rate off one
 *    flick of the wheel. The band has to stay comfortably wider than the height the header gives
 *    up (56px against 15px here), or it comes back.
 *
 * 2. `.is-hidden` (below the lg breakpoint only) slides the whole bar out of the way while the
 *    reader is scrolling DOWN and brings it straight back on the first upward scroll. On a phone
 *    the bar is a permanent ~15% of the viewport spent on navigation nobody is using mid-article;
 *    reading is what the screen is for. It comes back on an upward flick, which is the gesture a
 *    reader already makes when they want the chrome — so the bar is never more than one flick away
 *    and there is no separate control to discover.
 *
 * Four states must NOT hide the bar, because the reader is using it: the hamburger panel is open,
 * the search box has focus, the search overlay is up, or the page is scrolled to the very top
 * (where hiding it would just make the first flick of a page load flicker).
 *
 * Everything here is decoration: with JavaScript off the header keeps its full-size, always-visible
 * state, which is the state it is designed in.
 */
(function () {
	var nav = document.querySelector('.navigation');
	if (!nav) return;

	var MOBILE = '(max-width: 991px)';
	/* The two edges of the compact state. Anything between them keeps the state it already has. */
	var COMPACT_AT = 80;
	var EXPAND_AT = 24;
	var TOP_ZONE = 90;   /* above this the bar always shows — one header's worth of page */
	/* Asymmetric on purpose. Hiding needs only a deliberate downward scroll, but *showing* has to
	   ignore the small upward corrections a page makes on its own — a late-loading image, a font
	   swap or Chromium's scroll anchoring all nudge the offset back a few pixels, and a symmetric
	   threshold made the bar reappear a moment after every flick, which reads as flicker. */
	var HIDE_AFTER = 8;
	var SHOW_AFTER = 26;

	var lastY = window.pageYOffset || document.documentElement.scrollTop;
	var up = 0, down = 0;
	var compact = false;
	var ticking = false;

	function held() {
		var panel = nav.querySelector('.navbar-collapse');
		if (panel && panel.classList.contains('show')) return true;
		if (document.body.classList.contains('docs-search-open')) return true;
		var focused = document.activeElement;
		return !!(focused && nav.contains(focused));
	}

	function sync() {
		var y = window.pageYOffset || document.documentElement.scrollTop;
		var delta = y - lastY;
		lastY = y;

		if (compact) {
			if (y < EXPAND_AT) compact = false;
		} else if (y > COMPACT_AT) {
			compact = true;
		}
		nav.classList.toggle('is-scrolled', compact);

		/* Travel since the last direction change, not the size of one scroll event. A phone
		   delivers a flick as dozens of small deltas, so a per-event threshold would never fire
		   on a slow drag and would fire on every jitter. */
		if (delta > 0) { up = 0; down += delta; }
		else if (delta < 0) { down = 0; up -= delta; }

		if (!window.matchMedia(MOBILE).matches || held() || y <= TOP_ZONE) {
			nav.classList.remove('is-hidden');
		} else if (down > HIDE_AFTER) {
			nav.classList.add('is-hidden');
		} else if (up > SHOW_AFTER) {
			nav.classList.remove('is-hidden');
		}

		ticking = false;
	}

	function onScroll() {
		if (ticking) return;
		ticking = true;
		window.requestAnimationFrame(sync);
	}

	sync();
	window.addEventListener('scroll', onScroll, { passive: true });
	/* A hidden bar must not stay hidden once the reason to hide it is gone: rotating to landscape
	   can cross the breakpoint, and opening the menu or the search box is a request for it. */
	window.addEventListener('resize', function () { lastY = window.pageYOffset; sync(); });
	document.addEventListener('focusin', function () { if (held()) nav.classList.remove('is-hidden'); });
	nav.addEventListener('click', function () { nav.classList.remove('is-hidden'); });
})();
