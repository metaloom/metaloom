/*
 * /help/ — resolves an in-product help link to a documentation page.
 *
 * The help icons in the Loom UI link here rather than at a `/docs/…#anchor` of their own, because
 * an installed Loom outlives the site it points at: wiring a shipped build to a heading means every
 * already-deployed instance breaks the day that heading is reworded, silently, on the reader's
 * machine. So the UI sends what it knows — a stable topic id, and the question in words —
 *
 *     /help/?t=pipeline.editing&q=build+a+pipeline+connect+nodes+typed+ports
 *
 * and this decides what that means today.
 *
 *   1. The curated map, read straight out of the links this page already rendered. Instant, exact,
 *      and checked by the build (check-links.mjs walks those same anchors), which is what makes the
 *      common case a fixed link with none of a fixed link's fragility. Every topic a shipped UI
 *      sends is in the map, so in practice this is the only step that ever runs.
 *   2. A strong literal hit on the search index, for an id the map does not have — a UI newer than
 *      the site, or older than a renamed topic. "Strong" is docs-search.js's own threshold: the
 *      query *is* a page title, or matches a section heading. That is a fact about the corpus, not
 *      a judgement, so it is safe to act on without asking.
 *   3. Otherwise the ranked candidates, semantic pass included, listed above the shortcut index —
 *      and nobody is moved anywhere.
 *
 * Step 3 does NOT auto-redirect on cosine similarity, and that is a measurement rather than a
 * preference. Scored against this corpus, genuine product questions land between 0.37 and 0.56
 * ("why is my video not playing" → 0.404), while questions the documentation cannot answer at all
 * reach 0.54 ("reset the password on my home router" → 0.488, straight at the authentication page).
 * The distributions overlap almost completely: every floor that rejects the router question also
 * rejects four real ones. A 15.4M-parameter model can rank this corpus but it cannot decide about
 * it — the same limit WEBSITE_SEARCH.md already records for two-word queries — so it ranks, and the
 * reader decides. A reader who followed a help icon must always land somewhere they can act on, and
 * a confidently wrong page is worse than a short list with the shortcut index underneath it.
 */
(function () {
	'use strict';

	/* docs-search.js's own "strong hit" line: at 6 and above the query matched a page title or a
	 * section heading, not a passing mention inside a paragraph. */
	var LITERAL_FLOOR = 6;

	var MAX_CANDIDATES = 6;

	var status = document.getElementById('help-status');
	var box = document.getElementById('help-results');
	if (!status || !box) return;

	var params = new URLSearchParams(window.location.search);
	var topic = (params.get('t') || '').trim();
	var query = (params.get('q') || '').trim();

	/* The map is read out of the DOM rather than handed over as data. The anchors below are what
	 * check-links.mjs verified at build time, so the link that was checked is by construction the
	 * link this redirects to — there is no second copy of these URLs to fall out of step. */
	function curated(id) {
		if (!id) return null;
		var selector = '[data-help-topic="' +
			(window.CSS && CSS.escape ? CSS.escape(id) : id.replace(/["\\]/g, '\\$&')) + '"]';
		var link = document.querySelector(selector);
		return link ? link.getAttribute('href') : null;
	}

	/* `replace`, not `assign`: this page is a junction, and leaving it in the history means Back
	 * from the documentation lands the reader on it again and bounces them straight forward. */
	function go(url) { window.location.replace(url); }

	function say(text) { status.textContent = text; }

	// -- 1. the curated map, before anything is fetched -----------------------------------------

	var direct = curated(topic);
	if (direct) {
		go(direct);
		return;
	}

	if (!topic && !query) {
		/* Somebody opened /help/ on its own. The shortcut index already is the page. */
		return;
	}

	if (!query) {
		/* An id this build does not know, and nothing to search with. An older UI that sends only
		   `t` gets the index; there is nothing better to be done, and it is not a failure worth
		   alarming anyone about. */
		say('That shortcut is not one this site knows. Everything it does know is below.');
		return;
	}

	// -- 2 & 3. fall back to the documentation search --------------------------------------------

	say('Finding the right page…');

	/* docs-search.js is deferred, so it has not run yet — this script is not, precisely so the
	   curated path above can answer before the page has finished parsing. Only the fallback waits,
	   and it was going to wait on a network fetch regardless. */
	function whenEngineReady(then) {
		if (window.metaloomDocsSearch) { then(window.metaloomDocsSearch); return; }
		document.addEventListener('DOMContentLoaded', function () {
			if (window.metaloomDocsSearch) then(window.metaloomDocsSearch);
			else say('Search is unavailable here. The shortcuts below cover the same ground.');
		});
	}

	whenEngineReady(function (engine) {
		engine.loadIndex().then(function () {
			if (!engine.indexReady()) {
				say('The documentation index could not be loaded. The shortcuts below still work.');
				return;
			}

			var literal = engine.literal(query);
			if (literal.length && literal[0].score >= LITERAL_FLOOR) {
				go(engine.describe(engine.chunkAt(literal[0].n)).url);
				return;
			}

			/* Nothing matched by name — which for a question phrased in words is the normal case,
			   not a failure. That is what the model is for: it is the pass that connects "how do I
			   get rid of duplicate photos" to a page sharing no word with it. It shapes the list;
			   see the header for why it does not shape a redirect. */
			return engine.loadModel().then(function () { offer(engine); });
		}).catch(function (e) {
			if (window.console) console.warn('help: could not resolve —', e);
			say('That page could not be found automatically. The shortcuts below cover the documentation.');
		});
	});

	/**
	 * Show what the two passes found, above the shortcut index that is already on the page, so the
	 * reader keeps both the near misses and the full list.
	 */
	function offer(engine) {
		var found = engine.results(query).slice(0, MAX_CANDIDATES);

		if (!found.length) {
			say('Nothing in the documentation matched “' + query + '”. The shortcuts below may help.');
			return;
		}

		found.forEach(function (chunk) {
			var hit = engine.describe(chunk);
			var row = document.createElement('a');
			row.className = 'hp-result';
			row.href = hit.url;

			var where = document.createElement('span');
			where.className = 'hp-result-where';
			where.textContent = hit.where;

			var title = document.createElement('span');
			title.className = 'hp-result-title';
			title.textContent = hit.section ? hit.title + ' — ' + hit.section : hit.title;

			var snippet = document.createElement('span');
			snippet.className = 'hp-result-snippet';
			snippet.textContent = hit.snippet;

			/* Text nodes throughout, never markup: a snippet is corpus text arriving through a
			   query string, and this page has no business parsing either as HTML. */
			row.appendChild(where);
			row.appendChild(title);
			row.appendChild(snippet);
			box.appendChild(row);
		});

		box.hidden = false;
		say(engine.modelReady()
			? 'These pages come closest to “' + query + '”:'
			: 'Semantic search is unavailable, so these are text matches only for “' + query + '”:');
	}
}());
