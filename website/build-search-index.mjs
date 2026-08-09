#!/usr/bin/env node
// Builds the client-side semantic search index for /docs/.
//
// Walks every documentation page in dist/, cuts it into chunks small enough for the embedding
// model to actually read, embeds each chunk with the vendored Ternlight engine, and writes a
// metadata sidecar plus a quantised vector blob that the browser downloads on first search.
//
// It indexes the BUILT HTML rather than the AsciiDoc source, for two reasons. Heading anchors
// only exist after Asciidoctor has run — pages mix explicit [#node-graph] attributes with
// generated _snake_case ids — so reading the output is what keeps a deep link and its index
// entry regenerating together when a heading is reworded. And the page chrome (nav, footer, the
// topic rail, the "Looking for something else?" foot) is a layout concern that one selector
// removes here and would have to be re-implemented against the source otherwise.
//
// The engine is the SAME file the browser loads — themes/meghna-hugo/static/plugins/ternlight/.
// Embeddings from two different model builds are not comparable at all (they are points in
// different spaces, not merely less accurate), so there is deliberately only one model file.
//
// Usage: node build-search-index.mjs [distDir]
// Exits 1 when the index would ship broken or empty — see the gates in main().

import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { availableParallelism } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';

import { initSync, embed, tokenize } from './themes/meghna-hugo/static/plugins/ternlight/tern_engine.js';

const HERE = dirname(new URL(import.meta.url).pathname);
const MODEL_DIR = join(HERE, 'themes/meghna-hugo/static/plugins/ternlight');
const WASM = join(MODEL_DIR, 'tern_engine_bg.wasm');

/** The model reads at most this many WordPiece tokens; everything past it is silently dropped. */
const MAX_TOKENS = 128;
/** Ternlight emits 384-dim L2-normalised vectors. */
const DIMS = 384;

/**
 * Pages that exist in the output but are not documentation a reader should be sent to.
 * The three stubs are the same ones partials/docs-topics.html leaves out of the topic rail;
 * examples/ is the staged OpenAPI / GraphQL artefact directory, not prose.
 */
const SKIP_PAGES = new Set(['/docs/rest/', '/docs/test/', '/docs/configuration/']);
const SKIP_PREFIXES = ['/docs/examples/'];

/** Below this many pages something has gone wrong upstream rather than in the content. */
const MIN_PAGES = 50;

// ---------------------------------------------------------------------------------------------
// Worker branch
//
// A 120-token forward pass costs ~27 ms, and there are well over a thousand of them — enough to
// make this step dominate a build that otherwise finishes in under a second. The work is
// embarrassingly parallel (one independent inference per chunk), so it is fanned out across
// threads, each holding its own instance of the same wasm.
// ---------------------------------------------------------------------------------------------

if (!isMainThread) {
	initSync({ module: readFileSync(workerData.wasm) });
	const out = new Float32Array(workerData.texts.length * DIMS);
	workerData.texts.forEach((text, i) => out.set(embed(text), i * DIMS));
	parentPort.postMessage(out, [out.buffer]);
}

// ---------------------------------------------------------------------------------------------
// HTML → text
// ---------------------------------------------------------------------------------------------

/** Recursively list files under dir. */
function walk(dir, out = []) {
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		const full = join(dir, entry.name);
		if (entry.isDirectory()) walk(full, out);
		else if (entry.isFile()) out.push(full);
	}
	return out;
}

const NAMED_ENTITIES = {
	amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', shy: '',
	mdash: '—', ndash: '–', hellip: '…', rarr: '→', larr: '←', times: '×', middot: '·'
};

function decodeEntities(s) {
	return s.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (whole, body) => {
		if (body[0] === '#') {
			const code = body[1] === 'x' || body[1] === 'X'
				? parseInt(body.slice(2), 16)
				: parseInt(body.slice(1), 10);
			return Number.isFinite(code) ? String.fromCodePoint(code) : whole;
		}
		return body in NAMED_ENTITIES ? NAMED_ENTITIES[body] : whole;
	});
}

