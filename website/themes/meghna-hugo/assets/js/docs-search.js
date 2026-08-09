/*
 * Semantic search for /docs/.
 *
 * Two passes over one index, because they fail in opposite directions:
 *
 *   substring — page titles, section headings, URLs and every identifier the page shows in
 *               <code>. Answers from a ~120 KB JSON file, so it is live on the first keystroke.
 *               This is the pass that finds "fingerprint-dedup-apply", which the model reads as
 *               six meaningless WordPiece fragments.
 *   semantic  — cosine similarity against Ternlight embeddings. This is the pass that finds the
 *               dedup node when a reader types "how do I get rid of duplicate photos", where no
 *               word of the query appears on the page.
 *
 * Nothing is fetched until the reader focuses the box: the model is 7 MB and most visitors never
 * search. While it downloads, the substring pass answers on its own and the panel says so — a
 * spinner over an empty list would be the wrong trade for a control that already has an answer.
 *
 * The box is hidden until docs-search-bootstrap.html's inline snippet flags JavaScript as
 * working, and that snippet takes the flag away again if this script never runs. A reader with
 * JavaScript off sees the topic rail they have always seen, not a text field that does nothing.
 */
(function () {
	'use strict';

	var roots = Array.prototype.slice.call(document.querySelectorAll('[data-docs-search]'));
	var config = document.querySelector('script[data-search-index-url]');
	if (!roots.length || !config) return;

	// Tells docs-search-bootstrap.html's timer that the script arrived, so the box may stay.
	window.__docsSearchReady = true;

	var INDEX_URL = config.getAttribute('data-search-index-url');
	var VECTORS_URL = config.getAttribute('data-search-vectors-url');
	var MODEL_URL = config.getAttribute('data-search-model-url');
	var WASM_URL = config.getAttribute('data-search-wasm-url');

	var MAX_RESULTS = 8;
	/* One page must not be able to fill the panel with eight views of itself. */
	var MAX_PER_PAGE = 2;
	/* Ternlight reads 128 WordPiece tokens and silently drops the rest. */
	var MAX_QUERY_WORDS = 60;

	var index = null;      // the metadata sidecar
	var vectors = null;    // Int8Array, count × dims
	var embed = null;      // the model, once it is up

	var indexState = 'idle';   // idle | loading | ready | failed
	var modelState = 'idle';
	var indexPromise = null;
	var modelPromise = null;

	var activeInput = null;
	var activeRow = -1;
	var rows = [];
	var lastQuery = '';
	var debounce = null;

	// -- loading -----------------------------------------------------------------------------

	function loadIndex() {
		if (indexPromise) return indexPromise;
		indexState = 'loading';
		indexPromise = Promise.all([
			fetch(INDEX_URL).then(function (r) {
				if (!r.ok) throw new Error(INDEX_URL + ' → ' + r.status);
				return r.json();
			}),
			fetch(VECTORS_URL).then(function (r) {
				if (!r.ok) throw new Error(VECTORS_URL + ' → ' + r.status);
				return r.arrayBuffer();
			})
		]).then(function (parts) {
			index = parts[0];
			vectors = new Int8Array(parts[1]);
			indexState = 'ready';
			render();
		}).catch(function (e) {
			indexState = 'failed';
			if (window.console) console.warn('docs search: index unavailable —', e);
			render();
		});
		return indexPromise;
	}

	function loadModel() {
		if (modelPromise) return modelPromise;
		modelState = 'loading';
		// The index has to land first: its model stamp is what the wasm URL is versioned by.
		modelPromise = loadIndex().then(function () {
			if (indexState !== 'ready') throw new Error('no index');

			// The wasm filename is stable, so a reader who searched before a model bump would
			// otherwise keep a cached engine from a *different* embedding space — vectors that
			// are not merely less accurate but not comparable at all. Versioning the URL by the
			// build's own checksum makes that cache entry unreachable instead of wrong.
			var url = WASM_URL + '?v=' + String(index.model.sha256 || '').slice(0, 12);

			return import(MODEL_URL).then(function (mod) {
				// The object form, not the bare URL — wasm-bindgen deprecated positional
				// arguments and warns on the console for every reader who opens the box.
				return mod.default({ module_or_path: url }).then(function () {
					embed = mod.embed;
					modelState = 'ready';
					render();
				});
			});
		}).catch(function (e) {
			modelState = 'failed';
			if (window.console) console.warn('docs search: semantic model unavailable —', e);
			render();
		});
		return modelPromise;
	}

	// -- the two passes ----------------------------------------------------------------------

	/**
	 * Literal matches, scored by how specific the field is. A query that *is* a page title beats
	 * one that merely appears in a paragraph, and an exact identifier beats a prefix of one.
	 */
	function substringPass(query) {
		var needle = query.toLowerCase();
		var best = {};

		// Whether the query looks like a name the product uses rather than an English word.
		// It decides how much an exact <code> match is worth, and the distinction is load-bearing:
		// "fingerprint-dedup-apply" in a code block means that page is *about* it, while
		// "kubernetes" in a code block means someone pasted a command — the page titled after the
		// word is what the reader wants, and it must not be outranked by a passing mention.
		var identifierLike = /[-_./]/.test(needle) || /\d/.test(needle);

		function offer(chunkIndex, score) {
			var chunk = index.chunks[chunkIndex];
			var key = chunk[0] + '#' + chunk[1];
			if (!best[key] || best[key].score < score) best[key] = { n: chunkIndex, score: score };
		}

		// Page-level fields point at the page's first chunk, which is its opening paragraph.
		var firstChunkOf = {};
		for (var n = 0; n < index.chunks.length; n++) {
			var p = index.chunks[n][0];
			if (!(p in firstChunkOf)) firstChunkOf[p] = n;
		}

		// Only the two exact forms — the query IS this page's title, or IS one of its identifiers
		// — outrank a plain title match. Everything else is weaker than the page being *about*
		// the word: "kubernetes" appears in a code sample on the chat and configuration pages,
		// and neither of those is what a reader typing it is looking for.
		for (var i = 0; i < index.pages.length; i++) {
			var page = index.pages[i];
			if (!(i in firstChunkOf)) continue;
			var title = page.t.toLowerCase();
			var score = 0;

			if (title === needle) score = 10;
			else if (title.indexOf(needle) === 0) score = 8;
			else if (title.indexOf(needle) !== -1) score = 7;
			else if (page.u.toLowerCase().indexOf(needle) !== -1) score = 5;

			for (var t = 0; page.c && t < page.c.length; t++) {
				var term = page.c[t];
				if (term === needle) { score = Math.max(score, identifierLike ? 9 : 5); break; }
				if (term.indexOf(needle) === 0) score = Math.max(score, 4);
				else if (term.indexOf(needle) !== -1) score = Math.max(score, 3);
			}
			if (score) offer(firstChunkOf[i], score);
		}

		for (var c = 0; c < index.chunks.length; c++) {
			var chunk = index.chunks[c];
			if (chunk[2] && chunk[2].toLowerCase().indexOf(needle) !== -1) offer(c, 6);
			else if (chunk[3] && chunk[3].toLowerCase().indexOf(needle) !== -1) offer(c, 2);
		}

		var out = [];
		for (var key in best) if (Object.prototype.hasOwnProperty.call(best, key)) out.push(best[key]);
		out.sort(function (a, b) { return b.score - a.score; });
		return out;
	}

	/** Cosine similarity, straight out of the int8 blob — the query vector carries the scale. */
	function semanticPass(query) {
		var q = embed(query);
		var dims = index.dims;
		var scored = new Array(index.count);
		for (var n = 0; n < index.count; n++) {
			var base = n * dims;
			var dot = 0;
			for (var i = 0; i < dims; i++) dot += q[i] * vectors[base + i];
			scored[n] = { n: n, score: dot / index.scale };
		}
		scored.sort(function (a, b) { return b.score - a.score; });

		// A relative floor rather than a fixed one: what counts as a good score depends on how
		// well the corpus answers the question at all, and a fixed cutoff either buries real
		// answers to hard queries or pads easy ones with noise.
		var top = scored.length ? scored[0].score : 0;
		var floor = Math.max(0.22, top * 0.55);
		return scored.filter(function (r) { return r.score >= floor; });
	}

	function results(query) {
		var strong = [];
		var weak = [];
		substringPass(query).forEach(function (hit) {
			(hit.score >= 6 ? strong : weak).push(hit);
		});

		var semantic = (modelState === 'ready') ? semanticPass(query) : [];

		var seen = {};
		var perPage = {};
		var out = [];
		[strong, semantic, weak].forEach(function (list) {
			for (var i = 0; i < list.length && out.length < MAX_RESULTS; i++) {
				var chunk = index.chunks[list[i].n];
				var key = chunk[0] + '#' + chunk[1];
				if (seen[key]) continue;
				if ((perPage[chunk[0]] || 0) >= MAX_PER_PAGE) continue;
				seen[key] = true;
				perPage[chunk[0]] = (perPage[chunk[0]] || 0) + 1;
				out.push(chunk);
			}
		});
		return out;
	}

	// -- the panel ---------------------------------------------------------------------------

	var panel = document.createElement('div');
	panel.className = 'docs-search-panel';
	panel.id = 'docs-search-panel';
	panel.setAttribute('role', 'listbox');
	panel.hidden = true;

	var status = document.createElement('p');
	status.className = 'docs-search-status';
	status.setAttribute('aria-live', 'polite');

	var list = document.createElement('div');
	list.className = 'docs-search-results';

	panel.appendChild(status);
	panel.appendChild(list);
	document.body.appendChild(panel);

	/**
	 * The panel hangs off <body>, not off the rail.
	 *
	 * .docs-sidebar is a scroll container — and, by design, the only one in the rail — so a
	 * dropdown inside it would be clipped at the rail's edge and would put a second scrollbar a
	 * dozen pixels from the first. Positioning it against the input's own box instead leaves the
	 * rail's geometry completely alone.
	 */
	function place() {
		if (!activeInput) return;
		var box = activeInput.getBoundingClientRect();
		var width = Math.min(Math.max(box.width, 380), window.innerWidth - 24);
		var left = Math.min(box.left, window.innerWidth - width - 12);
		panel.style.left = Math.max(12, left) + 'px';
		panel.style.top = (box.bottom + 6) + 'px';
		panel.style.width = width + 'px';
	}

	/**
	 * Marks the reader's own words wherever they appear, building nodes rather than markup so a
	 * result can never inject anything into the page.
	 *
	 * Per word rather than per query: a semantic hit is precisely the case where the whole phrase
	 * does not appear, and "how do I get rid of duplicate photos" would then highlight nothing at
	 * all on a page that is entirely about duplicates.
	 */
	function terms(query) {
		var seen = {};
		return query.toLowerCase().split(/[^a-z0-9_.\-/]+/).filter(function (word) {
			if (word.length < 3 || seen[word]) return false;
			seen[word] = true;
			return true;
		});
	}

	function withHighlight(text, words) {
		var fragment = document.createDocumentFragment();
		var haystack = text.toLowerCase();
		var from = 0;

		while (from < text.length) {
			var at = -1;
			var len = 0;
			for (var i = 0; i < words.length; i++) {
				var found = haystack.indexOf(words[i], from);
				// Leftmost match wins; on a tie the longer word does, so "dedup" inside
				// "deduplication" does not cut the longer highlight short.
				if (found !== -1 && (at === -1 || found < at || (found === at && words[i].length > len))) {
					at = found;
					len = words[i].length;
				}
			}
			if (at === -1) break;
			if (at > from) fragment.appendChild(document.createTextNode(text.slice(from, at)));
			var mark = document.createElement('mark');
			mark.textContent = text.slice(at, at + len);
			fragment.appendChild(mark);
			from = at + len;
		}
		fragment.appendChild(document.createTextNode(text.slice(from)));
		return fragment;
	}

	function render() {
		if (!activeInput) return;
		var query = activeInput.value.trim();

		if (!query) {
			close();
			return;
		}

		panel.hidden = false;
		activeInput.setAttribute('aria-expanded', 'true');
		place();

		if (indexState === 'failed') {
			say('Search is unavailable — the index could not be loaded.');
			clear();
			return;
		}
		if (indexState !== 'ready') {
			say('Loading search…');
			clear();
			return;
		}

		var found = results(query);
		clear();

		var words = terms(query);
		found.forEach(function (chunk, i) {
			var page = index.pages[chunk[0]];
			var row = document.createElement('a');
			row.className = 'docs-search-result';
			row.href = page.u + (chunk[1] ? '#' + chunk[1] : '');
			row.id = 'docs-search-option-' + i;
			row.setAttribute('role', 'option');
			row.setAttribute('aria-selected', 'false');

			var head = document.createElement('span');
			head.className = 'dsr-title';
			head.appendChild(withHighlight(page.t, words));
			if (chunk[2]) {
				var sub = document.createElement('span');
				sub.className = 'dsr-section';
				sub.appendChild(withHighlight(chunk[2], words));
				head.appendChild(sub);
			}

			var where = document.createElement('span');
			where.className = 'dsr-where';
			where.textContent = page.e || 'Documentation';

			var snippet = document.createElement('span');
			snippet.className = 'dsr-snippet';
			snippet.appendChild(withHighlight(chunk[3], words));

			row.appendChild(where);
			row.appendChild(head);
			row.appendChild(snippet);
			list.appendChild(row);
		});

		rows = Array.prototype.slice.call(list.children);
		activeRow = -1;
		activeInput.removeAttribute('aria-activedescendant');

		if (!found.length) {
			say(modelState === 'ready' || modelState === 'failed'
				? 'No matches for “' + query + '”.'
				: 'No literal matches yet — loading semantic search…');
			return;
		}

		var note = found.length === 1 ? '1 result' : found.length + ' results';
		if (modelState === 'loading') note += ' — loading semantic search…';
		else if (modelState === 'failed') note += ' — semantic search unavailable, matching text only.';
		else if (query.split(/\s+/).length > MAX_QUERY_WORDS) note += ' — only the first ~' + MAX_QUERY_WORDS + ' words were read.';
		say(note);
	}

	function say(text) { status.textContent = text; }
	function clear() { while (list.firstChild) list.removeChild(list.firstChild); rows = []; activeRow = -1; }

	function close() {
		panel.hidden = true;
		clear();
		if (activeInput) {
			activeInput.setAttribute('aria-expanded', 'false');
			activeInput.removeAttribute('aria-activedescendant');
		}
	}

	function highlightRow(next) {
		if (!rows.length) return;
		if (activeRow >= 0) rows[activeRow].setAttribute('aria-selected', 'false');
		activeRow = (next + rows.length) % rows.length;
		var row = rows[activeRow];
		row.setAttribute('aria-selected', 'true');
		activeInput.setAttribute('aria-activedescendant', row.id);
		row.scrollIntoView({ block: 'nearest' });
	}

	// -- wiring ------------------------------------------------------------------------------

	roots.forEach(function (root) {
		var input = root.querySelector('input');
		if (!input) return;

		input.setAttribute('role', 'combobox');
		input.setAttribute('aria-controls', 'docs-search-panel');
		input.setAttribute('aria-expanded', 'false');
		input.setAttribute('aria-autocomplete', 'list');
		input.setAttribute('autocomplete', 'off');

		function begin() {
			activeInput = input;
			loadIndex();
			loadModel();
			// Coming back to a box that still holds a query should show its results again rather
			// than waiting for a keystroke to prove the reader meant it.
			if (input.value.trim()) render();
		}

		input.addEventListener('focus', begin);
		input.addEventListener('input', function () {
			begin();
			if (input.value.trim() === lastQuery) return;
			lastQuery = input.value.trim();
			clearTimeout(debounce);
			// Long enough that a fast typist runs one search rather than eight, short enough that
			// it still feels like it is answering as you type.
			debounce = setTimeout(render, 140);
		});

		input.addEventListener('keydown', function (e) {
			if (e.key === 'Escape') { close(); input.blur(); return; }
			if (panel.hidden) return;
			if (e.key === 'ArrowDown') { e.preventDefault(); highlightRow(activeRow + 1); }
			else if (e.key === 'ArrowUp') { e.preventDefault(); highlightRow(activeRow - 1); }
			else if (e.key === 'Enter' && activeRow >= 0) { e.preventDefault(); rows[activeRow].click(); }
		});

		root.addEventListener('submit', function (e) { e.preventDefault(); });
	});

	document.addEventListener('click', function (e) {
		if (panel.hidden) return;
		if (panel.contains(e.target)) return;
		if (activeInput && activeInput.parentNode.contains(e.target)) return;
		close();
	});

	document.addEventListener('keydown', function (e) {
		if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
		var el = document.activeElement;
		if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) return;
		// Focus the box the reader can actually see: the rail is hidden below the lg breakpoint.
		for (var i = 0; i < roots.length; i++) {
			var input = roots[i].querySelector('input');
			if (input && input.offsetParent !== null) { e.preventDefault(); input.focus(); return; }
		}
	});

	window.addEventListener('resize', place);
	window.addEventListener('scroll', place, { passive: true });
}());