/**
 * Slice out the element opened at `start`, by counting <div>/</div> depth.
 *
 * A regex cannot do this — the content column contains dozens of nested divs and ends at a
 * closing tag indistinguishable from theirs. Depth counting is exact and the markup is generated,
 * so it is well-formed by construction.
 */
function sliceDiv(html, start) {
	const tag = /<(\/?)div\b/gi;
	tag.lastIndex = start;
	let depth = 0;
	let m;
	while ((m = tag.exec(html))) {
		if (m[1]) {
			depth--;
			if (depth === 0) return html.slice(start, m.index);
		} else {
			depth++;
		}
	}
	return null;
}

/**
 * Strip markup to readable text.
 *
 * Code blocks go: a chunk has ~120 tokens of budget and a YAML sample spends all of it on syntax
 * the model has no representation for. Exact identifiers are what the client's substring pass is
 * for. Inline <code> stays — it is usually a term inside a sentence that carries meaning.
 */
function toText(html) {
	return decodeEntities(
		html
			.replace(/<(script|style|svg)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
			.replace(/<pre\b[^>]*>[\s\S]*?<\/pre>/gi, ' ')
			.replace(/<[^>]+>/g, ' ')
	)
		.replace(/\s+/g, ' ')
		.trim();
}

const firstMatch = (html, re) => {
	const m = html.match(re);
	return m ? toText(m[1]) : '';
};

/**
 * Every distinct identifier the page shows in <code> — node kinds, option names, env vars, CLI
 * flags, REST paths.
 *
 * This is the raw material for the client's substring pass, and it is collected from the WHOLE
 * page including the <pre> blocks that toText() throws away. The embedding model is weak on
 * exactly these strings: "fingerprint-dedup-apply" is not a word it has a representation for, it
 * is six WordPiece fragments, and a reader who types one is asking for a lookup rather than for
 * a paraphrase. Matching them literally is both cheaper and better than trying to embed them.
 */
function codeTermsOf(html) {
	const terms = new Set();
	for (const m of html.matchAll(/<code\b[^>]*>([\s\S]*?)<\/code>/gi)) {
		for (const raw of toText(m[1]).split(/[\s,;()[\]{}"']+/)) {
			const term = raw.replace(/^[.:=|]+|[.:=|,]+$/g, '').toLowerCase();
			// Below three characters a term matches everything; above forty it is a pasted line.
			if (term.length >= 3 && term.length <= 40 && /[a-z0-9]/.test(term)) terms.add(term);
		}
		if (terms.size > 400) break;
	}
	return [...terms].slice(0, 200);
}

/**
 * Cut one page into (anchor, heading, text) sections.
 *
 * Asciidoctor wraps every section in div.sect1/div.sect2 whose h2/h3 carries the id, so the
 * headings are both the natural chunk boundary and the deep-link target. Text before the first
 * heading becomes the page-top section with no anchor.
 */
function sectionsOf(body) {
	const headings = [...body.matchAll(/<h([23])\s+id="([^"]+)"[^>]*>([\s\S]*?)<\/h\1>/gi)];
	const sections = [];

	const intro = toText(body.slice(0, headings.length ? headings[0].index : body.length));
	if (intro) sections.push({ anchor: '', heading: '', text: intro });

	let currentH2 = '';
	for (let i = 0; i < headings.length; i++) {
		const [whole, level, anchor, rawTitle] = headings[i];
		const title = toText(rawTitle);
		if (level === '2') currentH2 = title;

		const from = headings[i].index + whole.length;
		const to = i + 1 < headings.length ? headings[i + 1].index : body.length;
		const text = toText(body.slice(from, to));
		if (!text) continue;

		// An h3 alone often reads as a fragment ("Which copy is kept"); its h2 supplies the topic.
		const heading = level === '3' && currentH2 && currentH2 !== title
			? `${currentH2} › ${title}`
			: title;
		sections.push({ anchor, heading, text });
	}
	return sections;
}

// ---------------------------------------------------------------------------------------------
// Chunking
// ---------------------------------------------------------------------------------------------

/**
 * Token cost of a string, excluding the [CLS]/[SEP] pair the tokenizer always adds.
 *
 * These payload costs are additive across a whitespace join — WordPiece pre-tokenises on
 * whitespace, so no token spans a boundary — which is what lets the packer below measure a
 * candidate chunk by adding numbers instead of re-tokenising the whole accumulator each time.
 * The overrun gate in main() is what keeps that assumption honest.
 *
 * tokenize() returns a fixed 128-slot array padded with zeroes, so the count saturates at
 * MAX_TOKENS. Callers must keep every measured string comfortably under that.
 */
function payload(text) {
	const ids = tokenize(text);
	let n = 0;
	for (let i = 0; i < ids.length; i++) if (ids[i] !== 0) n++;
	return n - 2;
}

/**
 * Split into sentences, then hard-split any piece still long enough to saturate the tokenizer's
 * 128-slot window. A run-on with no punctuation, or a line of dash-joined node kinds that
 * WordPiece shatters into six tokens apiece, would otherwise be mis-costed.
 */
function sentencesOf(text) {
	const rough = text.split(/(?<=[.!?:;])\s+(?=[A-Z(“"'\d])/);
	const out = [];
	for (const s of rough) {
		const words = s.split(' ').filter(Boolean);
		if (!words.length) continue;
		// 20 words cannot reach 128 tokens even at the corpus's worst token-per-word ratio.
		if (words.length <= 20) {
			out.push(s.trim());
			continue;
		}
		for (let i = 0; i < words.length; i += 20) out.push(words.slice(i, i + 20).join(' '));
	}
	return out.filter(Boolean);
}

/**
 * Greedily pack sentences into chunks that fit the model's window once the context prefix is
 * accounted for, overlapping by one sentence so a statement split across a boundary still has
 * one side that reads whole.
 *
 * The fit is measured with the model's own tokenizer rather than a word-count heuristic: this
 * corpus is full of long identifiers that WordPiece shatters into five or six tokens each, so a
 * "95 words" rule of thumb would silently overrun on exactly the pages that matter most.
 */
function chunkSection(prefix, text) {
	// A chunk is [CLS] + prefix + sentences + [SEP], and must come in under the window.
	const budget = MAX_TOKENS - 1 - 2 - payload(prefix);
	const sentences = sentencesOf(text).map((s) => ({ s, cost: payload(s) }));

	const chunks = [];
	let current = [];
	let cost = 0;

	for (const item of sentences) {
		if (!current.length) {
			current = [item];
			cost = item.cost;
			continue;
		}
		if (cost + item.cost <= budget) {
			current.push(item);
			cost += item.cost;
			continue;
		}
		chunks.push(current.map((c) => c.s).join(' '));
		// Carry the last sentence forward as overlap — unless it alone already fills the window.
		const tail = current[current.length - 1];
		if (tail.cost + item.cost <= budget) {
			current = [tail, item];
			cost = tail.cost + item.cost;
		} else {
			current = [item];
			cost = item.cost;
		}
	}
	if (current.length) chunks.push(current.map((c) => c.s).join(' '));
	return chunks;
}

// ---------------------------------------------------------------------------------------------
// Build
// ---------------------------------------------------------------------------------------------

function fail(message, detail = []) {
	console.error('');
	console.error(`ERROR: ${message}`);
	for (const line of detail) console.error(`  ${line}`);
	console.error('');
	process.exit(1);
}

/** Embed every text, fanned out over worker threads, preserving order. */
async function embedAll(texts) {
	const workers = Math.max(1, Math.min(8, availableParallelism() - 2));
	const perWorker = Math.ceil(texts.length / workers);
	const slices = [];
	for (let i = 0; i < texts.length; i += perWorker) slices.push(texts.slice(i, i + perWorker));

	const results = await Promise.all(slices.map((slice) => new Promise((ok, err) => {
		const worker = new Worker(new URL(import.meta.url), { workerData: { wasm: WASM, texts: slice } });
		worker.once('message', ok);
		worker.once('error', err);
		worker.once('exit', (code) => { if (code !== 0) err(new Error(`embedding worker exited with ${code}`)); });
	})));

	const all = new Float32Array(texts.length * DIMS);
	let offset = 0;
	for (const part of results) {
		all.set(part, offset);
		offset += part.length;
	}
	return all;
}

async function main() {
	const dist = resolve(process.argv[2] ?? join(HERE, 'dist'));

	let modelVersion;
	try {
		modelVersion = readFileSync(join(MODEL_DIR, 'VERSION'), 'utf8');
	} catch {
		fail('the Ternlight engine is not vendored.', [
			'themes/meghna-hugo/static/plugins/ternlight/ is missing or incomplete.',
			'Run ./vendor-ternlight.sh and commit the result.'
		]);
	}

	const wasmBytes = readFileSync(WASM);
	const wasmSha = createHash('sha256').update(wasmBytes).digest('hex');
	const recordedSha = (modelVersion.match(/^sha256\s+(\S+)/m) ?? [])[1];
	if (recordedSha !== wasmSha) {
		fail('the vendored wasm does not match its recorded checksum.', [
			`VERSION says ${recordedSha}`,
			`the file is    ${wasmSha}`,
			'Re-run ./vendor-ternlight.sh rather than editing VERSION.'
		]);
	}

	// The main thread tokenizes (to cost chunks); the workers run the forward passes.
	initSync({ module: wasmBytes });

	const pageFiles = walk(dist)
		.filter((f) => f.endsWith(sep + 'index.html'))
		.map((f) => ({ file: f, url: '/' + relative(dist, dirname(f)).split(sep).join('/') + '/' }))
		.filter(({ url }) => url.startsWith('/docs/'))
		.filter(({ url }) => !SKIP_PAGES.has(url) && !SKIP_PREFIXES.some((p) => url.startsWith(p)))
		.sort((a, b) => a.url.localeCompare(b.url));

	const pages = [];
	const chunks = [];
	const texts = [];
	const emptyPages = [];
	const unreadable = [];
	const overruns = [];

	for (const { file, url } of pageFiles) {
		const html = readFileSync(file, 'utf8');

		const title = firstMatch(html, /<header class="page-head[^"]*">[\s\S]*?<h1[^>]*>([\s\S]*?)<\/h1>/i);
		const eyebrow = firstMatch(html, /<p class="page-eyebrow">([\s\S]*?)<\/p>/i);
		if (!title) continue; // not rendered by a docs layout at all

		// A page with the docs header but no content wrapper means the layout was renamed under
		// this extractor. Skipping it quietly is how a whole section would drop out of search
		// while the build still reported success, so it is a failure rather than a `continue`.
		const contentStart = html.search(/<div class="post-single-content[^"]*"[^>]*>/);
		if (contentStart === -1) {
			unreadable.push(url);
			continue;
		}
		const body = sliceDiv(html, contentStart);
		if (body === null) {
			fail(`unbalanced content markup in ${url}`, ['The content column never closes — check the layout.']);
		}

		const pageIndex = pages.length;
		let produced = 0;

		for (const section of sectionsOf(body)) {
			// What the model reads is prefixed with where it is: a chunk deep inside a page is a
			// paragraph about nothing until it says which page and which section it came from.
			// The prefix is inside the token budget, not on top of it.
			const prefix = section.heading ? `${title} — ${section.heading}: ` : `${title}: `;

			for (const text of chunkSection(prefix, section.text)) {
				// The packer costs a chunk by adding per-sentence numbers. This is the one place
				// that measures the string the model actually receives — if the additivity
				// assumption ever stops holding, chunk tails would be silently truncated.
				if (payload(prefix + text) + 2 >= MAX_TOKENS) overruns.push(`${url}#${section.anchor}`);

				texts.push(prefix + text);
				chunks.push([
					pageIndex,
					section.anchor,
					section.heading,
					text.length > 180 ? text.slice(0, 179).replace(/\s+\S*$/, '') + '…' : text
				]);
				produced++;
			}
		}

		if (produced === 0) emptyPages.push(url);
		pages.push({ u: url, t: title, e: eyebrow, c: codeTermsOf(body) });
	}

	// -- Gates -------------------------------------------------------------------------------

	if (pages.length < MIN_PAGES) {
		fail(`only ${pages.length} documentation pages were indexed (expected at least ${MIN_PAGES}).`, [
			'Either the build output is incomplete, or the extractor no longer recognises the docs layout.'
		]);
	}

	if (unreadable.length) {
		fail(`${unreadable.length} documentation page(s) have no readable content column:`, [
			...unreadable,
			'',
			'These pages render the docs header but nothing this extractor recognises as a body.',
			'The ".post-single-content" wrapper in layouts/docs/{single,list}.html is what it reads —',
			'if that class was renamed, rename it here too rather than letting the pages fall out',
			'of search silently.'
		]);
	}

	if (overruns.length) {
		fail(`${overruns.length} chunk(s) overran the model's ${MAX_TOKENS}-token window:`, [
			...overruns.slice(0, 10),
			overruns.length > 10 ? `… and ${overruns.length - 10} more` : '',
			'',
			'The packer costs chunks by adding per-sentence token counts, which assumes WordPiece',
			'never merges across a whitespace join. That assumption has broken — the tail of these',
			'chunks would be dropped before the model ever saw it. Fix chunkSection() before shipping.'
		].filter(Boolean));
	}

	if (emptyPages.length) {
		fail(`${emptyPages.length} documentation page(s) produced no searchable text:`, [
			...emptyPages,
			'',
			'A page with no indexable prose cannot be found by search. Usually this means the page is',
			'nothing but passthrough HTML or images, or that the ".post-single-content" wrapper in',
			'layouts/docs/*.html was renamed and the extractor is now reading nothing.'
		]);
	}

	// -- Quantisation and output -------------------------------------------------------------

	const flat = await embedAll(texts);

	// The vectors are unit-normalised over 384 dimensions, so no component is ever near 1 — they
	// sit around 0.05 and peak well under 0.3. Scaling by 127 (the obvious choice) would throw
	// away most of the int8 range; scaling by the corpus maximum uses all of it. One global
	// factor, not one per vector, so the client can dot-product straight out of the blob.
	let maxAbs = 0;
	for (let i = 0; i < flat.length; i++) { const a = Math.abs(flat[i]); if (a > maxAbs) maxAbs = a; }
	const scale = 127 / maxAbs;

	const blob = new Int8Array(flat.length);
	for (let i = 0; i < flat.length; i++) {
		blob[i] = Math.max(-127, Math.min(127, Math.round(flat[i] * scale)));
	}

	const index = {
		// Identifies the embedding space. A browser holding a stale cached engine compares this
		// against the model it actually loaded: mismatched builds do not produce worse rankings,
		// they produce rankings against a different space, which is worth refusing outright.
		model: {
			package: (modelVersion.match(/^package\s+(.+)$/m) ?? [])[1] ?? '',
			sha256: wasmSha,
			engine: (modelVersion.match(/^engine\s+(.+)$/m) ?? [])[1] ?? ''
		},
		dims: DIMS,
		scale,
		count: chunks.length,
		pages,
		// Positional rows, not objects: 1,400 copies of {"p":…,"a":…,"h":…,"s":…} is ~40 KB of keys.
		chunks
	};

	const outDir = join(dist, 'search');
	mkdirSync(outDir, { recursive: true });
	writeFileSync(join(outDir, 'docs-index.json'), JSON.stringify(index));
	writeFileSync(join(outDir, 'docs-vectors.bin'), blob);

	const kb = (n) => `${Math.round(n / 1024)} KB`;
	console.log(
		`Search index OK — ${pages.length} pages, ${chunks.length} chunks, ` +
		`${kb(JSON.stringify(index).length)} metadata + ${kb(blob.byteLength)} vectors.`
	);
}

// Dispatched last, not beside the worker branch above: main() reads consts declared throughout
// this file, and calling it mid-module would hit their temporal dead zone.
if (isMainThread) await main();
